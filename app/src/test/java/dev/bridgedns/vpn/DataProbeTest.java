package dev.bridgedns.vpn;

import org.junit.Test;
import static org.junit.Assert.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

public class DataProbeTest {
    static byte[] read(InputStream in,int n)throws IOException{byte[] b=new byte[n];new DataInputStream(in).readFully(b);return b;}
    static void negotiate(Socket s)throws IOException{
        assertArrayEquals(new byte[]{5,1,0},read(s.getInputStream(),3));s.getOutputStream().write(new byte[]{5,0});
        byte[] h=read(s.getInputStream(),5);assertArrayEquals(new byte[]{5,1,0,3},Arrays.copyOf(h,4));
        assertEquals("probe.example",new String(read(s.getInputStream(),h[4]&255),StandardCharsets.US_ASCII));
        assertArrayEquals(new byte[]{1,(byte)187},read(s.getInputStream(),2));
    }
    @Test public void domainConnectAndBidirectionalPayloadThroughSocks()throws Exception{
        ExecutorService pool=Executors.newSingleThreadExecutor();
        try(ServerSocket server=new ServerSocket(0,1,InetAddress.getByName("127.0.0.1"))){
            Future<?> mock=pool.submit(()->{try(Socket s=server.accept()){
                s.setSoTimeout(3000);negotiate(s);
                s.getOutputStream().write(new byte[]{5,0,0,4});s.getOutputStream().write(new byte[18]);
                byte[] data=read(s.getInputStream(),4096);s.getOutputStream().write(data);
            }catch(IOException e){throw new RuntimeException(e);}});
            List<String> logs=new ArrayList<>();byte[] payload=new byte[4096];new Random(11).nextBytes(payload);
            try(Socket tunnel=DataProbe.openSocks("probe.example",443,server.getLocalPort(),logs::add)){
                tunnel.getOutputStream().write(payload);assertArrayEquals(payload,read(tunnel.getInputStream(),payload.length));
            }
            mock.get(5,TimeUnit.SECONDS);assertTrue(logs.get(logs.size()-1).contains("confirmed"));
        }finally{pool.shutdownNow();}
    }
    @Test public void remoteRulesetRejectionIsNotReportedAsDataSuccess()throws Exception{
        ExecutorService pool=Executors.newSingleThreadExecutor();
        try(ServerSocket server=new ServerSocket(0,1,InetAddress.getByName("127.0.0.1"))){
            Future<?> mock=pool.submit(()->{try(Socket s=server.accept()){s.setSoTimeout(3000);negotiate(s);s.getOutputStream().write(new byte[]{5,2,0,1});assertEquals(-1,s.getInputStream().read());}catch(IOException e){throw new RuntimeException(e);}});
            try{DataProbe.openSocks("probe.example",443,server.getLocalPort(),line->{});fail("Must reject SOCKS error");}
            catch(IOException e){assertTrue(e.getMessage().contains("ruleset denied"));}
            mock.get(5,TimeUnit.SECONDS);
        }finally{pool.shutdownNow();}
    }
    @Test public void truncatedReplyDoesNotPass()throws Exception{
        ExecutorService pool=Executors.newSingleThreadExecutor();
        try(ServerSocket server=new ServerSocket(0,1,InetAddress.getByName("127.0.0.1"))){
            Future<?> mock=pool.submit(()->{try(Socket s=server.accept()){s.setSoTimeout(3000);negotiate(s);s.getOutputStream().write(new byte[]{5,0,0,1,127});}catch(IOException e){throw new RuntimeException(e);}});
            try{DataProbe.openSocks("probe.example",443,server.getLocalPort(),line->{});fail("Must reject truncated reply");}
            catch(EOFException expected){}
            mock.get(5,TimeUnit.SECONDS);
        }finally{pool.shutdownNow();}
    }
}
