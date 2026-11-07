package dev.bridgedns.vpn;

final class NativeBridge {
    static { System.loadLibrary("masterdns"); System.loadLibrary("hev-socks5-tunnel"); System.loadLibrary("bridgedns"); }
    static void ensureLoaded() {}
    static native String selfTest();
    static native String runCore(String config, String resolvers, String log);
    static native void stopCore();
    static native int runTun(String config, int fd);
    static native void stopTun();
    static native long[] stats();
    static native long[] metrics();
    static native String resolvers();
}
