package dev.bridgedns.vpn;

import android.annotation.SuppressLint;
import android.Manifest;
import android.app.*;
import android.content.*;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.net.VpnService;
import android.os.*;
import android.text.InputType;
import android.view.*;
import android.widget.*;
import org.json.*;
import java.util.*;
import java.util.concurrent.*;
import java.net.*;
import java.io.*;

public final class MainActivity extends Activity {
    static final int BG=0xff09121d,CARD=0xff121f2d,LINE=0xff263747,TEXT=0xffecf4f8,MUTED=0xff93a9bb,MINT=0xff53e5c2;
    Profile profile=new Profile(); JSONArray profiles=new JSONArray(); int selected=0;
    boolean active=false,busy=false; String status="Ready when you are";
    TextView state,up,down,time,modeText,profileText,presetText,eventsText;
    TextView upVolume,downVolume,resolverCounter,scanProgress,transportTotals;
    Orbit orbit; LinearLayout root; long lastUp,lastDown,lastSample;
    final ArrayDeque<String> events=new ArrayDeque<>();
    final ExecutorService worker=Executors.newSingleThreadExecutor();
    LogPane logScroll;
    CheckBox followLogs;
    final ExecutorService probeWorker=Executors.newSingleThreadExecutor();
    Button testButton;
    Future<?> dnsTask;
    int logGeneration=0;
    boolean testing=false;
    String lastError="";
    Button vpnChoice,proxyChoice,standardChoice,multiChoice;
    TextView routeSummary,pathLimit,scanSetting;
    Spinner profilePicker; boolean refreshingProfiles;
    @Override protected void attachBaseContext(Context base) {
        android.content.res.Configuration config=new android.content.res.Configuration(base.getResources().getConfiguration());
        config.setLocale(Locale.ENGLISH); config.setLayoutDirection(Locale.ENGLISH);
        super.attachBaseContext(base.createConfigurationContext(config));
    }
    final BroadcastReceiver receiver=new BroadcastReceiver(){public void onReceive(Context c,Intent i){
        active=i.getBooleanExtra("active",false);busy=i.getBooleanExtra("busy",false);
        String next=i.getStringExtra("status"); if(next!=null&&!next.equals(status)){status=next;event(next);}
        String error=i.getStringExtra("error"); if(error!=null&&!error.isEmpty()&&!error.equals(lastError)){lastError=error;event(error);}
        String core=i.getStringExtra("core_log");if(core!=null&&!core.isEmpty())for(String line:core.split("\n"))if(!line.isEmpty())event(line);
        state.setText(status);updatePowerState();
        long u=i.getLongExtra("up",0),d=i.getLongExtra("down",0),now=SystemClock.elapsedRealtime();
        double dt=lastSample==0?1:Math.max(0.1,(now-lastSample)/1000.0);
        up.setText(format(active&&lastSample!=0?Math.max(0,u-lastUp)/dt:0)+"/s");down.setText(format(active&&lastSample!=0?Math.max(0,d-lastDown)/dt:0)+"/s");
        upVolume.setText("TCP "+format(u));downVolume.setText("TCP "+format(d));
        transportTotals.setText("DNS tunnel traffic • includes overhead\nUpload ↑ "+format(i.getLongExtra("wire_up",0))+"    Download ↓ "+format(i.getLongExtra("wire_down",0)));
        long total=i.getLongExtra("total",0),healthy=i.getLongExtra("healthy",0),checked=i.getLongExtra("checked",0);
        resolverCounter.setText("Resolvers  "+healthy+" / "+total);
        scanProgress.setText("Healthy / total • checked "+checked+" / "+total+(i.getBooleanExtra("scanning",false)?" • scanning":""));
        lastSample=now;lastUp=u;lastDown=d;
        long s=i.getLongExtra("seconds",0);time.setText(String.format(Locale.US,"%02d:%02d:%02d",s/3600,s/60%60,s%60));
    }};
    @Override public void onCreate(Bundle saved){
        super.onCreate(saved);
        try{profiles=Vault.load(this);selected=getPreferences(0).getInt("selected",0);if(selected>=profiles.length())selected=0;if(profiles.length()>0)profile=Profile.parse(profiles.getString(selected));}
        catch(Exception e){event("Could not read saved profiles. Please check your settings.");}
        build();
        if(Build.VERSION.SDK_INT>=33)requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},8);
    }
    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    @Override public void onStart(){super.onStart();IntentFilter f=new IntentFilter(TunnelService.STATE);if(Build.VERSION.SDK_INT>=33)registerReceiver(receiver,f,Context.RECEIVER_NOT_EXPORTED);else registerReceiver(receiver,f);sendBroadcast(new Intent(TunnelService.QUERY).setPackage(getPackageName()));}
    @Override public void onStop(){super.onStop();unregisterReceiver(receiver);}
    @Override public void onDestroy(){worker.shutdownNow();probeWorker.shutdownNow();super.onDestroy();}
    void build(){
        ScrollView scroll=new ScrollView(this);scroll.setBackgroundColor(BG);scroll.setFillViewport(true);
        root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setPadding(dp(24),dp(16),dp(24),dp(28));root.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        scroll.addView(root);setContentView(scroll);
        scroll.setOnApplyWindowInsetsListener((v,insets)->{root.setPadding(dp(24),dp(16)+insets.getSystemWindowInsetTop(),dp(24),dp(24)+insets.getSystemWindowInsetBottom());return insets.consumeSystemWindowInsets();});
        LinearLayout header=row();TextView brand=text("BridgeDNS",29,TEXT);brand.setTypeface(null,Typeface.BOLD);brand.setLetterSpacing(.18f);
        TextView beta=text("0.1.0",11,MINT);beta.setPadding(dp(12),dp(7),dp(12),dp(7));beta.setBackground(bg(0xff163c37,30));header.addView(brand,new LinearLayout.LayoutParams(0,-2,1));header.addView(beta);root.addView(header);
        TextView sub=text("Find another way through.",14,MUTED);sub.setPadding(0,dp(9),0,dp(12));root.addView(sub);
        orbit=new Orbit();orbit.setOnClickListener(v->toggle());root.addView(orbit,new LinearLayout.LayoutParams(-1,dp(230)));
        state=text(status,19,TEXT);state.setGravity(Gravity.CENTER);state.setTypeface(null,Typeface.BOLD);root.addView(state);
        resolverCounter=text("Resolvers  — / —",21,MINT);resolverCounter.setGravity(Gravity.CENTER);resolverCounter.setPadding(0,dp(18),0,dp(4));root.addView(resolverCounter);
        scanProgress=text("Healthy / total",11,MUTED);scanProgress.setGravity(Gravity.CENTER);root.addView(scanProgress);
        LinearLayout stats=row();stats.setPadding(0,dp(18),0,dp(18));down=metric(stats,"Download ↓");up=metric(stats,"Upload ↑");time=metric(stats,"Connected for");time.setText("00:00:00");root.addView(stats);
        transportTotals=text("DNS tunnel traffic • includes overhead\nUpload ↑ 0 B    Download ↓ 0 B",12,MUTED);transportTotals.setGravity(Gravity.CENTER);transportTotals.setTextAlignment(View.TEXT_ALIGNMENT_CENTER);transportTotals.setPadding(0,0,0,dp(16));root.addView(transportTotals,new LinearLayout.LayoutParams(-1,-2));
        LinearLayout mode=card();mode.addView(text("Traffic mode",14,MUTED));
        modeText=text("",12,MUTED);
        vpnChoice=button("Whole device",CARD,TEXT);proxyChoice=button("Proxy",CARD,TEXT);
        mode.addView(segments(vpnChoice,proxyChoice));mode.addView(modeText);
        vpnChoice.setOnClickListener(v->{if(editable()){profile.vpn=true;persist();refresh();}});
        proxyChoice.setOnClickListener(v->{if(editable()){profile.vpn=false;persist();refresh();}});root.addView(mode);
        LinearLayout server=card();LinearLayout profileHeader=row();
        profileHeader.addView(text("Server profile",14,MUTED),new LinearLayout.LayoutParams(0,-2,1));
        IconButton edit=new IconButton(false),add=new IconButton(true);
        edit.setContentDescription("Edit selected profile");add.setContentDescription("Add profile");
        Button manage=button("⋮",BG,MINT);manage.setTextSize(24);manage.setPadding(0,0,0,0);manage.setMinWidth(0);manage.setMinimumWidth(0);manage.setContentDescription("Profile actions");
        profileHeader.addView(edit,new LinearLayout.LayoutParams(dp(48),dp(48)));profileHeader.addView(add,new LinearLayout.LayoutParams(dp(48),dp(48)));profileHeader.addView(manage,new LinearLayout.LayoutParams(dp(44),dp(48)));server.addView(profileHeader);
        manage.setOnClickListener(v->profileActions(manage));
        profilePicker=new Spinner(this);profilePicker.setBackground(outline(BG,12));profilePicker.setPopupBackgroundDrawable(bg(CARD,12));
        server.addView(profilePicker,new LinearLayout.LayoutParams(-1,dp(52)));
        profileText=text("",13,MUTED);profileText.setPadding(dp(4),dp(10),0,0);server.addView(profileText);root.addView(server);
        edit.setOnClickListener(v->{if(editable())editProfile();});add.setOnClickListener(v->{if(editable())editProfile(true);});
        profilePicker.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){
            public void onNothingSelected(AdapterView<?> parent){}
            public void onItemSelected(AdapterView<?> parent,View view,int position,long id){
                if(refreshingProfiles||position==selected||profiles.length()==0)return;
                if(!editable()){profilePicker.setSelection(selected);return;}
                try{profile=Profile.parse(profiles.getString(position));selected=position;getPreferences(0).edit().putInt("selected",selected).apply();refresh();}
                catch(Exception e){toast("Could not open profile");}
            }
        });
        LinearLayout routes=card();routes.addView(text("Resolver routing",14,MUTED));
        standardChoice=button("Standard",CARD,TEXT);multiChoice=button("Multipath",CARD,TEXT);routes.addView(segments(standardChoice,multiChoice));
        routeSummary=text("",12,MUTED);routeSummary.setPadding(0,dp(10),0,dp(6));routes.addView(routeSummary);
        pathLimit=text("",14,MINT);pathLimit.setMinHeight(dp(44));pathLimit.setGravity(Gravity.CENTER_VERTICAL);routes.addView(pathLimit);
        scanSetting=text("",14,MINT);scanSetting.setMinHeight(dp(48));scanSetting.setGravity(Gravity.CENTER_VERTICAL);routes.addView(scanSetting);scanSetting.setOnClickListener(v->{if(editable())editScanConcurrency();});
        Button dashboard=button("Resolver dashboard  ›",BG,MINT);routes.addView(dashboard,new LinearLayout.LayoutParams(-1,dp(48)));dashboard.setOnClickListener(v->openResolvers());
        standardChoice.setOnClickListener(v->{if(editable()){profile.multipath=false;persist();refresh();}});
        multiChoice.setOnClickListener(v->{if(editable()){profile.multipath=true;persist();refresh();}});
        pathLimit.setOnClickListener(v->{if(editable())editPathLimit();});root.addView(routes);
        LinearLayout tune=card();presetText=text("",16,TEXT);tune.addView(presetText);tune.addView(text("Balance request volume and loss tolerance",12,MUTED));
        tune.setOnClickListener(v->{if(editable())new AlertDialog.Builder(this).setTitle("Connection tuning").setSingleChoiceItems(new String[]{"Balanced • 2 copies","Efficient • 1 copy","Resilient • 3 copies"},profile.preset,(d,w)->{profile.preset=w;persist();refresh();d.dismiss();}).show();});root.addView(tune);
        LinearLayout actions=row();testButton=button("Test DNS",CARD,MINT);Button more=button("Tools",CARD,TEXT);actions.addView(testButton,new LinearLayout.LayoutParams(0,dp(48),1));actions.addView(more,new LinearLayout.LayoutParams(0,dp(48),1));root.addView(actions);testButton.setOnClickListener(v->{if(testing)cancelDnsTest();else testResolvers();});more.setOnClickListener(v->tools());
        TextView note=text("Whole device mode carries TCP and DNS. General UDP is not supported by the core. VPN status alone does not confirm internet access.",12,MUTED);note.setPadding(0,dp(20),0,dp(12));note.setLineSpacing(dp(4),1);root.addView(note);
        LinearLayout logs=card();LinearLayout logHeader=row();logHeader.addView(text("Connection log",14,TEXT),new LinearLayout.LayoutParams(0,-2,1));
        Button clear=button("Clear",CARD,MINT),copy=button("Copy",CARD,MUTED);
        logHeader.addView(clear,new LinearLayout.LayoutParams(dp(64),dp(40)));logHeader.addView(copy,new LinearLayout.LayoutParams(dp(64),dp(40)));logs.addView(logHeader);
        followLogs=new CheckBox(this);followLogs.setText("Follow new logs");followLogs.setTextColor(MUTED);followLogs.setTextSize(12);followLogs.setChecked(true);logs.addView(followLogs);
        logScroll=new LogPane();logScroll.setFillViewport(true);logScroll.setVerticalScrollBarEnabled(true);logScroll.setScrollbarFadingEnabled(false);
        eventsText=text("No events yet",12,MUTED);eventsText.setTextIsSelectable(false);eventsText.setLineSpacing(dp(3),1);eventsText.setTypeface(Typeface.MONOSPACE);logScroll.addView(eventsText);
        logs.addView(logScroll,new LinearLayout.LayoutParams(-1,dp(220)));root.addView(logs);
        followLogs.setOnCheckedChangeListener((v,checked)->{if(checked)logScroll.post(()->logScroll.fullScroll(View.FOCUS_DOWN));});
        clear.setOnClickListener(v->{cancelDnsTest();logGeneration++;events.clear();renderEvents();});copy.setOnClickListener(v->{copy(String.join("\n",events));toast("Log copied");});
        resolverCounter.setOnClickListener(v->openResolvers());
        refresh();renderEvents();
    }
    boolean editable(){if(active||busy){toast("Disconnect before changing settings");return false;}return true;}
    void refresh(){
        modeText.setText(profile.vpn?"TCP + DNS across your device":"SOCKS5 • 127.0.0.1:18000");
        segmentState(vpnChoice,profile.vpn,true);segmentState(proxyChoice,!profile.vpn,false);
        segmentState(standardChoice,!profile.multipath,true);segmentState(multiChoice,profile.multipath,false);
        routeSummary.setText(profile.multipath?"Split different packets across healthy paths, weighted by latency and loss.":"Original routing with the selected reliability preset.");
        pathLimit.setVisibility(profile.multipath?View.VISIBLE:View.GONE);
        pathLimit.setText("Maximum data paths   "+(profile.maxPaths==0?"All healthy":profile.maxPaths)+"   ›");
        profileText.setText(profile.domain.isEmpty()?"Configure your server to get started":profile.domain);
        scanSetting.setText("Scan concurrency   "+profile.scanConcurrency+"   ›");
        refreshingProfiles=true;
        String[] names=new String[Math.max(1,profiles.length())];
        for(int i=0;i<names.length;i++)try{names[i]=profiles.length()==0?"Add a server profile":Profile.parse(profiles.getString(i)).name;}catch(Exception e){names[i]="Profile";}
        ArrayAdapter<String> adapter=new ArrayAdapter<String>(this,android.R.layout.simple_spinner_item,names){
            public View getView(int pos,View convert,ViewGroup parent){TextView t=(TextView)super.getView(pos,convert,parent);t.setTextColor(TEXT);t.setTextSize(16);t.setPadding(dp(14),0,dp(14),0);t.setGravity(Gravity.CENTER_VERTICAL);t.setSingleLine(true);t.setEllipsize(android.text.TextUtils.TruncateAt.END);t.setCompoundDrawablePadding(dp(12));t.setCompoundDrawablesWithIntrinsicBounds(null,null,new Chevron(),null);return t;}
            public View getDropDownView(int pos,View convert,ViewGroup parent){TextView t=(TextView)super.getDropDownView(pos,convert,parent);t.setTextColor(pos==selected?MINT:TEXT);t.setBackgroundColor(CARD);t.setPadding(dp(16),dp(14),dp(16),dp(14));return t;}
        };
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);profilePicker.setAdapter(adapter);profilePicker.setSelection(Math.min(selected,names.length-1));refreshingProfiles=false;
        presetText.setText("Connection tuning  •  "+new String[]{"Balanced","Efficient","Resilient"}[Math.max(0,Math.min(2,profile.preset))]+(profile.multipath?" (Standard only)":""));
    }
    void editScanConcurrency(){
        EditText input=new EditText(this);input.setText(String.valueOf(profile.scanConcurrency));input.setInputType(InputType.TYPE_CLASS_NUMBER);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("Scan concurrency").setMessage("Parallel tests for connection startup, background discovery and Test DNS. Choose 1–64. Higher values can overload slow networks. Previously successful paths are tested first.").setView(input).setPositiveButton("Save",null).setNegativeButton("Cancel",null).create();
        dialog.setOnShowListener(d->dialog.getButton(-1).setOnClickListener(v->{try{int n=Integer.parseInt(input.getText().toString().trim());if(n<1||n>64)throw new Exception();profile.scanConcurrency=n;persist();refresh();dialog.dismiss();}catch(Exception e){input.setError("Enter a number from 1 to 64");}}));dialog.show();
    }
    void importProfile(String value){
        try{Profile imported=Profile.parse(value);imported.validate(false);
            if(imported.encryption!=0&&imported.key.isEmpty()){toast("Enter the server key to finish importing");editProfile(true,imported);}
            else{JSONArray updated=new JSONArray(profiles.toString());updated.put(imported.json());Vault.save(this,updated);profiles=updated;selected=updated.length()-1;profile=imported;getPreferences(0).edit().putInt("selected",selected).apply();refresh();toast("Profile imported");}
        }catch(Exception e){toast("Invalid profile: "+e.getMessage());}
    }
    void profileActions(View anchor){
        PopupMenu menu=new PopupMenu(this,anchor);menu.getMenu().add(0,1,0,"Export profile with key");menu.getMenu().add(0,2,1,"Export profile without key");menu.getMenu().add(0,3,2,"Delete profile");
        menu.setOnMenuItemClickListener(item->{if(item.getItemId()==1)exportProfile(true);else if(item.getItemId()==2)exportProfile(false);else deleteProfile();return true;});menu.show();
    }
    void exportProfile(boolean includeKey){
        try{String value=profile.exportJson(includeKey);File pending=new File(getNoBackupFilesDir(),"pending-profile-export.json");TunnelService.write(pending,value);
            Intent intent=new Intent(Intent.ACTION_CREATE_DOCUMENT).setType("application/json").addCategory(Intent.CATEGORY_OPENABLE);
            String name=profile.name.replaceAll("[^A-Za-z0-9_-]","_");if(name.isEmpty())name="profile";
            intent.putExtra(Intent.EXTRA_TITLE,"BridgeDNS-"+name+(includeKey?"-with-key":"-without-key")+".json");startActivityForResult(intent,20);
        }catch(Exception e){new File(getNoBackupFilesDir(),"pending-profile-export.json").delete();toast("Could not open profile export");}
    }
    void deleteProfile(){
        if(!editable())return;if(profiles.length()==0){toast("No saved profile to delete");return;}
        new AlertDialog.Builder(this).setTitle("Delete profile?").setMessage("Remove “"+profile.name+"” from this device?").setNegativeButton("Cancel",null).setPositiveButton("Delete",(dialog,which)->{
            try{JSONArray updated=new JSONArray(profiles.toString());updated.remove(selected);int next=Math.max(0,Math.min(selected,updated.length()-1));
                Profile replacement=updated.length()==0?new Profile():Profile.parse(updated.getString(next));
                Vault.save(this,updated);profiles=updated;selected=next;profile=replacement;getPreferences(0).edit().putInt("selected",selected).apply();refresh();toast("Profile deleted");
            }catch(Exception e){toast("Could not delete profile");}
        }).show();
    }
    void editPathLimit(){
        EditText input=new EditText(this);input.setText(String.valueOf(profile.maxPaths));input.setInputType(InputType.TYPE_CLASS_NUMBER);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("Maximum data paths").setMessage("Use up to this many healthy resolvers. Enter 0 for all healthy paths. More paths do not always mean more speed.").setView(input).setPositiveButton("Save",null).setNegativeButton("Cancel",null).create();
        dialog.setOnShowListener(d->dialog.getButton(-1).setOnClickListener(v->{try{int n=Integer.parseInt(input.getText().toString().trim());if(n<0)throw new Exception();profile.maxPaths=n;persist();refresh();dialog.dismiss();}catch(Exception e){input.setError("Enter zero or a positive whole number");}}));dialog.show();
    }
    void toggle(){
        if(active||busy){sendBroadcast(new Intent(TunnelService.STOP).setPackage(getPackageName()));return;}
        try{profile.validate();}catch(Exception e){toast(e.getMessage());editProfile();return;}
        if(profile.vpn){Intent request=VpnService.prepare(this);if(request!=null){startActivityForResult(request,10);return;}}
        startConnection();
    }
    void startConnection(){try{lastError="";lastSample=0;lastUp=0;lastDown=0;TunnelService.write(new File(getNoBackupFilesDir(),"pending-profile.json"),profile.json());Intent i=new Intent(this,TunnelService.class);startForegroundService(i);busy=true;updatePowerState();state.setText("Preparing…");}catch(Exception e){toast(e.getMessage());}}
    void updatePowerState(){orbit.running=active;orbit.working=busy&&!active;orbit.setContentDescription(active||busy?"Disconnect tunnel":"Connect tunnel");orbit.invalidate();}
    @Override protected void onActivityResult(int req,int result,Intent data){super.onActivityResult(req,result,data);
        if(req==21){if(result==RESULT_OK&&data!=null&&data.getData()!=null&&editable())try(InputStream input=getContentResolver().openInputStream(data.getData())){if(input==null)throw new IOException();ByteArrayOutputStream bytes=new ByteArrayOutputStream();byte[] buffer=new byte[4096];int n;while((n=input.read(buffer))!=-1)bytes.write(buffer,0,n);importProfile(bytes.toString("UTF-8"));}catch(Exception e){toast("Could not read profile file");}return;}
        if(req==20){File pending=new File(getNoBackupFilesDir(),"pending-profile-export.json");try{
            if(result==RESULT_OK&&data!=null&&data.getData()!=null){try(InputStream input=new FileInputStream(pending);OutputStream output=getContentResolver().openOutputStream(data.getData(),"wt")){if(output==null)throw new IOException();byte[] b=new byte[4096];int n;while((n=input.read(b))!=-1)output.write(b,0,n);}toast("Profile exported");}
        }catch(Exception e){toast("Could not export profile");}finally{pending.delete();}return;}
        if(req==10&&result==RESULT_OK)startConnection();else if(req==10)toast("VPN permission was not granted");}
    void persist(){try{profiles.put(selected,profile.json());Vault.save(this,profiles);getPreferences(0).edit().putInt("selected",selected).apply();}catch(Exception e){toast("Could not save encrypted settings");}}
    EditText field(LinearLayout box,String label,String value,boolean secret){box.addView(text(label,12,MUTED));EditText f=new EditText(this);f.setText(value);f.setTextColor(TEXT);f.setTextSize(15);f.setSingleLine(true);f.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);f.setInputType(secret?InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD:InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);box.addView(f);return f;}
    void editProfile(){editProfile(false);}
    void editProfile(boolean adding){editProfile(adding,adding?new Profile():profile);}
    void editProfile(boolean adding,Profile seed){
        final Profile draft;try{draft=Profile.parse(seed.json());}catch(Exception e){toast("Could not open profile");return;}
        LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);box.setPadding(dp(24),dp(8),dp(24),dp(8));box.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        EditText name=field(box,"Profile name",draft.name,false),domain=field(box,"Delegated tunnel domain",draft.domain,false),key=field(box,"Server key",draft.key,true);
        box.addView(text("Encryption • Must match the server",12,MUTED));Spinner method=new Spinner(this);method.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,new String[]{"0 • None","1 • XOR","2 • ChaCha20","3 • AES-128-GCM","4 • AES-192-GCM","5 • AES-256-GCM"}));method.setSelection(Math.max(0,Math.min(5,draft.encryption)));box.addView(method);
        EditText resolvers=field(box,"Resolvers • IP or IP:port, one per line (default port 53)",draft.resolvers,false);resolvers.setSingleLine(false);resolvers.setMinLines(3);resolvers.setMaxLines(6);resolvers.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_MULTI_LINE|InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        CheckBox compatible=new CheckBox(this);compatible.setText("Compatibility mode: Base64 replies, smaller packets");compatible.setTextColor(TEXT);compatible.setChecked(draft.compatibility);box.addView(compatible);
        box.addView(text("XOR is weak. Selecting AES does not fix the upstream return-path encryption limitation. Keep HTTPS or SSH for sensitive traffic.",11,MUTED));
        ScrollView scroll=new ScrollView(this);scroll.addView(box);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("Server settings").setView(scroll).setPositiveButton("Save",null).setNegativeButton("Cancel",null).create();
        dialog.setOnShowListener(d->dialog.getButton(-1).setOnClickListener(v->{try{
            Profile p=Profile.parse(draft.json());p.name=name.getText().toString().trim();p.domain=domain.getText().toString().trim();p.key=key.getText().toString();p.resolvers=resolvers.getText().toString().trim();p.encryption=method.getSelectedItemPosition();p.compatibility=compatible.isChecked();p.validate();if(adding)selected=profiles.length();profile=p;persist();refresh();dialog.dismiss();
        }catch(Exception e){toast(e.getMessage());}}));dialog.getWindow();dialog.show();
    }
    void selectProfile(){
        String[] names=new String[profiles.length()+1];for(int i=0;i<profiles.length();i++)try{names[i]=Profile.parse(profiles.getString(i)).name;}catch(Exception e){names[i]="Profile";}names[names.length-1]="＋ New profile";
        new AlertDialog.Builder(this).setTitle("Profiles").setItems(names,(d,w)->{try{if(w==profiles.length()){editProfile(true);}else{selected=w;profile=Profile.parse(profiles.getString(w));getPreferences(0).edit().putInt("selected",w).apply();refresh();}}catch(Exception e){toast("Invalid profile");}}).show();
    }
    void tools(){new AlertDialog.Builder(this).setTitle("BridgeDNS tools").setItems(new String[]{"Import JSON from clipboard","Copy settings without key","Apps excluded from VPN","Copy session log","About","Check native libraries","Test HTTPS through tunnel","Import profile from file"},(d,w)->{
        if(w==0&&editable())try{ClipboardManager cb=(ClipboardManager)getSystemService(CLIPBOARD_SERVICE);ClipData clip=cb.getPrimaryClip();if(clip==null)throw new Exception();importProfile(clip.getItemAt(0).coerceToText(this).toString());}catch(Exception e){toast("Could not read profile JSON from clipboard");}
        if(w==1)try{JSONObject j=new JSONObject(profile.json());j.put("key","");copy(j.toString(2));toast("Settings copied without the key");}catch(Exception ignored){}
        if(w==2&&editable()){EditText f=new EditText(this);f.setText(profile.bypass);f.setHint("com.example.app, com.example.other");new AlertDialog.Builder(this).setTitle("Excluded app package names").setMessage("These apps connect directly. Separate package names with commas. An uninstalled package will prevent VPN startup.").setView(f).setPositiveButton("Save",(a,b)->{String old=profile.bypass;try{profile.bypass=f.getText().toString().trim();profile.validate();persist();}catch(Exception e){profile.bypass=old;toast(e.getMessage());}}).setNegativeButton("Cancel",null).show();}
        if(w==3){copy(String.join("\n",events));toast("Session log copied with the key redacted");}
        if(w==4)new AlertDialog.Builder(this).setTitle("BridgeDNS 0.1.0").setMessage("Independent Android client for MasterDnsVPN\nCore: masterking32/MasterDnsVPN (MIT)\nTUN: heiher/hev-socks5-tunnel (MIT)\n\nNo ads or telemetry. Profiles are encrypted using Android Keystore.\n\nGeneral UDP is not supported. Real-device and server testing is required.").setPositiveButton("OK",null).show();
        if(w==5)try{event(NativeBridge.selfTest());toast("Native library check passed");}catch(Throwable e){event("Native library check failed: "+e.toString());}
        if(w==6)testDataPath();
        if(w==7&&editable())try{startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("application/json").addCategory(Intent.CATEGORY_OPENABLE),21);}catch(Exception e){toast("Could not open file picker");}
    }).show();}
    void testResolvers(){
        final String list;
        try{if(!ConfigRules.domain(profile.domain))throw new IllegalArgumentException("Enter a valid tunnel domain");list=ConfigRules.normalizeResolvers(profile.resolvers);}catch(Exception e){toast(e.getMessage());return;}
        final String domain=profile.domain;final int generation=++logGeneration;testing=true;testButton.setText("Stop test");event("DNS response test started. This does not verify the full tunnel.");
        final int parallel=profile.scanConcurrency;
        dnsTask=worker.submit(()->{
            ExecutorService pool=Executors.newFixedThreadPool(parallel);CompletionService<String> results=new ExecutorCompletionService<>(pool);
            try{int total=0;for(String r:list.split("\\r?\\n")){if(Thread.currentThread().isInterrupted())return;if(r.trim().isEmpty())continue;final String resolver=r.trim();results.submit(()->probeResolver(domain,resolver));total++;}
                for(int checked=0;checked<total;checked++){if(Thread.currentThread().isInterrupted())return;String result=results.take().get();final String line="["+(checked+1)+"/"+total+"] "+result;runOnUiThread(()->{if(generation==logGeneration)event(line);});}
            }catch(InterruptedException e){Thread.currentThread().interrupt();}catch(Exception e){runOnUiThread(()->{if(generation==logGeneration)event("DNS test stopped");});}
            finally{pool.shutdownNow();runOnUiThread(()->{if(generation==logGeneration){testing=false;testButton.setText("Test DNS");}});}
        });
    }
    String probeResolver(String domain,String resolver){
        try{String[] a=resolver.split(":");long start=SystemClock.elapsedRealtime();byte[] query=dnsQuestion(domain);
            try(DatagramSocket socket=new DatagramSocket()){socket.connect(InetAddress.getByName(a[0]),Integer.parseInt(a[1]));socket.setSoTimeout(2000);socket.send(new DatagramPacket(query,query.length));byte[] buf=new byte[4096];DatagramPacket answer=new DatagramPacket(buf,buf.length);socket.receive(answer);
                if(answer.getLength()<12||buf[0]!=query[0]||buf[1]!=query[1]||(buf[2]&0x80)==0)throw new IOException();return resolver+" • "+(SystemClock.elapsedRealtime()-start)+" ms • RCODE "+(buf[3]&15);}
        }catch(Exception e){return resolver+" • No valid response";}
    }
    void cancelDnsTest(){logGeneration++;if(dnsTask!=null)dnsTask.cancel(true);testing=false;if(testButton!=null)testButton.setText("Test DNS");}
    static byte[] dnsQuestion(String domain) throws Exception {ByteArrayOutputStream bytes=new ByteArrayOutputStream();DataOutputStream d=new DataOutputStream(bytes);d.writeShort(new java.security.SecureRandom().nextInt(65536));d.writeShort(0x100);d.writeShort(1);d.writeShort(0);d.writeShort(0);d.writeShort(0);for(String label:("bridgedns-"+Long.toHexString(System.nanoTime())+"."+domain).split("\\.")){byte[] b=label.getBytes(java.nio.charset.StandardCharsets.US_ASCII);d.writeByte(b.length);d.write(b);}d.writeByte(0);d.writeShort(16);d.writeShort(1);return bytes.toByteArray();}
    void copy(String s){((ClipboardManager)getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("BridgeDNS",s));}
    void event(String s){if(profile!=null&&!profile.key.isEmpty())s=s.replace(profile.key,"[key redacted]");if(events.size()>=300)events.removeFirst();events.addLast(new java.text.SimpleDateFormat("HH:mm:ss",Locale.US).format(new Date())+"  "+s);renderEvents();}
    void renderEvents(){if(eventsText!=null){eventsText.setText(events.isEmpty()?"No events yet":String.join("\n",events));if(followLogs!=null&&followLogs.isChecked())logScroll.post(()->logScroll.fullScroll(View.FOCUS_DOWN));}}
    void testDataPath(){
        if(!active){toast("Connect the tunnel first");return;}
        EditText host=new EditText(this);host.setSingleLine(true);host.setText("example.com");host.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        new AlertDialog.Builder(this).setTitle("Test data through SOCKS").setMessage("Connect to this HTTPS host through the local SOCKS tunnel, verify its TLS certificate and read an HTTP response. This checks the SOCKS data path, not Android TUN routing.")
            .setView(host).setNegativeButton("Cancel",null).setPositiveButton("Run test",(d,w)->{
                String target=host.getText().toString().trim();if(!ConfigRules.domain(target)){toast("Enter a hostname, without https:// or a path");return;}
                probeWorker.execute(()->{try{DataProbe.run(target,line->runOnUiThread(()->event(line)));}catch(Exception e){runOnUiThread(()->event(e.getMessage()));}});
            }).show();
    }
    void openResolvers(){startActivity(new Intent(this,ResolverActivity.class));}
    void toast(String s){Toast.makeText(this,s,Toast.LENGTH_LONG).show();}
    int dp(float n){return (int)(n*getResources().getDisplayMetrics().density+.5f);}
    TextView text(String s,int size,int color){TextView t=new TextView(this);t.setText(s);t.setTextSize(size);t.setTextColor(color);t.setPadding(0,dp(3),0,dp(3));return t;}
    LinearLayout row(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.HORIZONTAL);l.setGravity(Gravity.CENTER_VERTICAL);return l;}
    LinearLayout card(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.VERTICAL);l.setPadding(dp(18),dp(14),dp(18),dp(14));l.setBackground(bg(CARD,22));LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.bottomMargin=dp(12);l.setLayoutParams(p);return l;}
    GradientDrawable bg(int color,int radius){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));return d;}
    Button button(String s,int fill,int color){Button b=new Button(this);b.setText(s);b.setTextSize(15);b.setTextColor(color);b.setAllCaps(false);b.setBackground(bg(fill,18));b.setStateListAnimator(null);return b;}
    TextView metric(LinearLayout parent,String title){
        LinearLayout column=new LinearLayout(this);column.setOrientation(LinearLayout.VERTICAL);column.setGravity(Gravity.CENTER);column.setPadding(dp(2),0,dp(2),0);
        TextView value=text("—",17,TEXT);value.setTypeface(Typeface.MONOSPACE);value.setGravity(Gravity.CENTER);value.setSingleLine(true);value.setAutoSizeTextTypeUniformWithConfiguration(12,17,1,android.util.TypedValue.COMPLEX_UNIT_SP);column.addView(value,new LinearLayout.LayoutParams(-1,dp(32)));
        TextView label=text(title,11,MUTED);label.setGravity(Gravity.CENTER);label.setSingleLine(true);column.addView(label,new LinearLayout.LayoutParams(-1,dp(26)));
        TextView volume=text("TCP 0 B",12,MINT);volume.setGravity(Gravity.CENTER);volume.setSingleLine(true);volume.setAutoSizeTextTypeUniformWithConfiguration(10,12,1,android.util.TypedValue.COMPLEX_UNIT_SP);column.addView(volume,new LinearLayout.LayoutParams(-1,dp(25)));
        if(title.startsWith("Upload"))upVolume=volume;else if(title.startsWith("Download"))downVolume=volume;else volume.setVisibility(View.INVISIBLE);
        parent.addView(column,new LinearLayout.LayoutParams(0,-2,1));return value;
    }
    static String format(double n){if(n>=1073741824)return String.format(Locale.US,"%.1f GB",n/1073741824);if(n>=1048576)return String.format(Locale.US,"%.1f MB",n/1048576);if(n>=1024)return String.format(Locale.US,"%.1f KB",n/1024);return String.format(Locale.US,"%.0f B",n);}
    GradientDrawable outline(int color,int radius){GradientDrawable d=bg(color,radius);d.setStroke(dp(1),LINE);return d;}
    LinearLayout segments(Button left,Button right){
        LinearLayout group=row();group.setBackground(outline(BG,13));group.setPadding(dp(1),dp(1),dp(1),dp(1));
        for(Button button:new Button[]{left,right}){button.setMinWidth(0);button.setMinimumWidth(0);button.setPadding(dp(6),0,dp(6),0);button.setTextSize(14);group.addView(button,new LinearLayout.LayoutParams(0,dp(48),1));}
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.topMargin=dp(10);group.setLayoutParams(p);return group;
    }
    void segmentState(Button button,boolean checked,boolean left){
        GradientDrawable d=new GradientDrawable();d.setColor(checked?MINT:BG);float r=dp(12);d.setCornerRadii(left?new float[]{r,r,0,0,0,0,r,r}:new float[]{0,0,r,r,r,r,0,0});
        button.setBackground(d);button.setTextColor(checked?BG:MUTED);button.setSelected(checked);button.setContentDescription(button.getText()+(checked?", selected":", not selected"));
    }
    final class Chevron extends android.graphics.drawable.Drawable{
        final Paint paint=new Paint(3);
        public int getIntrinsicWidth(){return dp(28);}public int getIntrinsicHeight(){return dp(28);}
        public void draw(Canvas c){Rect b=getBounds();float x=b.exactCenterX(),y=b.exactCenterY();paint.setStyle(Paint.Style.FILL);paint.setColor(0xff163c37);c.drawRoundRect(b.left,b.top,b.right,b.bottom,dp(8),dp(8),paint);paint.setColor(MINT);paint.setStrokeWidth(dp(2));paint.setStrokeCap(Paint.Cap.ROUND);paint.setStrokeJoin(Paint.Join.ROUND);paint.setStyle(Paint.Style.STROKE);Path path=new Path();path.moveTo(x-dp(5),y-dp(2));path.lineTo(x,y+dp(3));path.lineTo(x+dp(5),y-dp(2));c.drawPath(path,paint);}
        public void setAlpha(int a){paint.setAlpha(a);}public void setColorFilter(ColorFilter f){paint.setColorFilter(f);}public int getOpacity(){return PixelFormat.TRANSLUCENT;}
    }
    final class IconButton extends View{
        final boolean plus;final Paint paint=new Paint(3);
        IconButton(boolean plus){super(MainActivity.this);this.plus=plus;setClickable(true);setFocusable(true);}
        public CharSequence getAccessibilityClassName(){return Button.class.getName();}
        protected void onDraw(Canvas c){super.onDraw(c);float x=getWidth()/2f,y=getHeight()/2f;paint.setStyle(Paint.Style.FILL);paint.setColor(isPressed()?LINE:BG);c.drawCircle(x,y,dp(17),paint);paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(dp(2));paint.setStrokeCap(Paint.Cap.ROUND);paint.setColor(MINT);
            if(plus){c.drawLine(x-dp(6),y,x+dp(6),y,paint);c.drawLine(x,y-dp(6),x,y+dp(6),paint);}else{c.save();c.rotate(40,x,y);c.drawRoundRect(x-dp(3),y-dp(8),x+dp(3),y+dp(5),dp(1),dp(1),paint);Path tip=new Path();tip.moveTo(x-dp(3),y+dp(5));tip.lineTo(x,y+dp(9));tip.lineTo(x+dp(3),y+dp(5));c.drawPath(tip,paint);c.drawLine(x-dp(3),y-dp(4),x+dp(3),y-dp(4),paint);c.restore();}}
        protected void drawableStateChanged(){super.drawableStateChanged();invalidate();}
    }
    final class LogPane extends ScrollView {
        LogPane(){super(MainActivity.this);}
        @Override public boolean dispatchTouchEvent(MotionEvent e){
            int action=e.getActionMasked();
            if(action==MotionEvent.ACTION_DOWN){getParent().requestDisallowInterceptTouchEvent(true);followLogs.setChecked(false);}
            boolean handled=super.dispatchTouchEvent(e);
            if(action==MotionEvent.ACTION_UP||action==MotionEvent.ACTION_CANCEL)getParent().requestDisallowInterceptTouchEvent(false);
            return handled;
        }
    }
    final class Orbit extends View {
        final Paint p=new Paint(3);boolean running,working;Orbit(){super(MainActivity.this);setContentDescription("Connect tunnel");setFocusable(true);setClickable(true);}
        @Override public CharSequence getAccessibilityClassName(){return Button.class.getName();}
        @Override protected void drawableStateChanged(){super.drawableStateChanged();invalidate();}
        @Override protected void onDraw(Canvas c){super.onDraw(c);float x=getWidth()/2f,y=getHeight()/2f,r=dp(76);p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(dp(1));p.setColor(LINE);c.drawCircle(x,y,r+dp(23),p);c.drawCircle(x,y,r+dp(9),p);
            p.setColor(running?0xff185a50:0xff1c3343);p.setStyle(Paint.Style.FILL);c.drawCircle(x,y,r,p);p.setShader(new RadialGradient(x,y,r,new int[]{running?0xff19483f:0xff153344,0xff0c1925},null,Shader.TileMode.CLAMP));c.drawCircle(x,y,r-dp(2),p);p.setShader(null);
            p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(dp(3));p.setStrokeCap(Paint.Cap.ROUND);p.setColor(MINT);
            float angle=working?(SystemClock.uptimeMillis()%3000)*.12f:-90;c.drawArc(x-r-dp(9),y-r-dp(9),x+r+dp(9),y+r+dp(9),angle,running?350:working?100:42,false,p);
            p.setStrokeWidth(dp(5));c.drawLine(x-dp(22),y+dp(22),x-dp(22),y-dp(22),p);c.drawLine(x-dp(22),y-dp(22),x+dp(22),y+dp(22),p);c.drawLine(x+dp(22),y+dp(22),x+dp(22),y-dp(22),p);
            p.setStyle(Paint.Style.FILL);
            p.setTextAlign(Paint.Align.CENTER);p.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));p.setTextSize(dp(11));p.setColor(isPressed()?TEXT:MINT);c.drawText(working?"TAP TO STOP":running?"DISCONNECT":"CONNECT",x,y+dp(51),p);
            if(working)postInvalidateDelayed(32);
        }
    }
}
