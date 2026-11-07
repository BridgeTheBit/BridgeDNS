package dev.bridgedns.vpn;

import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import javax.net.ssl.*;

/** Small HTTPS samples through local SOCKS only; never the direct network. */
final class TunnelSpeedTest implements AutoCloseable {
    volatile Socket current;
    volatile boolean cancelled;
    final ScheduledExecutorService timer=Executors.newSingleThreadScheduledExecutor();
    public void close(){cancelled=true;Socket s=current;if(s!=null)try{s.close();}catch(IOException ignored){}timer.shutdownNow();}
    SSLSocket connect(String host) throws IOException {
        if(cancelled)throw new IOException("Test cancelled");
        Socket raw=DataProbe.openSocks(host,443,18000,line->{},s->{current=s;if(cancelled)try{s.close();}catch(IOException ignored){}});current=raw;
        if(cancelled){raw.close();throw new IOException("Test cancelled");}
        SSLSocket tls=(SSLSocket)((SSLSocketFactory)SSLSocketFactory.getDefault()).createSocket(raw,host,443,true);current=tls;
        tls.setSoTimeout(20000);SSLParameters parameters=tls.getSSLParameters();parameters.setEndpointIdentificationAlgorithm("HTTPS");tls.setSSLParameters(parameters);tls.startHandshake();return tls;
    }
    void run(Consumer<String> log) throws IOException {
        String host="speed.cloudflare.com";
        log.accept("Endpoint: Cloudflare • SOCKS tunnel only\nDownload sample ≤64 KiB; upload 8 KiB. TLS verified. DNS tunnel overhead is extra.");
        String stage="TLS / remote connection";
        try {
            ScheduledFuture<?> deadline=timer.schedule(()->{Socket s=current;if(s!=null)try{s.close();}catch(IOException ignored){}},45,TimeUnit.SECONDS);
            long start=System.nanoTime();
            try(SSLSocket tls=connect(host)){
                stage="download HTTP";
                request(tls,"GET /__down?bytes=65536 HTTP/1.1\r\nHost: "+host+"\r\nAccept-Encoding: identity\r\nCache-Control: no-cache\r\nConnection: close\r\n\r\n");
                InputStream in=tls.getInputStream();Map<String,String> headers=readHeaders(in);
                double first=(System.nanoTime()-start)/1e6;log.accept(String.format(Locale.US,"HTTPS response: %.0f ms (connection + TLS + first response)",first));
                stage="download body";long bodyStart=System.nanoTime();long n=readBody(in,headers,65536);double seconds=Math.max(.001,(System.nanoTime()-bodyStart)/1e9);
                if(n<1024)throw new IOException("Download sample too small");
                log.accept(String.format(Locale.US,"Download: %.1f KB/s • %.3f Mbps\n%,d payload bytes in %.2f s",n/1024.0/seconds,n*8.0/seconds/1e6,n,seconds));
            }finally{current=null;deadline.cancel(false);}
            stage="upload connection";
            deadline=timer.schedule(()->{Socket s=current;if(s!=null)try{s.close();}catch(IOException ignored){}},45,TimeUnit.SECONDS);
            try(SSLSocket tls=connect(host)){
                byte[] payload=new byte[8192];new java.security.SecureRandom().nextBytes(payload);stage="upload HTTP";
                long sentAt=System.nanoTime();request(tls,"POST /__up HTTP/1.1\r\nHost: "+host+"\r\nContent-Type: application/octet-stream\r\nContent-Length: "+payload.length+"\r\nConnection: close\r\n\r\n");
                tls.getOutputStream().write(payload);tls.getOutputStream().flush();readHeaders(tls.getInputStream());double seconds=Math.max(.001,(System.nanoTime()-sentAt)/1e9);
                log.accept(String.format(Locale.US,"Upload: %.1f KB/s • %.3f Mbps\n%,d bytes; HTTP-confirmed in %.2f s",payload.length/1024.0/seconds,payload.length*8.0/seconds/1e6,payload.length,seconds));
                log.accept("PASS • HTTPS works through SOCKS. This test does not traverse Android TUN. Short samples are estimates, not line-capacity measurements.");
            }finally{current=null;deadline.cancel(false);}
        }catch(IOException e){throw new IOException((cancelled?"Cancelled":"Failed at "+stage)+": "+e.getMessage(),e);}
    }
    static void request(Socket s,String header)throws IOException{s.getOutputStream().write(header.getBytes(StandardCharsets.US_ASCII));s.getOutputStream().flush();}
    static String line(InputStream in)throws IOException{
        ByteArrayOutputStream b=new ByteArrayOutputStream();int previous=-1;
        while(b.size()<8192){int n=in.read();if(n<0)throw new EOFException("HTTP response ended early");if(previous==13&&n==10){byte[] bytes=b.toByteArray();return new String(bytes,0,bytes.length-1,StandardCharsets.ISO_8859_1);}b.write(n);previous=n;}
        throw new IOException("HTTP line too long");
    }
    static Map<String,String> readHeaders(InputStream in)throws IOException{
        String status=line(in);String[] parts=status.split(" ",3);if(parts.length<2||!parts[0].startsWith("HTTP/"))throw new IOException("Invalid HTTP response");
        int code;try{code=Integer.parseInt(parts[1]);}catch(NumberFormatException e){throw new IOException("Invalid HTTP status");}
        if(code<200||code>=300)throw new IOException("HTTP "+code);
        Map<String,String> h=new HashMap<>();for(int i=0;i<128;i++){String s=line(in);if(s.isEmpty())return h;int p=s.indexOf(':');if(p>0)h.put(s.substring(0,p).trim().toLowerCase(Locale.US),s.substring(p+1).trim());}throw new IOException("Too many HTTP headers");
    }
    static long readBody(InputStream in,Map<String,String> headers,int limit)throws IOException{
        long total=0;byte[] buffer=new byte[8192];boolean chunked=headers.getOrDefault("transfer-encoding","").toLowerCase(Locale.US).contains("chunked");
        if(chunked){while(total<limit){String size=line(in).split(";",2)[0].trim();long length;try{length=Long.parseLong(size,16);}catch(NumberFormatException e){throw new IOException("Invalid chunk size");}if(length<0)throw new IOException("Invalid chunk size");if(length==0)return total;
            long left=length;while(left>0&&total<limit){int n=in.read(buffer,0,(int)Math.min(buffer.length,Math.min(left,limit-total)));if(n<0)throw new EOFException("Incomplete HTTP chunk");left-=n;total+=n;}if(total==limit)return total;if(!line(in).isEmpty())throw new IOException("Invalid chunk terminator");}return total;}
        long expected=-1;if(headers.containsKey("content-length"))try{expected=Long.parseLong(headers.get("content-length"));if(expected<0)throw new NumberFormatException();}catch(NumberFormatException e){throw new IOException("Invalid content length");}
        long target=expected<0?limit:Math.min(limit,expected);
        while(total<target){int n=in.read(buffer,0,(int)Math.min(buffer.length,target-total));if(n<0){if(expected>=0)throw new EOFException("Incomplete HTTP body");break;}total+=n;}return total;
    }
}
