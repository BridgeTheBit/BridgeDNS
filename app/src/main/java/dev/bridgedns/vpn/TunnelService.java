package dev.bridgedns.vpn;

import android.annotation.SuppressLint;
import android.app.*;
import android.content.*;
import android.net.*;
import android.os.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

public final class TunnelService extends VpnService {
    static final String RESOLVERS_QUERY="dev.bridgedns.vpn.RESOLVERS_QUERY", RESOLVERS_STATE="dev.bridgedns.vpn.RESOLVERS_STATE";
    static final String STATE="dev.bridgedns.vpn.STATE", STOP="dev.bridgedns.vpn.STOP", QUERY="dev.bridgedns.vpn.QUERY";
    final Handler handler=new Handler(Looper.getMainLooper());
    final AtomicBoolean stopping=new AtomicBoolean();
    volatile boolean active=false, coreExited=false;
    volatile String status="Ready", error="";
    Profile profile;
    ParcelFileDescriptor tun;
    LocalSocket bound;
    LocalServerSocket protectServer;
    Thread coreThread,tunThread;
    long started;
    boolean launched=false;
    final BroadcastReceiver control=new BroadcastReceiver(){ public void onReceive(Context c,Intent i){ if(STOP.equals(i.getAction())) shutdown(); else if(RESOLVERS_QUERY.equals(i.getAction())) publishResolvers(); else publish(); }};
    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    @Override public void onCreate(){
        super.onCreate(); IntentFilter f=new IntentFilter(STOP); f.addAction(QUERY);f.addAction(RESOLVERS_QUERY);
        if(Build.VERSION.SDK_INT>=33) registerReceiver(control,f,Context.RECEIVER_NOT_EXPORTED); else registerReceiver(control,f);
        NotificationManager nm=getSystemService(NotificationManager.class); nm.createNotificationChannel(new NotificationChannel("tunnel","BridgeDNS connection",NotificationManager.IMPORTANCE_LOW));
    }
    @Override public int onStartCommand(Intent intent,int flags,int id){
        if(intent==null){stopSelf();return START_NOT_STICKY;}
        if(STOP.equals(intent.getAction())){shutdown();return START_NOT_STICKY;}
        if(launched) return START_NOT_STICKY; launched=true;
        startForeground(1,notification("Preparing"));
        new Thread(()->{
            try {
                File input=new File(getNoBackupFilesDir(),"pending-profile.json");
                try {profile=Profile.parse(new String(java.nio.file.Files.readAllBytes(input.toPath()),StandardCharsets.UTF_8));}
                finally {input.delete();}
                profile.validate(); startTunnel();
            }
            catch(Throwable e){fail(e);}
        },"nava-start").start(); return START_NOT_STICKY;
    }
    Notification notification(String text){
        Intent stop=new Intent(this,TunnelService.class).setAction(STOP);
        PendingIntent pi=PendingIntent.getService(this,2,stop,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        PendingIntent open=PendingIntent.getActivity(this,1,new Intent(this,MainActivity.class),PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this,"tunnel").setSmallIcon(dev.bridgedns.vpn.R.drawable.ic_bridgedns).setContentTitle("BridgeDNS")
            .setContentText(text).setContentIntent(open).setOngoing(true).addAction(new Notification.Action.Builder(null,"Disconnect",pi).build()).build();
    }
    void startTunnel() throws Exception {
        NativeBridge.ensureLoaded(); // Fail before changing routing if native libraries are absent.
        try(Socket occupied=new Socket()) {
            occupied.connect(new InetSocketAddress("127.0.0.1",18000),200);
            throw new IllegalStateException("Local port 18000 is in use by another application");
        } catch(java.net.ConnectException expected) { /* Port is available. */ }
        File dir=new File(getNoBackupFilesDir(),"runtime"); dir.mkdirs();
        File socket=new File(dir,"protect.sock"); socket.delete();
        if(profile.vpn) startProtector(socket.getAbsolutePath());
        File cacheDirectory=new File(getNoBackupFilesDir(),"resolver-history");cacheDirectory.mkdirs();
        File cache=new File(cacheDirectory,profile.resolverCacheId()+".json");
        write(new File(dir,"client.toml"),profile.toml(profile.vpn?socket.getAbsolutePath():"")+"NAVA_RESOLVER_CACHE_FILE = "+ConfigRules.tomlString(cache.getAbsolutePath())+"\n");
        write(new File(dir,"resolvers.txt"),ConfigRules.normalizeResolvers(profile.resolvers));
        File log=new File(dir,"core.log"); log.delete();
        status="Testing paths and negotiating with server"; publish();
        coreThread=new Thread(()->{
            try{
                String result=NativeBridge.runCore(new File(dir,"client.toml").getPath(),new File(dir,"resolvers.txt").getPath(),log.getPath());
                coreExited=true; if(!stopping.get()) fail(new IOException(result.isEmpty()?"The core stopped":result));
            }catch(Throwable e){coreExited=true;if(!stopping.get())fail(e);}
        },"masterdns"); coreThread.start();
        handler.post(tick);
        // A large user-supplied list takes longer to scan than three resolvers.
        int resolverCount=ConfigRules.normalizeResolvers(profile.resolvers).split("\n").length;
        long startupBudget=Math.max(120000L,((resolverCount+profile.scanConcurrency-1L)/profile.scanConcurrency)*20000L+30000L);
        long deadline=System.currentTimeMillis()+startupBudget;
        boolean ready=false;
        while(!stopping.get() && !coreExited && System.currentTimeMillis()<deadline){
            try(Socket s=new Socket()) { s.connect(new InetSocketAddress("127.0.0.1",18000),250); ready=true; break; }
            catch(IOException e){Thread.sleep(250);}
        }
        if(stopping.get()) return;
        if(!ready) throw new IOException("Proxy startup timed out. Check the domain, key, encryption and resolvers.");
        if(profile.vpn){
            Builder b=new Builder().setSession("BridgeDNS").setMtu(1280).addAddress("198.18.0.1",30)
                .addAddress("fd00:6e61:7661::1",126).addRoute("0.0.0.0",0).addRoute("::",0).addDnsServer("1.1.1.1");
            b.addDisallowedApplication(getPackageName());
            for(String pkg:profile.bypass.split("[,\\s]+")) if(!pkg.isEmpty() && !pkg.equals(getPackageName())) b.addDisallowedApplication(pkg);
            if(stopping.get()) return;
            tun=b.establish(); if(tun==null) throw new IOException("VPN permission was revoked");
            File yaml=new File(dir,"tun.yml"); write(yaml,"tunnel:\n  mtu: 1280\n  ipv4: 198.18.0.1\n  ipv6: 'fd00:6e61:7661::1'\nsocks5:\n  address: 127.0.0.1\n  port: 18000\n  udp: 'udp'\nmisc:\n  log-level: error\n");
            final int fd=tun.getFd();
            tunThread=new Thread(()->{int code=NativeBridge.runTun(yaml.getPath(),fd); if(!stopping.get()) fail(new IOException("TUN adapter stopped: "+code));},"tun2socks"); tunThread.start();
        }
        if(stopping.get()) return;
        active=true; started=SystemClock.elapsedRealtime(); status=profile.vpn?"VPN interface active • data not verified":"SOCKS listening • data not verified";
        getSystemService(NotificationManager.class).notify(1,notification(status)); publish();
    }
    void startProtector(String path) throws Exception {
        bound=new LocalSocket(); bound.bind(new LocalSocketAddress(path,LocalSocketAddress.Namespace.FILESYSTEM));
        protectServer=new LocalServerSocket(bound.getFileDescriptor());
        new Thread(()->{ while(!stopping.get()) {
            try(LocalSocket client=protectServer.accept()){
                client.setSoTimeout(5000); int token=client.getInputStream().read(); FileDescriptor[] fds=client.getAncillaryFileDescriptors();
                boolean ok=token==1 && fds!=null && fds.length==1;
                if(fds!=null) for(FileDescriptor fd:fds){try(ParcelFileDescriptor dup=ParcelFileDescriptor.dup(fd)){if(!protect(dup.getFd())) ok=false;} finally {new FileInputStream(fd).close();}}
                client.getOutputStream().write(ok?1:0);
            }catch(Exception e){if(!stopping.get()) {error="Socket protection failed"; publish();}}
        }},"socket-protector").start();
    }
    static void write(File file,String data) throws IOException { try(FileOutputStream out=new FileOutputStream(file)){out.write(data.getBytes(StandardCharsets.UTF_8));} }
    final Runnable tick=new Runnable(){public void run(){if(stopping.get())return; publish();handler.postDelayed(this,1000);}};
    void publish(){
        long[] metrics=new long[8];try{metrics=NativeBridge.metrics();}catch(Throwable ignored){}
        if(active&&metrics[0]>0&&metrics[1]>0&&status.contains("data not verified")){status=profile!=null&&profile.vpn?"VPN traffic active":"Proxy traffic active";getSystemService(NotificationManager.class).notify(1,notification(status));}
        Intent i=new Intent(STATE).setPackage(getPackageName()).putExtra("active",active).putExtra("busy",launched&&!stopping.get())
            .putExtra("status",status).putExtra("error",error).putExtra("core_log",readCoreLog()).putExtra("up",metrics[0]).putExtra("down",metrics[1])
            .putExtra("wire_up",metrics[2]).putExtra("wire_down",metrics[3]).putExtra("healthy",metrics[4]).putExtra("total",metrics[5]).putExtra("checked",metrics[6]).putExtra("scanning",!stopping.get()&&metrics[7]!=0)
            .putExtra("seconds",active?(SystemClock.elapsedRealtime()-started)/1000:0).putExtra("vpn",profile!=null&&profile.vpn);
        sendBroadcast(i);
    }
    synchronized void publishResolvers(){
        try{org.json.JSONObject snapshot=new org.json.JSONObject().put("rows",new org.json.JSONArray(NativeBridge.resolvers())).put("active",active).put("scanning",!stopping.get()&&NativeBridge.metrics()[7]!=0).put("captured_at",System.currentTimeMillis());
            File target=new File(getNoBackupFilesDir(),"resolver-snapshot.json"),temporary=new File(getNoBackupFilesDir(),"resolver-snapshot.tmp");write(temporary,snapshot.toString());java.nio.file.Files.move(temporary.toPath(),target.toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING,java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            sendBroadcast(new Intent(RESOLVERS_STATE).setPackage(getPackageName()));
        }catch(Exception ignored){}
    }
    long coreLogPosition=0;
    String partialLog="";
    synchronized String readCoreLog(){
        File file=new File(getNoBackupFilesDir(),"runtime/core.log");if(!file.isFile())return "";
        try(RandomAccessFile log=new RandomAccessFile(file,"rw")){
            if(log.length()>2097152){log.setLength(0);coreLogPosition=0;partialLog="";return "Core log rotated (2 MB).";}
            if(log.length()<coreLogPosition){coreLogPosition=0;partialLog="";}
            log.seek(coreLogPosition);byte[] buffer=new byte[(int)Math.min(16384,log.length()-coreLogPosition)];log.readFully(buffer);coreLogPosition=log.getFilePointer();
            String text=partialLog+new String(buffer,StandardCharsets.UTF_8);int end=text.lastIndexOf('\n');
            if(end<0){partialLog=text;return "";}partialLog=text.substring(end+1);
            text=text.substring(0,end).replaceAll("\\u001B\\[[;\\d]*m","").replaceAll("</?[A-Za-z_]+>","");
            if(profile!=null&&!profile.key.isEmpty())text=text.replace(profile.key,"[key redacted]");return text;
        }catch(IOException ignored){return "";}
    }
    void fail(Throwable e){error=safeError(e); status="Connection failed"; shutdown();}
    String safeError(Throwable e){String s=e.getMessage(); if(s==null)s=e.getClass().getSimpleName(); if(profile!=null&&!profile.key.isEmpty())s=s.replace(profile.key,"[key redacted]");return s.substring(0,Math.min(s.length(),700));}
    void shutdown(){
        if(!stopping.compareAndSet(false,true))return;
        active=false; if(error.isEmpty())status="Disconnected"; handler.removeCallbacks(tick); publish();publishResolvers();
        new Thread(()->{
            try{NativeBridge.stopCore(); if(tunThread!=null) NativeBridge.stopTun();}catch(Throwable ignored){}
            try{if(tun!=null)tun.close();}catch(Exception ignored){}
            try{if(protectServer!=null)protectServer.close();if(bound!=null)bound.close();}catch(Exception ignored){}
            try{if(coreThread!=null)coreThread.join(2000);if(tunThread!=null)tunThread.join(1000);}catch(InterruptedException ignored){}
            File dir=new File(getNoBackupFilesDir(),"runtime"); new File(dir,"client.toml").delete();new File(dir,"core.log").delete();new File(dir,"protect.sock").delete();
            handler.post(()->{stopForeground(STOP_FOREGROUND_REMOVE);stopSelf();android.os.Process.killProcess(android.os.Process.myPid());});
        },"nava-stop").start();
    }
    @Override public void onRevoke(){shutdown();super.onRevoke();}
    @Override public void onDestroy(){try{unregisterReceiver(control);}catch(Exception ignored){} shutdown();super.onDestroy();}
}
