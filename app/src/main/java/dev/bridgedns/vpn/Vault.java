package dev.bridgedns.vpn;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import org.json.JSONArray;

final class Vault {
    private static SecretKey key() throws Exception {
        KeyStore ks=KeyStore.getInstance("AndroidKeyStore"); ks.load(null);
        if(!ks.containsAlias("bridgedns.profiles")) {
            KeyGenerator g=KeyGenerator.getInstance("AES","AndroidKeyStore");
            g.init(new KeyGenParameterSpec.Builder("bridgedns.profiles",KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes("GCM").setEncryptionPaddings("NoPadding").build()); g.generateKey();
        }
        return (SecretKey)ks.getKey("bridgedns.profiles",null);
    }
    static JSONArray load(Context c) throws Exception {
        String v=c.getSharedPreferences("vault",0).getString("profiles",""); if(v.isEmpty()) return new JSONArray();
        String[] x=v.split(":",2); Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE,key(),new GCMParameterSpec(128,Base64.decode(x[0],2)));
        return new JSONArray(new String(cipher.doFinal(Base64.decode(x[1],2)),java.nio.charset.StandardCharsets.UTF_8));
    }
    static void save(Context c,JSONArray profiles) throws Exception {
        Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE,key());
        String v=Base64.encodeToString(cipher.getIV(),2)+":"+Base64.encodeToString(cipher.doFinal(profiles.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)),2);
        if(!c.getSharedPreferences("vault",0).edit().putString("profiles",v).commit()) throw new java.io.IOException("Could not save profile");
    }
}
