#include <jni.h>
#include <stdlib.h>
#include <stddef.h>
#include "libmasterdns.h"
extern int hev_socks5_tunnel_main_from_file(const char *, int);
extern void hev_socks5_tunnel_quit(void);
extern void hev_socks5_tunnel_stats(size_t *, size_t *, size_t *, size_t *);
JNIEXPORT jstring JNICALL Java_dev_bridgedns_vpn_NativeBridge_selfTest(JNIEnv *e,jclass c) {
    return (*e)->NewStringUTF(e,"MasterDnsVPN core and TUN JNI libraries loaded successfully");
}
JNIEXPORT jstring JNICALL Java_dev_bridgedns_vpn_NativeBridge_runCore(JNIEnv *e,jclass c,jstring cfg,jstring res,jstring log) {
    const char *a=(*e)->GetStringUTFChars(e,cfg,0), *b=(*e)->GetStringUTFChars(e,res,0), *d=(*e)->GetStringUTFChars(e,log,0);
    char *err=NavaRun((char *)a,(char *)b,(char *)d);
    (*e)->ReleaseStringUTFChars(e,cfg,a); (*e)->ReleaseStringUTFChars(e,res,b); (*e)->ReleaseStringUTFChars(e,log,d);
    jstring result=(*e)->NewStringUTF(e,err ? err : ""); free(err); return result;
}
JNIEXPORT void JNICALL Java_dev_bridgedns_vpn_NativeBridge_stopCore(JNIEnv *e,jclass c) { NavaStop(); }
JNIEXPORT jint JNICALL Java_dev_bridgedns_vpn_NativeBridge_runTun(JNIEnv *e,jclass c,jstring cfg,jint fd) {
    const char *a=(*e)->GetStringUTFChars(e,cfg,0); int r=hev_socks5_tunnel_main_from_file(a,fd); (*e)->ReleaseStringUTFChars(e,cfg,a); return r;
}
JNIEXPORT void JNICALL Java_dev_bridgedns_vpn_NativeBridge_stopTun(JNIEnv *e,jclass c) { hev_socks5_tunnel_quit(); }
JNIEXPORT jlongArray JNICALL Java_dev_bridgedns_vpn_NativeBridge_stats(JNIEnv *e,jclass c) {
    size_t tp=0,tb=0,rp=0,rb=0; hev_socks5_tunnel_stats(&tp,&tb,&rp,&rb); jlong v[2]={(jlong)tb,(jlong)rb};
    jlongArray a=(*e)->NewLongArray(e,2); (*e)->SetLongArrayRegion(e,a,0,2,v); return a;
}

JNIEXPORT jlongArray JNICALL Java_dev_bridgedns_vpn_NativeBridge_metrics(JNIEnv *e,jclass c) {
 long long snapshot[8]={0};NavaSnapshot(snapshot);jlong values[8];
 for(int i=0;i<8;i++) values[i]=(jlong)snapshot[i];
 jlongArray result=(*e)->NewLongArray(e,8);(*e)->SetLongArrayRegion(e,result,0,8,values);return result;
}

JNIEXPORT jstring JNICALL Java_dev_bridgedns_vpn_NativeBridge_resolvers(JNIEnv *e,jclass c) {
 char *json=NavaResolvers();jstring result=(*e)->NewStringUTF(e,json?json:"[]");free(json);return result;
}
