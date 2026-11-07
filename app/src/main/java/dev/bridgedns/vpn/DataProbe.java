package dev.bridgedns.vpn;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;
import javax.net.ssl.*;

/** A real, user-initiated request through SOCKS; no direct-network fallback. */
final class DataProbe {
    static Socket openSocks(String host,int port,int proxyPort,Consumer<String> log) throws IOException {return openSocks(host,port,proxyPort,log,null);}
    static Socket openSocks(String host,int port,int proxyPort,Consumer<String> log,Consumer<Socket> observe) throws IOException {
        Socket socket=new Socket();if(observe!=null)observe.accept(socket);
        try {
            socket.connect(new InetSocketAddress("127.0.0.1",proxyPort),3000);
            socket.setSoTimeout(3000);
            OutputStream out=socket.getOutputStream();InputStream in=socket.getInputStream();
            out.write(new byte[]{5,1,0});out.flush();
            byte[] method=readExactly(in,2);
            if(method[0]!=5||method[1]!=0)throw new IOException("Local SOCKS did not accept unauthenticated SOCKS5");
            log.accept("[probe] Local SOCKS negotiation passed");
            byte[] target=host.getBytes(StandardCharsets.US_ASCII);
            if(target.length==0||target.length>253)throw new IOException("Invalid target hostname");
            ByteArrayOutputStream request=new ByteArrayOutputStream();
            request.write(new byte[]{5,1,0,3,(byte)target.length});request.write(target);request.write(port>>8);request.write(port&255);
            socket.setSoTimeout(25000);out.write(request.toByteArray());out.flush();
            log.accept("[probe] Waiting for remote CONNECT confirmation: "+host+":"+port);
            byte[] response=readExactly(in,4);
            if(response[0]!=5||response[2]!=0)throw new IOException("Invalid SOCKS reply");
            if(response[1]!=0)throw new IOException("Remote CONNECT rejected: "+replyName(response[1]&255)+" ("+(response[1]&255)+")");
            switch(response[3]){
                case 1:readExactly(in,4);break;
                case 4:readExactly(in,16);break;
                case 3:readExactly(in,readExactly(in,1)[0]&255);break;
                default:throw new IOException("Invalid SOCKS address type");
            }
            readExactly(in,2);log.accept("[probe] Remote TCP connection confirmed by tunnel server");
            return socket;
        }catch(IOException e){try{socket.close();}catch(IOException ignored){}throw e;}
    }
    static void run(String host,Consumer<String> log) throws IOException {run(host,log,null);}
    static void run(String host,Consumer<String> log,Consumer<Socket> observe) throws IOException {
        String stage="SOCKS / remote CONNECT";
        log.accept("[probe] Testing HTTPS data through tunnel to "+host+":443");
        try(Socket raw=openSocks(host,443,18000,log,observe)){
            stage="TLS handshake";
            try(SSLSocket tls=(SSLSocket)((SSLSocketFactory)SSLSocketFactory.getDefault()).createSocket(raw,host,443,true)){
                tls.setSoTimeout(20000);SSLParameters params=tls.getSSLParameters();params.setEndpointIdentificationAlgorithm("HTTPS");tls.setSSLParameters(params);
                tls.startHandshake();log.accept("[probe] TLS handshake and hostname verification passed");
                stage="HTTP request / response";
                byte[] request=("GET / HTTP/1.1\r\nHost: "+host+"\r\nUser-Agent: BridgeDNS/0.1.0\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII);
                tls.getOutputStream().write(request);tls.getOutputStream().flush();
                ByteArrayOutputStream response=new ByteArrayOutputStream();int matched=0;
                while(response.size()<16384){int b=tls.getInputStream().read();if(b<0)break;response.write(b);int expected=(matched==0||matched==2)?13:10;if(b==expected)matched++;else matched=b==13?1:0;if(matched==4)break;}
                String header=response.toString("ISO-8859-1");String first=header.split("\r?\n",2)[0];
                if(!first.startsWith("HTTP/"))throw new IOException("No HTTP status line was received");
                log.accept("[probe] PASS: "+first+"; sent "+request.length+" HTTP bytes, received "+response.size()+" header bytes through SOCKS");
                log.accept("[probe] This verifies this HTTPS destination via SOCKS; it does not verify whole-device TUN routing.");
            }
        }catch(IOException e){throw new IOException("[probe] FAILED at "+stage+": "+e.getClass().getSimpleName()+": "+e.getMessage(),e);}
    }
    static byte[] readExactly(InputStream in,int count) throws IOException {
        byte[] data=new byte[count];int offset=0;
        while(offset<count){int n=in.read(data,offset,count-offset);if(n<0)throw new EOFException("Connection closed while waiting for SOCKS reply");offset+=n;}
        return data;
    }
    static String replyName(int rep){switch(rep){case 1:return "general failure";case 2:return "server ruleset denied";case 3:return "network unreachable";case 4:return "host unreachable";case 5:return "connection refused";case 6:return "TTL expired";case 7:return "command unsupported";case 8:return "address type unsupported";default:return "unknown error";}}
}
