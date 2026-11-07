package dev.bridgedns.vpn;
import org.junit.Test;
import static org.junit.Assert.*;
public class ConfigRulesTest {
    @Test public void tomlEscapingPreservesSocketPathAndPreventsNewSettings(){
        assertEquals("\"/data/user/0/app/protect.sock\"",ConfigRules.tomlString("/data/user/0/app/protect.sock"));
        assertEquals("\"a\\\"\\nX=1\\\\\"",ConfigRules.tomlString("a\"\nX=1\\"));
    }
    @Test public void domainRejectsTomlInjectionAndInvalidLabels(){
        assertTrue(ConfigRules.domain("v.example.com"));
        assertTrue(ConfigRules.domain("v-2.example.com"));
        for(String s:new String[]{"example", "v..com", "v.example.com\"\nLISTEN_IP=\"0.0.0.0", "-v.example.com", "v.example.com.", "v_.example.com"}) assertFalse(s,ConfigRules.domain(s));
    }
    @Test public void resolverDoesNotAcceptHostnamesOrCidrs(){
        assertTrue(ConfigRules.resolver("1.1.1.1:53"));assertTrue(ConfigRules.resolver("8.8.8.8"));
        for(String s:new String[]{"resolver.example.com:53","1.1.1.256","1.1.1.1:0","1.1.1.1:65536","1.1.1.0/24","1.1.1.1:53\nKEY=x","1.1.1.1:53:54"}) assertFalse(s,ConfigRules.resolver(s));
    }
    @Test public void omittedOrEmptyPortUses53AndExplicitPortIsPreserved(){
        assertEquals("1.1.1.1:53\n8.8.8.8:53\n9.9.9.9:5353\n",ConfigRules.normalizeResolvers("1.1.1.1\n8.8.8.8:\n9.9.9.9:5353\n"));
    }
    @Test public void resolverListHasNoArtificialCountLimit(){
        StringBuilder input=new StringBuilder();
        for(int i=0;i<10000;i++)input.append("10.0.").append(i/256).append('.').append(i%256).append('\n');
        String output=ConfigRules.normalizeResolvers(input.toString());
        assertEquals(10000,output.split("\n").length);
        assertTrue(output.endsWith("10.0.39.15:53\n"));
    }
}
