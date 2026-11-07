package dev.bridgedns.vpn;

final class ConfigRules {
    static String tomlString(String s) {
        StringBuilder b=new StringBuilder("\"");
        for(int i=0;i<s.length();i++) {
            char c=s.charAt(i);
            switch(c) {
                case '"': b.append("\\\"");break;
                case '\\': b.append("\\\\");break;
                case '\n': b.append("\\n");break;
                case '\r': b.append("\\r");break;
                case '\t': b.append("\\t");break;
                default: if(c<32 || c==127) b.append(String.format(java.util.Locale.US,"\\u%04x",(int)c)); else b.append(c);
            }
        }
        return b.append('"').toString();
    }
    static boolean domain(String s) {
        if(s==null || s.length()>253 || !s.contains(".")) return false;
        for(String label:s.split("\\.",-1)) if(!label.matches("[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?")) return false;
        return true;
    }
    static boolean resolver(String s) {
        if(s.endsWith(":")) s=s.substring(0,s.length()-1);
        String[] parts=s.split(":",-1); if(parts.length>2) return false;
        String[] octets=parts[0].split("\\.",-1); if(octets.length!=4) return false;
        try {
            for(String x:octets) if(!x.matches("[0-9]{1,3}") || Integer.parseInt(x)>255) return false;
            if(parts.length==2 && (!parts[1].matches("[0-9]{1,5}") || Integer.parseInt(parts[1])<1 || Integer.parseInt(parts[1])>65535)) return false;
        } catch(NumberFormatException e) { return false; }
        return true;
    }
    static String normalizeResolvers(String text) {
        StringBuilder out=new StringBuilder();
        for(String line:text.split("\\r?\\n")) {
            String s=line.trim(); if(s.isEmpty()) continue;
            if(!resolver(s)) throw new IllegalArgumentException("Invalid resolver: "+s);
            if(s.endsWith(":")) s += "53";
            else if(!s.contains(":")) s += ":53";
            out.append(s).append('\n');
        }
        if(out.length()==0) throw new IllegalArgumentException("Enter at least one resolver");
        return out.toString();
    }
}
