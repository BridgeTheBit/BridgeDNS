package dev.bridgedns.vpn;

import org.json.JSONObject;
import java.util.Locale;

final class Profile {
    String name="My server", domain="", key="", resolvers="1.1.1.1:53\n8.8.8.8:53", bypass="", allowedApps="";
    int encryption=1, preset=0;
    boolean vpn=true, routeAllApps=true;
    boolean compatibility=false;
    boolean multipath=false;
    int maxPaths=4;
    int scanConcurrency=8;
    static Profile parse(String text) throws Exception {
        JSONObject j=new JSONObject(text); Profile p=new Profile();
        p.name=j.optString("name",p.name); p.domain=j.optString("domain",""); p.key=j.optString("key","");
        p.resolvers=j.optString("resolvers",p.resolvers); p.bypass=j.optString("bypass","");
        p.routeAllApps=j.optBoolean("routeAllApps",true);p.allowedApps=j.optString("allowedApps","");
        p.encryption=j.optInt("encryption",1); p.preset=j.optInt("preset",0); p.vpn=j.optBoolean("vpn",true);p.compatibility=j.optBoolean("compatibility",false); p.multipath=j.optBoolean("multipath",false);p.maxPaths=j.optInt("maxPaths",4); p.scanConcurrency=j.optInt("scanConcurrency",8); return p;
    }
    String json() throws Exception {
        return new JSONObject().put("name",name).put("domain",domain).put("key",key)
            .put("bypass",bypass).put("routeAllApps",routeAllApps).put("allowedApps",allowedApps)
            .put("encryption",encryption).put("preset",preset).put("vpn",vpn).put("compatibility",compatibility).put("multipath",multipath).put("maxPaths",maxPaths).put("scanConcurrency",scanConcurrency).toString();
    }
    String exportJson(boolean includeKey) throws Exception {
        JSONObject j=new JSONObject(json());if(!includeKey)j.put("key","");return j.toString(2);
    }
    String resolverCacheId() throws Exception {
        byte[] digest=java.security.MessageDigest.getInstance("SHA-256").digest((domain.toLowerCase(Locale.ROOT)+"\n"+encryption+"\n"+key+"\n"+compatibility).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        StringBuilder out=new StringBuilder();for(byte b:digest)out.append(String.format(Locale.US,"%02x",b&255));return out.toString();
    }
    void validate(){validate(true);}
    void validate(boolean requireKey) {
        if(scanConcurrency<1||scanConcurrency>64)throw new IllegalArgumentException("Scan concurrency must be between 1 and 64");
        if(!ConfigRules.domain(domain)) throw new IllegalArgumentException("Invalid tunnel domain. Example: v.example.com");
        if(maxPaths<0) throw new IllegalArgumentException("Maximum paths must be zero or a positive number");
        if(encryption<0 || encryption>5 || preset<0 || preset>2) throw new IllegalArgumentException("Invalid settings");
        if(requireKey && encryption!=0 && key.isEmpty()) throw new IllegalArgumentException("Enter the server key");
        if(key.length()>1024 || name.length()>100) throw new IllegalArgumentException("The profile name or key is too long");
        int count=0; for(String s:resolvers.split("\\r?\\n")) if(!s.trim().isEmpty()) {
            if(!ConfigRules.resolver(s.trim())) throw new IllegalArgumentException("Resolver must be an IP or IP:port: "+s);
            count++;
        }
        if(count==0) throw new IllegalArgumentException("Enter at least one resolver");
        for(String s:bypass.split("[,\\s]+")) if(!s.isEmpty() && !s.matches("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+")) throw new IllegalArgumentException("Invalid app package name");
        int selected=0;
        for(String s:allowedApps.split("[,\\s]+")) if(!s.isEmpty()) {
            if(!s.matches("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+")) throw new IllegalArgumentException("Invalid selected app package name");
            selected++;
        }
        if(vpn&&!routeAllApps&&selected==0) throw new IllegalArgumentException("Select at least one app to route through the VPN");
    }
    String toml(String socketPath) {
        int duplicate=multipath?1:compatibility?1:preset==1?1:preset==2?3:2;
        return "DOMAINS = ["+ConfigRules.tomlString(domain.toLowerCase(Locale.ROOT))+"]\nENCRYPTION_KEY = "+ConfigRules.tomlString(key)+
            "\nDATA_ENCRYPTION_METHOD = "+encryption+"\nPROTOCOL_TYPE = \"SOCKS5\"\nLISTEN_IP = \"127.0.0.1\"\nLISTEN_PORT = 18000\n"+
            "SOCKS5_AUTH = false\nLOCAL_DNS_ENABLED = false\nRESOLVER_BALANCING_STRATEGY = "+(compatibility?3:5)+"\nPACKET_DUPLICATION_COUNT = "+duplicate+
            "\nSETUP_PACKET_DUPLICATION_COUNT = "+(duplicate+1)+"\nMAX_DOWNLOAD_MTU = "+(compatibility?512:1232)+"\nMAX_UPLOAD_MTU = 150\nCOMPRESSION_MIN_SIZE = 120\n"+
            "BASE_ENCODE_DATA = "+compatibility+"\n"+(compatibility?"ARQ_WINDOW_SIZE = 64\nRX_TX_WORKERS = 1\n":"")+
            "NAVA_SCAN_PARALLELISM = "+scanConcurrency+"\n"+
            "NAVA_MULTIPATH_ENABLED = "+multipath+"\nNAVA_MULTIPATH_LIMIT = "+maxPaths+"\n"+
            "AUTO_REMOVE_LOW_MTU_SERVERS = true\nRECHECK_INACTIVE_SERVERS_ENABLED = true\nAUTO_DISABLE_TIMEOUT_SERVERS = true\n"+
            "UPLOAD_COMPRESSION_TYPE = 0\nDOWNLOAD_COMPRESSION_TYPE = 0\nLOG_LEVEL = \"INFO\"\nFD_CONTROL_UNIX_SOCKET = "+ConfigRules.tomlString(socketPath)+"\n";
    }
}
