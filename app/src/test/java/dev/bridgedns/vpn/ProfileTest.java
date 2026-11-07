package dev.bridgedns.vpn;
import org.junit.Test;
import static org.junit.Assert.*;
public class ProfileTest {
 @Test public void oldProfilesKeepOriginalRouting() throws Exception {
  Profile p=Profile.parse("{\"domain\":\"v.example.com\",\"key\":\"test\",\"preset\":2}");
  assertFalse(p.multipath);assertEquals(4,p.maxPaths);
  assertTrue(p.toml("/tmp/protect").contains("PACKET_DUPLICATION_COUNT = 3\n"));
  assertTrue(p.toml("/tmp/protect").contains("NAVA_MULTIPATH_ENABLED = false\n"));
 }
 @Test public void multipathSettingsSurviveSaveAndGenerateConfig() throws Exception {
  Profile p=new Profile();p.multipath=true;p.maxPaths=10;p.preset=2;p.compatibility=true;
  Profile copy=Profile.parse(p.json());assertTrue(copy.multipath);assertEquals(10,copy.maxPaths);
  String cfg=copy.toml("/tmp/protect");assertTrue(cfg.contains("NAVA_MULTIPATH_LIMIT = 10\n"));assertTrue(cfg.contains("PACKET_DUPLICATION_COUNT = 1\n"));assertTrue(cfg.contains("BASE_ENCODE_DATA = true\n"));
  copy.maxPaths=0;assertTrue(copy.toml("/tmp/protect").contains("NAVA_MULTIPATH_LIMIT = 0\n"));
 }
 @Test public void scanSettingsAndSecretExportsAreExplicit() throws Exception {
  Profile p=Profile.parse("{\"domain\":\"m.example.com\",\"key\":\"synthetic-server-secret\"}");
  assertEquals(8,p.scanConcurrency);p.scanConcurrency=32;p.validate();
  Profile saved=Profile.parse(p.json());assertEquals(32,saved.scanConcurrency);assertTrue(saved.toml("/tmp/p").contains("NAVA_SCAN_PARALLELISM = 32\n"));
  assertEquals(p.key,Profile.parse(p.exportJson(true)).key);assertEquals("",Profile.parse(p.exportJson(false)).key);assertFalse(p.exportJson(false).contains(p.key));
  String id=p.resolverCacheId();p.name="Renamed";p.resolvers="8.8.8.8";assertEquals(id,p.resolverCacheId());
  p.domain="other.example.com";assertNotEquals(id,p.resolverCacheId());p.domain="m.example.com";p.key="other-secret";assertNotEquals(id,p.resolverCacheId());
  for(int n:new int[]{0,65}){p.scanConcurrency=n;try{p.validate();fail("invalid concurrency accepted");}catch(IllegalArgumentException expected){}}
 }
}
