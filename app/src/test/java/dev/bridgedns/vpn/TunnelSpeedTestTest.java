package dev.bridgedns.vpn;
import org.junit.Test;
import static org.junit.Assert.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
public class TunnelSpeedTestTest {
 ByteArrayInputStream input(String s){return new ByteArrayInputStream(s.getBytes(StandardCharsets.ISO_8859_1));}
 @Test public void fixedAndChunkedBodiesCountOnlyPayload()throws Exception{
  InputStream fixed=input("HTTP/1.1 200 OK\r\nContent-Length: 5\r\n\r\nhelloEXTRA");Map<String,String> h=TunnelSpeedTest.readHeaders(fixed);assertEquals(5,TunnelSpeedTest.readBody(fixed,h,100));assertEquals('E',fixed.read());
  InputStream chunked=input("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n3\r\nabc\r\n2;ext=1\r\nde\r\n0\r\n\r\n");h=TunnelSpeedTest.readHeaders(chunked);assertEquals(5,TunnelSpeedTest.readBody(chunked,h,100));
 }
 @Test public void sampleIsBoundedAndTruncationFails()throws Exception{
  assertEquals(3,TunnelSpeedTest.readBody(input("abcdefgh"),Collections.singletonMap("content-length","8"),3));
  try{TunnelSpeedTest.readBody(input("short"),Collections.singletonMap("content-length","20"),100);fail();}catch(EOFException expected){}
  try{TunnelSpeedTest.readBody(input("5\r\na"),Collections.singletonMap("transfer-encoding","chunked"),100);fail();}catch(EOFException expected){}
 }
 @Test public void errorsAndRedirectsAreNotReportedAsSpeed()throws Exception{
  for(String status:new String[]{"HTTP/1.1 403 Denied","HTTP/1.1 302 Redirect","NOT HTTP"})try{TunnelSpeedTest.readHeaders(input(status+"\r\n\r\n"));fail(status);}catch(IOException expected){}
 }
}
