package dev.bridgedns.vpn;

import android.annotation.SuppressLint;
import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.*;
import android.content.res.ColorStateList;
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
    int BG=0xff071a22,CARD=0xff10323b,LINE=0xff1b4650,TEXT=0xffedf7f6,MUTED=0xffa5bcbd,MINT=0xff43dfc6;
    UiPalette palette;
    Profile profile=new Profile(); JSONArray profiles=new JSONArray(); int selected=0;
    boolean active=false,busy=false; String status="Disconnected";
    TextView state,statusSubtitle,up,down,time,modeText,profileText,presetText,eventsText;
    TextView upVolume,downVolume,upWireVolume,downWireVolume,resolverCounter,scanProgress,transportTotals,publicIpText,pingText,selectedAppsSummary,themeSummary;
    Orbit orbit; LinearLayout root,shell,bottomNav; FrameLayout contentHost; long lastUp,lastDown,lastSample;int navigationInsetBottom=0;
    String currentTab="connect"; Switch modeSwitch,routingModeSwitch; TextView modeLabel,routingModeLabel;
    final ArrayDeque<String> events=new ArrayDeque<>();
    final ExecutorService worker=Executors.newSingleThreadExecutor();
    LogPane logScroll;
    CheckBox followLogs;
    final ExecutorService probeWorker=Executors.newSingleThreadExecutor();
    final ExecutorService speedWorker=Executors.newSingleThreadExecutor();
    final ExecutorService ipWorker=Executors.newSingleThreadExecutor();
    final Handler mainHandler=new Handler(Looper.getMainLooper());
    final Runnable resolverPoll=new Runnable(){public void run(){if("connect".equals(currentTab)||active)sendBroadcast(new Intent(TunnelService.RESOLVERS_QUERY).setPackage(getPackageName()));mainHandler.postDelayed(this,5000);}};
    volatile TunnelSpeedTest activeSpeedTest;
    volatile boolean ipProbeRunning=false; Boolean lastIpViaTunnel=null;String lastPublicIp="—";
    Button testButton;
    Button speedTestButton,webCheckButton,allAppsChoice,selectedAppsChoice;
    EditText resolverEditor, diagnosticHost;
    TextView diagnosticResults;
    Button diagnosticStop, resolverSave;
    SeekBar pathSeek, scanSeek;
    Button pathMinus, pathPlus, scanMinus, scanPlus;
    volatile java.net.Socket diagnosticSocket;
    Future<?> diagnosticTask;
    int diagnosticGeneration=0;
    boolean diagnosticRunning=false;
    int resolverImportRequest=30,resolverExportRequest=31;
    Future<?> dnsTask;
    int logGeneration=0;
    boolean testing=false;
    String lastError="";
    Button vpnChoice,proxyChoice,standardChoice,multiChoice;
    TextView routeSummary,pathLimit,scanSetting;
    LinearLayout appRoutingCard;
    Spinner profilePicker; boolean refreshingProfiles;
    @Override protected void attachBaseContext(Context base) {
        android.content.res.Configuration config=new android.content.res.Configuration(base.getResources().getConfiguration());
        config.setLocale(Locale.ENGLISH); config.setLayoutDirection(Locale.ENGLISH);
        super.attachBaseContext(base.createConfigurationContext(config));
    }
    final BroadcastReceiver receiver=new BroadcastReceiver(){public void onReceive(Context c,Intent i){
        if(TunnelService.RESOLVERS_STATE.equals(i.getAction())){updatePing();return;}
        boolean wasActive=active;
        active=i.getBooleanExtra("active",false);busy=i.getBooleanExtra("busy",false);
        String next=i.getStringExtra("status"); if(next!=null&&!next.equals(status)){status=next;event(next);}
        String error=i.getStringExtra("error"); if(error!=null&&!error.isEmpty()&&!error.equals(lastError)){lastError=error;event(error);}
        String core=i.getStringExtra("core_log");if(core!=null&&!core.isEmpty())for(String line:core.split("\n"))if(!line.isEmpty())event(line);
        updatePowerState();updateModeSwitch();if("settings".equals(currentTab))refreshSettings();
        if(resolverEditor!=null)resolverEditor.setEnabled(!active&&!busy);if(resolverSave!=null)resolverSave.setEnabled(!active&&!busy);
        long u=i.getLongExtra("up",0),d=i.getLongExtra("down",0),now=SystemClock.elapsedRealtime();
        double dt=lastSample==0?1:Math.max(0.1,(now-lastSample)/1000.0);
        if(up!=null)up.setText(format(active&&lastSample!=0?Math.max(0,u-lastUp)/dt:0)+"/s");if(down!=null)down.setText(format(active&&lastSample!=0?Math.max(0,d-lastDown)/dt:0)+"/s");
        if(upVolume!=null)upVolume.setText("Total: "+format(u));if(downVolume!=null)downVolume.setText("Total: "+format(d));
        if(upWireVolume!=null)upWireVolume.setText("(incl. overhead): "+format(i.getLongExtra("wire_up",0)));
        if(downWireVolume!=null)downWireVolume.setText("(incl. overhead): "+format(i.getLongExtra("wire_down",0)));
        transportTotals.setText("DNS tunnel traffic • includes overhead\nUpload ↑ "+format(i.getLongExtra("wire_up",0))+"    Download ↓ "+format(i.getLongExtra("wire_down",0)));
        long total=i.getLongExtra("total",0),healthy=i.getLongExtra("healthy",0),checked=i.getLongExtra("checked",0);
        if(resolverCounter!=null)resolverCounter.setText(healthy+" / "+total+" (healthy)");
        scanProgress.setText("Healthy / total • checked "+checked+" / "+total+(i.getBooleanExtra("scanning",false)?" • scanning":""));
        lastSample=now;lastUp=u;lastDown=d;
        long s=i.getLongExtra("seconds",0);if(time!=null)time.setText(String.format(Locale.US,"%02d:%02d:%02d",s/3600,s/60%60,s%60));
        if(wasActive!=active||lastIpViaTunnel==null)probePublicIp(active);
    }};
    @Override public void onCreate(Bundle saved){
        UiPalette.apply(this);super.onCreate(saved);palette=UiPalette.from(this);applyWindowColors();
        try{profiles=Vault.load(this);selected=getPreferences(0).getInt("selected",0);if(selected>=profiles.length())selected=0;if(profiles.length()>0)profile=Profile.parse(profiles.getString(selected));}
        catch(Exception e){event("Could not read saved profiles. Please check your settings.");}
        ResolverStore.migrateIfNeeded(this,profiles);profile.resolvers=String.join("\n",ResolverStore.load(this));
        currentTab=getPreferences(0).getString("tab","connect");
        String savedLog=AppLog.read(this);if(!savedLog.isEmpty())for(String line:savedLog.split("\n")){if(events.size()==300)events.removeFirst();events.addLast(line);}
        build();
        if(Build.VERSION.SDK_INT>=33)requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},8);
    }
    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    @Override public void onStart(){super.onStart();IntentFilter f=new IntentFilter(TunnelService.STATE);f.addAction(TunnelService.RESOLVERS_STATE);if(Build.VERSION.SDK_INT>=33)registerReceiver(receiver,f,Context.RECEIVER_NOT_EXPORTED);else registerReceiver(receiver,f);sendBroadcast(new Intent(TunnelService.QUERY).setPackage(getPackageName()));sendBroadcast(new Intent(TunnelService.RESOLVERS_QUERY).setPackage(getPackageName()));mainHandler.post(resolverPoll);mainHandler.postDelayed(()->probePublicIp(active),1200);}
    @Override public void onStop(){mainHandler.removeCallbacksAndMessages(null);unregisterReceiver(receiver);super.onStop();}
    @Override public void onDestroy(){stopDiagnostic();worker.shutdownNow();probeWorker.shutdownNow();speedWorker.shutdownNow();ipWorker.shutdownNow();TunnelSpeedTest test=activeSpeedTest;if(test!=null)test.close();super.onDestroy();}
    void build(){
        if(currentTab==null||currentTab.isEmpty())currentTab="connect";
        buildModern();
        if(currentTab!=null)return;
        ScrollView scroll=new ScrollView(this);scroll.setBackgroundColor(BG);scroll.setFillViewport(true);
        root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setPadding(dp(24),dp(16),dp(24),dp(28));root.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        scroll.addView(root);setContentView(scroll);
        scroll.setOnApplyWindowInsetsListener((v,insets)->{root.setPadding(dp(24),dp(16)+insets.getSystemWindowInsetTop(),dp(24),dp(24)+insets.getSystemWindowInsetBottom());return insets.consumeSystemWindowInsets();});
        LinearLayout header=row();TextView brand=text("BridgeDNS",29,TEXT);brand.setTypeface(null,Typeface.BOLD);brand.setLetterSpacing(.18f);
        TextView beta=text(appVersion(),11,MINT);beta.setPadding(dp(12),dp(7),dp(12),dp(7));beta.setBackground(bg(0xff163c37,30));header.addView(brand,new LinearLayout.LayoutParams(0,-2,1));header.addView(beta);root.addView(header);
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
    void buildModern(){
        palette=UiPalette.from(this);BG=palette.background;CARD=palette.card;LINE=palette.line;TEXT=palette.text;MUTED=palette.muted;MINT=palette.accent;
        shell=new LinearLayout(this);shell.setOrientation(LinearLayout.VERTICAL);shell.setBackgroundColor(BG);shell.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        contentHost=new FrameLayout(this);shell.addView(contentHost,new LinearLayout.LayoutParams(-1,0,1));
        bottomNav=makeBottomNavigation();shell.addView(bottomNav,new LinearLayout.LayoutParams(-1,navigationHeight()));
        shell.setOnApplyWindowInsetsListener((v,insets)->{navigationInsetBottom=insets.getSystemWindowInsetBottom();if(bottomNav!=null){bottomNav.setPadding(dp(5),dp(5),dp(5),dp(5)+navigationInsetBottom);ViewGroup.LayoutParams params=bottomNav.getLayoutParams();params.height=navigationHeight();bottomNav.setLayoutParams(params);}return insets;});
        setContentView(shell);applyWindowColors();renderTab(currentTab);
    }
    void applyWindowColors(){
        boolean dark=UiPalette.isDark(this);getWindow().setStatusBarColor(BG);getWindow().setNavigationBarColor(BG);
        if(Build.VERSION.SDK_INT>=26)getWindow().getDecorView().setSystemUiVisibility(dark?0:View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR|View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
    }
    void renderTab(String tab){
        if(tab==null||!Arrays.asList("profile","settings","connect","tools","log").contains(tab))tab="connect";
        if(diagnosticRunning&&!"tools".equals(tab))stopDiagnostic();
        currentTab=tab;getPreferences(0).edit().putString("tab",tab).apply();
        if(shell!=null&&bottomNav!=null){shell.removeView(bottomNav);bottomNav=makeBottomNavigation();shell.addView(bottomNav,new LinearLayout.LayoutParams(-1,navigationHeight()));}
        state=statusSubtitle=up=down=time=modeText=profileText=presetText=eventsText=null;upVolume=downVolume=upWireVolume=downWireVolume=resolverCounter=publicIpText=pingText=selectedAppsSummary=themeSummary=null;
        orbit=null;profilePicker=null;vpnChoice=proxyChoice=standardChoice=multiChoice=allAppsChoice=selectedAppsChoice=null;routingModeSwitch=null;routingModeLabel=null;
        routeSummary=pathLimit=scanSetting=null;pathSeek=scanSeek=null;pathMinus=pathPlus=scanMinus=scanPlus=null;appRoutingCard=null;testButton=speedTestButton=webCheckButton=diagnosticStop=resolverSave=null;resolverEditor=diagnosticHost=null;diagnosticResults=null;logScroll=null;followLogs=null;
        scanProgress=new TextView(this);transportTotals=new TextView(this);
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);scroll.setClipToPadding(false);scroll.setBackgroundColor(BG);
        root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setPadding(dp(22),dp(10),dp(22),dp(22));root.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);scroll.addView(root);
        scroll.setOnApplyWindowInsetsListener((v,insets)->{root.setPadding(dp(22),dp(10)+insets.getSystemWindowInsetTop(),dp(22),dp(20));return insets.consumeSystemWindowInsets();});
        contentHost.removeAllViews();contentHost.addView(scroll,new FrameLayout.LayoutParams(-1,-1));
        addHeader();TextView tagline=text("Find another way through.",13,MUTED);tagline.setPadding(0,dp(3),0,dp(12));root.addView(tagline);
        switch(tab){case "profile":buildProfileTab();break;case "settings":buildSettingsTab();break;case "tools":buildToolsTab();break;case "log":buildLogTab();break;default:buildConnectTab();}
        updatePowerState();updateModeSwitch();renderEvents();updatePing();
    }
    void addHeader(){
        LinearLayout header=row();LinearLayout brand=row();TextView title=text("BridgeDNS",24,TEXT);title.setTypeface(Typeface.create("sans-serif",Typeface.BOLD));
        TextView version=text(appVersion(),10,MUTED);version.setPadding(dp(7),dp(7),0,0);brand.addView(title);brand.addView(version);header.addView(brand,new LinearLayout.LayoutParams(0,-2,1));
        LinearLayout mode=row();modeSwitch=new Switch(this);modeSwitch.setShowText(false);modeSwitch.setChecked(profile.vpn);modeSwitch.setScaleX(.88f);modeSwitch.setScaleY(.88f);
        styleModeSwitch(modeSwitch);
        modeLabel=text(profile.vpn?"VPN mode":"Proxy mode",10,MUTED);modeLabel.setSingleLine(true);modeLabel.setPadding(dp(4),0,0,0);mode.addView(modeSwitch);mode.addView(modeLabel);header.addView(mode);
        modeSwitch.setOnCheckedChangeListener((button,checked)->{if(!editable()){modeSwitch.setChecked(profile.vpn);return;}profile.vpn=checked;persist();renderTab(currentTab);});
        root.addView(header);
    }
    void styleModeSwitch(Switch control){int[][] states=new int[][]{new int[]{android.R.attr.state_checked},new int[]{}};control.setTrackTintList(new ColorStateList(states,new int[]{MINT,LINE}));control.setThumbTintList(new ColorStateList(states,new int[]{0xffffffff,MUTED}));}
    void updateModeSwitch(){if(modeSwitch!=null){modeSwitch.setChecked(profile.vpn);modeSwitch.setEnabled(!active&&!busy);}
        if(modeLabel!=null)modeLabel.setText(profile.vpn?"VPN mode":"Proxy mode");
        if(routingModeSwitch!=null){routingModeSwitch.setChecked(profile.multipath);routingModeSwitch.setEnabled(!active&&!busy);}
        if(routingModeLabel!=null)routingModeLabel.setText(profile.multipath?"Multipath":"Standard");}
    void renderTabFromChange(){renderTab(currentTab);}
    void buildConnectTab(){
        LinearLayout hero=card();hero.setBackground(heroBackground());hero.setPadding(dp(20),dp(18),dp(20),dp(18));
        LinearLayout top=row();top.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout statusBlock=new LinearLayout(this);statusBlock.setOrientation(LinearLayout.VERTICAL);
        state=text(displayStatus(),25,TEXT);state.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));state.setSingleLine(true);state.setAutoSizeTextTypeUniformWithConfiguration(20,25,1,android.util.TypedValue.COMPLEX_UNIT_SP);statusBlock.addView(state);
        statusSubtitle=text(connectionSubtitle(),12,MUTED);statusSubtitle.setMaxLines(3);statusSubtitle.setLineSpacing(dp(2),1);statusBlock.addView(statusSubtitle);
        top.addView(statusBlock,new LinearLayout.LayoutParams(0,-2,1));
        orbit=new Orbit();orbit.setOnClickListener(v->toggle());top.addView(orbit,new LinearLayout.LayoutParams(dp(138),dp(160)));hero.addView(top);
        LinearLayout first=row();time=valueColumn(first,"Duration","00:00:00");pingText=valueColumn(first,"Ping","—");
        LinearLayout.LayoutParams firstParams=new LinearLayout.LayoutParams(-1,-2);firstParams.topMargin=dp(8);hero.addView(first,firstParams);
        View divider=new View(this);divider.setBackgroundColor(LINE);LinearLayout.LayoutParams dividerParams=new LinearLayout.LayoutParams(-1,dp(1));dividerParams.topMargin=dp(8);dividerParams.bottomMargin=dp(8);hero.addView(divider,dividerParams);
        LinearLayout second=row();resolverCounter=valueColumn(second,"Resolvers","— / —");resolverCounter.setOnClickListener(v->openResolvers());publicIpText=valueColumn(second,"Public IP",lastPublicIp);publicIpText.setOnClickListener(v->probePublicIp(active));publicIpText.setOnLongClickListener(v->{if(!"Unavailable".equals(lastPublicIp)&&!"—".equals(lastPublicIp)){copy(lastPublicIp);toast("Public IP copied");}return true;});hero.addView(second);
        root.addView(hero);
        LinearLayout traffic=row();LinearLayout.LayoutParams downParams=new LinearLayout.LayoutParams(0,dp(132),1);downParams.rightMargin=dp(7);
        LinearLayout.LayoutParams upParams=new LinearLayout.LayoutParams(0,dp(132),1);upParams.leftMargin=dp(7);
        traffic.addView(trafficCard(false),downParams);traffic.addView(trafficCard(true),upParams);root.addView(traffic);
    }
    GradientDrawable heroBackground(){
        GradientDrawable background=new GradientDrawable(GradientDrawable.Orientation.TL_BR,new int[]{palette.surface,CARD});
        background.setCornerRadius(dp(24));background.setStroke(dp(1),LINE);return background;
    }
    TextView valueColumn(LinearLayout row,String label,String value){
        LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);box.setPadding(dp(3),dp(3),dp(3),dp(3));
        TextView title=text(label,11,MUTED);TextView v=text(value,16,TEXT);v.setTypeface(Typeface.create("sans-serif",Typeface.NORMAL));v.setFontFeatureSettings("tnum");v.setSingleLine(true);v.setEllipsize(android.text.TextUtils.TruncateAt.END);v.setAutoSizeTextTypeUniformWithConfiguration(10,16,1,android.util.TypedValue.COMPLEX_UNIT_SP);
        box.addView(title);box.addView(v,new LinearLayout.LayoutParams(-1,dp(28)));row.addView(box,new LinearLayout.LayoutParams(0,-2,1));return v;
    }
    View trafficCard(boolean upload){
        LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);box.setPadding(dp(14),dp(12),dp(12),dp(10));box.setBackground(outline(CARD,22));
        LinearLayout heading=row();TextView arrow=text(upload?"↑":"↓",22,MINT);arrow.setPadding(0,0,dp(8),0);heading.addView(arrow);
        TextView rate=text("0 B/s",18,TEXT);rate.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));rate.setFontFeatureSettings("tnum");rate.setSingleLine(true);rate.setAutoSizeTextTypeUniformWithConfiguration(13,18,1,android.util.TypedValue.COMPLEX_UNIT_SP);heading.addView(rate,new LinearLayout.LayoutParams(0,dp(30),1));box.addView(heading);
        View divider=new View(this);divider.setBackgroundColor(LINE);LinearLayout.LayoutParams lineParams=new LinearLayout.LayoutParams(-1,dp(1));lineParams.topMargin=dp(7);lineParams.bottomMargin=dp(6);box.addView(divider,lineParams);
        TextView total=text("Total: 0 B",11,TEXT);total.setSingleLine(true);total.setAutoSizeTextTypeUniformWithConfiguration(9,11,1,android.util.TypedValue.COMPLEX_UNIT_SP);box.addView(total,new LinearLayout.LayoutParams(-1,dp(25)));
        TextView wire=text("(incl. overhead): 0 B",10,MUTED);wire.setSingleLine(true);wire.setAutoSizeTextTypeUniformWithConfiguration(8,10,1,android.util.TypedValue.COMPLEX_UNIT_SP);box.addView(wire,new LinearLayout.LayoutParams(-1,dp(25)));
        if(upload){up=rate;upVolume=total;upWireVolume=wire;}else{down=rate;downVolume=total;downWireVolume=wire;}return box;
    }
    void buildProfileTab(){
        LinearLayout box=card();box.addView(text("Profiles",17,TEXT));
        profilePicker=new Spinner(this);profilePicker.setBackground(outline(BG,12));profilePicker.setPopupBackgroundDrawable(bg(CARD,12));box.addView(profilePicker,new LinearLayout.LayoutParams(-1,dp(50)));
        profileText=text("",12,MUTED);box.addView(profileText);LinearLayout actions=row();
        Button add=button("Add",palette.accentSoft,palette.accentStrong),edit=button("Edit",BG,MINT),manage=button("More",BG,MUTED);
        actions.addView(add,weightedButton());actions.addView(edit,weightedButton());actions.addView(manage,weightedButton());box.addView(actions);root.addView(box);
        add.setOnClickListener(v->{if(editable())editProfile(true);});edit.setOnClickListener(v->{if(editable())editProfile();});manage.setOnClickListener(v->profileActions(manage));
        profilePicker.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){public void onNothingSelected(AdapterView<?> p){}public void onItemSelected(AdapterView<?> p,View v,int position,long id){
            if(refreshingProfiles||position==selected||profiles.length()==0)return;if(!editable()){profilePicker.setSelection(selected);return;}
            try{profile=Profile.parse(profiles.getString(position));profile.resolvers=String.join("\n",ResolverStore.load(MainActivity.this));selected=position;getPreferences(0).edit().putInt("selected",selected).apply();renderTab("profile");}catch(Exception e){toast("Could not open profile");}
        }});refreshProfilePicker();
        LinearLayout actions2=card();actions2.addView(text("Profile files",15,TEXT));LinearLayout row=row();Button importFile=button("Import file",BG,MINT),importClipboard=button("Paste JSON",BG,MINT),export=button("Export",BG,MUTED);
        row.addView(importFile,weightedButton());row.addView(importClipboard,weightedButton());row.addView(export,weightedButton());actions2.addView(row);root.addView(actions2);
        importFile.setOnClickListener(v->openProfileImport());importClipboard.setOnClickListener(v->importProfileClipboard());export.setOnClickListener(v->profileActions(export));
        LinearLayout tune=card();presetText=text("",15,TEXT);tune.addView(presetText);tune.addView(text("Balance request volume and loss tolerance",11,MUTED));tune.setOnClickListener(v->{if(editable())new AlertDialog.Builder(this).setTitle("Connection tuning").setSingleChoiceItems(new String[]{"Balanced · 2 copies","Efficient · 1 copy","Resilient · 3 copies"},profile.preset,(d,w)->{profile.preset=w;persist();refresh();d.dismiss();}).show();});root.addView(tune);
        refreshProfilePicker();presetText.setText("Connection tuning · "+new String[]{"Balanced","Efficient","Resilient"}[Math.max(0,Math.min(2,profile.preset))]);
    }
    void refreshProfilePicker(){
        if(profilePicker==null)return;refreshingProfiles=true;String[] names=new String[Math.max(1,profiles.length())];
        for(int i=0;i<names.length;i++)try{names[i]=profiles.length()==0?"No saved profile · Add one":Profile.parse(profiles.getString(i)).name;}catch(Exception e){names[i]="Profile";}
        ArrayAdapter<String> adapter=new ArrayAdapter<String>(this,android.R.layout.simple_spinner_item,names){public View getView(int pos,View v,ViewGroup parent){TextView t=(TextView)super.getView(pos,v,parent);t.setTextColor(TEXT);t.setTextSize(15);return t;}public View getDropDownView(int pos,View v,ViewGroup parent){TextView t=(TextView)super.getDropDownView(pos,v,parent);t.setTextColor(TEXT);t.setBackgroundColor(CARD);return t;}};
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);profilePicker.setAdapter(adapter);profilePicker.setSelection(Math.min(selected,names.length-1));refreshingProfiles=false;
        if(profileText!=null)profileText.setText(profile.domain.isEmpty()?"Set the tunnel domain and server key in Edit profile.":profile.domain);
    }
    LinearLayout makeBottomNavigation(){
        LinearLayout nav=new LinearLayout(this);nav.setOrientation(LinearLayout.HORIZONTAL);nav.setGravity(Gravity.CENTER);nav.setPadding(dp(5),dp(5),dp(5),dp(5)+navigationInsetBottom);nav.setBackgroundColor(palette.surface);
        String[] tabs={"profile","settings","connect","tools","log"};String[] labels={"Profile","Settings","Connect","Tools","Log"};
        for(int i=0;i<tabs.length;i++){final String key=tabs[i];boolean selected=key.equals(currentTab);LinearLayout item=new LinearLayout(this);item.setGravity(Gravity.CENTER);item.setOrientation(LinearLayout.VERTICAL);item.setPadding(dp(2),dp(1),dp(2),dp(1));GradientDrawable d=bg(selected?palette.accentSoft:palette.surface,16);item.setBackground(d);
            NavGlyph glyph=new NavGlyph(key,selected?palette.accentStrong:palette.muted);item.addView(glyph,new LinearLayout.LayoutParams(dp(28),dp(27)));
            TextView label=text(labels[i],10,selected?palette.accentStrong:palette.muted);label.setGravity(Gravity.CENTER);label.setTypeface(Typeface.create(selected?"sans-serif-medium":"sans-serif",Typeface.NORMAL));item.addView(label);
            item.setOnClickListener(v->{if(!key.equals(currentTab)){currentTab=key;renderTab(key);}});nav.addView(item,new LinearLayout.LayoutParams(0,-1,1));}
        return nav;
    }
    int navigationHeight(){return dp(68)+navigationInsetBottom;}
    void openProfileImport(){try{startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("application/json").addCategory(Intent.CATEGORY_OPENABLE),21);}catch(Exception e){toast("Could not open file picker");}}
    String displayStatus(){if(!lastError.isEmpty()||status.toLowerCase(Locale.ROOT).contains("fail"))return "Failed";if(busy&&!active)return "Connecting";if(active)return "Connected";return "Disconnected";}
    void updatePing(){try{File file=new File(getNoBackupFilesDir(),"resolver-snapshot.json");if(!file.isFile())return;JSONObject snapshot=new JSONObject(new String(java.nio.file.Files.readAllBytes(file.toPath()),java.nio.charset.StandardCharsets.UTF_8));if(!snapshot.optBoolean("active",false)){if(pingText!=null)pingText.setText("—");return;}JSONArray rows=snapshot.optJSONArray("rows");if(rows==null)return;double total=0;int count=0;for(int i=0;i<rows.length();i++){JSONObject row=rows.optJSONObject(i);if(row!=null&&row.optBoolean("healthy")&&row.optDouble("rtt_ms",-1)>=0){total+=row.optDouble("rtt_ms");count++;}}if(pingText!=null)pingText.setText(count==0?"—":String.format(Locale.US,"%.0f ms",total/count));}catch(Exception ignored){}}
    void probePublicIp(boolean throughTunnel){if(ipProbeRunning)return;ipProbeRunning=true;lastIpViaTunnel=throughTunnel;if(publicIpText!=null)publicIpText.setText("Checking…");ipWorker.execute(()->{String result;try{result=PublicIpProbe.fetch(throughTunnel);}catch(Exception e){result="Unavailable";}final String value=result;runOnUiThread(()->{ipProbeRunning=false;lastPublicIp=value;if(publicIpText!=null)publicIpText.setText(value);if(lastIpViaTunnel!=null&&lastIpViaTunnel!=active)probePublicIp(active);});});}
    void applyProfileResolvers(){profile.resolvers=String.join("\n",ResolverStore.load(this));}
    void applyWindowColorsForTheme(){palette=UiPalette.from(this);BG=palette.background;CARD=palette.card;LINE=palette.line;TEXT=palette.text;MUTED=palette.muted;MINT=palette.accent;}
    void buildSettingsTab(){
        LinearLayout routes=card();routes.addView(text("Resolver routing",16,TEXT));LinearLayout routeMode=row();routingModeSwitch=new Switch(this);routingModeSwitch.setShowText(false);routingModeSwitch.setChecked(profile.multipath);routingModeSwitch.setScaleX(.88f);routingModeSwitch.setScaleY(.88f);styleModeSwitch(routingModeSwitch);routingModeLabel=text(profile.multipath?"Multipath":"Standard",12,MUTED);routeMode.addView(routingModeSwitch);routeMode.addView(routingModeLabel);routes.addView(routeMode);
        routeSummary=text("",11,MUTED);routes.addView(routeSummary);
        pathLimit=text("",12,MUTED);routes.addView(pathLimit);LinearLayout pathRow=row();
        pathMinus=button("−",BG,MINT);pathPlus=button("+",BG,MINT);pathSeek=new SeekBar(this);pathSeek.setMax(Math.max(64,profile.maxPaths));styleSeek(pathSeek);
        pathRow.addView(pathMinus,new LinearLayout.LayoutParams(dp(42),dp(44)));pathRow.addView(pathSeek,new LinearLayout.LayoutParams(0,dp(44),1));pathRow.addView(pathPlus,new LinearLayout.LayoutParams(dp(42),dp(44)));routes.addView(pathRow);
        scanSetting=text("",12,MUTED);LinearLayout.LayoutParams scanLabelParams=new LinearLayout.LayoutParams(-1,-2);scanLabelParams.topMargin=dp(12);routes.addView(scanSetting,scanLabelParams);
        LinearLayout scanRow=row();scanMinus=button("−",BG,MINT);scanPlus=button("+",BG,MINT);scanSeek=new SeekBar(this);scanSeek.setMax(63);styleSeek(scanSeek);
        scanRow.addView(scanMinus,new LinearLayout.LayoutParams(dp(42),dp(44)));scanRow.addView(scanSeek,new LinearLayout.LayoutParams(0,dp(44),1));scanRow.addView(scanPlus,new LinearLayout.LayoutParams(dp(42),dp(44)));routes.addView(scanRow);
        routingModeSwitch.setOnCheckedChangeListener((button,checked)->{if(!editable()){routingModeSwitch.setChecked(profile.multipath);return;}profile.multipath=checked;persist();renderTab("settings");});
        pathMinus.setOnClickListener(v->adjustPath(-1));pathPlus.setOnClickListener(v->adjustPath(1));scanMinus.setOnClickListener(v->adjustScan(-1));scanPlus.setOnClickListener(v->adjustScan(1));
        pathSeek.setOnSeekBarChangeListener(seekListener(true));scanSeek.setOnSeekBarChangeListener(seekListener(false));root.addView(routes);
        appRoutingCard=card();appRoutingCard.addView(text("Apps routed through VPN",16,TEXT));allAppsChoice=button("All apps",BG,MUTED);selectedAppsChoice=button("Selected apps",BG,MUTED);appRoutingCard.addView(segments(allAppsChoice,selectedAppsChoice));
        selectedAppsSummary=text("",12,MUTED);selectedAppsSummary.setPadding(0,dp(8),0,0);appRoutingCard.addView(selectedAppsSummary);
        allAppsChoice.setOnClickListener(v->{if(editable()){profile.routeAllApps=true;profile.bypass="";persist();renderTab("settings");}});
        selectedAppsChoice.setOnClickListener(v->{if(editable())chooseInstalledApps();});
        selectedAppsSummary.setOnClickListener(v->{if(editable())chooseInstalledApps();});root.addView(appRoutingCard);
        LinearLayout appearance=card();appearance.addView(text("Appearance",16,TEXT));themeSummary=text("",13,MUTED);appearance.addView(themeSummary);Button theme=button("Choose theme",BG,MINT);appearance.addView(theme,new LinearLayout.LayoutParams(-1,dp(44)));theme.setOnClickListener(v->chooseTheme());root.addView(appearance);
        refreshSettings();
    }
    void refreshSettings(){
        if(routingModeSwitch!=null){routingModeSwitch.setChecked(profile.multipath);routingModeSwitch.setEnabled(!active&&!busy);}if(routingModeLabel!=null)routingModeLabel.setText(profile.multipath?"Multipath":"Standard");
        if(routeSummary!=null)routeSummary.setText(profile.multipath?"Multipath spreads requests across healthy resolvers, weighted by measured latency and loss.":"Standard uses the selected reliability tuning with the normal resolver route.");
        if(pathLimit!=null)pathLimit.setText("Maximum data paths  ·  "+(profile.maxPaths==0?"All healthy":profile.maxPaths));
        if(pathSeek!=null){pathSeek.setProgress(profile.maxPaths);pathSeek.setEnabled(profile.multipath&&!active&&!busy);}
        if(pathMinus!=null){pathMinus.setEnabled(profile.multipath&&!active&&!busy&&profile.maxPaths>0);pathPlus.setEnabled(profile.multipath&&!active&&!busy&&profile.maxPaths<pathSeek.getMax());}
        if(scanSetting!=null)scanSetting.setText("Scan concurrency  ·  "+profile.scanConcurrency);
        if(scanSeek!=null){scanSeek.setProgress(profile.scanConcurrency-1);scanSeek.setEnabled(!active&&!busy);}
        if(scanMinus!=null){scanMinus.setEnabled(!active&&!busy&&profile.scanConcurrency>1);scanPlus.setEnabled(!active&&!busy&&profile.scanConcurrency<64);}
        if(allAppsChoice!=null)segmentState(allAppsChoice,profile.routeAllApps,true);if(selectedAppsChoice!=null)segmentState(selectedAppsChoice,!profile.routeAllApps,false);
        if(selectedAppsSummary!=null){int excluded=0;for(String pkg:profile.bypass.split("[,\\s]+"))if(!pkg.isEmpty())excluded++;selectedAppsSummary.setText(profile.routeAllApps?(excluded==0?"All apps will use the VPN tunnel.":"All apps except "+excluded+" saved exclusions. Tap All apps to clear them."):selectedAppNames());}
        if(appRoutingCard!=null)appRoutingCard.setVisibility(profile.vpn?View.VISIBLE:View.GONE);
        if(themeSummary!=null){String m=UiPalette.mode(this);themeSummary.setText("Theme  ·  "+("system".equals(m)?"System default":m.equals("dark")?"Dark":"Light"));}
    }
    void styleSeek(SeekBar seek){seek.setProgressTintList(ColorStateList.valueOf(MINT));seek.setThumbTintList(ColorStateList.valueOf(MINT));seek.setProgressBackgroundTintList(ColorStateList.valueOf(LINE));}
    SeekBar.OnSeekBarChangeListener seekListener(boolean paths){return new SeekBar.OnSeekBarChangeListener(){
        public void onStartTrackingTouch(SeekBar seek){}
        public void onProgressChanged(SeekBar seek,int value,boolean fromUser){
            if(!fromUser)return;
            if(paths)profile.maxPaths=value;else profile.scanConcurrency=value+1;
            if(paths&&pathLimit!=null)pathLimit.setText("Maximum data paths  ·  "+(value==0?"All healthy":value));
            if(!paths&&scanSetting!=null)scanSetting.setText("Scan concurrency  ·  "+(value+1));
        }
        public void onStopTrackingTouch(SeekBar seek){
            if(active||busy){refreshSettings();return;}
            if(paths)profile.maxPaths=seek.getProgress();else profile.scanConcurrency=seek.getProgress()+1;
            persist();refreshSettings();
        }
    };}
    void adjustPath(int delta){if(!editable()||!profile.multipath)return;profile.maxPaths=Math.max(0,Math.min(pathSeek.getMax(),profile.maxPaths+delta));persist();refreshSettings();}
    void adjustScan(int delta){if(!editable())return;profile.scanConcurrency=Math.max(1,Math.min(64,profile.scanConcurrency+delta));persist();refreshSettings();}
    String selectedAppNames(){
        ArrayList<String> names=new ArrayList<>();android.content.pm.PackageManager pm=getPackageManager();
        for(String pkg:profile.allowedApps.split("[,\\s]+"))if(!pkg.isEmpty())try{ApplicationInfo info=pm.getApplicationInfo(pkg,0);names.add(pm.getApplicationLabel(info).toString());}catch(Exception e){names.add(pkg);}
        return names.isEmpty()?"No apps selected yet. Tap here to choose installed apps.":String.join(", ",names);
    }
    static final class AppEntry{
        final String pkg,label;final ResolveInfo info;
        AppEntry(String pkg,String label,ResolveInfo info){this.pkg=pkg;this.label=label;this.info=info;}
    }
    void chooseInstalledApps(){
        PackageManager pm=getPackageManager();Intent launcher=new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        LinkedHashMap<String,AppEntry> unique=new LinkedHashMap<>();
        for(ResolveInfo info:pm.queryIntentActivities(launcher,0)){
            String pkg=info.activityInfo.packageName;
            if(!pkg.equals(getPackageName())&&!unique.containsKey(pkg))unique.put(pkg,new AppEntry(pkg,info.loadLabel(pm).toString(),info));
        }
        ArrayList<AppEntry> all=new ArrayList<>(unique.values());all.sort(Comparator.comparing((AppEntry item)->item.label,String.CASE_INSENSITIVE_ORDER));
        if(all.isEmpty()){toast("No launchable apps are visible to choose");return;}
        Set<String> selectedApps=new HashSet<>(Arrays.asList(profile.allowedApps.split("[,\\s]+")));selectedApps.retainAll(unique.keySet());
        LinearLayout panel=new LinearLayout(this);panel.setOrientation(LinearLayout.VERTICAL);panel.setPadding(dp(18),dp(8),dp(18),dp(8));
        TextView count=text("",12,MUTED);panel.addView(count);
        EditText search=new EditText(this);search.setSingleLine(true);search.setHint("Search installed apps");search.setTextColor(TEXT);search.setHintTextColor(MUTED);search.setTextSize(13);search.setBackground(outline(BG,12));search.setPadding(dp(12),0,dp(12),0);
        LinearLayout.LayoutParams searchParams=new LinearLayout.LayoutParams(-1,dp(44));searchParams.topMargin=dp(10);searchParams.bottomMargin=dp(10);panel.addView(search,searchParams);
        ArrayList<AppEntry> visible=new ArrayList<>(all);ListView list=new ListView(this);list.setDivider(new android.graphics.drawable.ColorDrawable(LINE));list.setDividerHeight(dp(1));list.setCacheColorHint(CARD);
        BaseAdapter adapter=new BaseAdapter(){
            public int getCount(){return visible.size();}
            public Object getItem(int index){return visible.get(index);}
            public long getItemId(int index){return index;}
            public View getView(int index,View recycled,ViewGroup parent){
                AppEntry entry=visible.get(index);LinearLayout row=row();row.setPadding(dp(5),dp(5),dp(5),dp(5));
                ImageView icon=new ImageView(MainActivity.this);icon.setImageDrawable(entry.info.loadIcon(pm));row.addView(icon,new LinearLayout.LayoutParams(dp(32),dp(32)));
                LinearLayout labels=new LinearLayout(MainActivity.this);labels.setOrientation(LinearLayout.VERTICAL);labels.setPadding(dp(10),0,dp(4),0);
                TextView title=text(entry.label,13,TEXT);title.setSingleLine(true);title.setEllipsize(android.text.TextUtils.TruncateAt.END);labels.addView(title);
                TextView packageName=text(entry.pkg,10,MUTED);packageName.setSingleLine(true);packageName.setEllipsize(android.text.TextUtils.TruncateAt.END);labels.addView(packageName);
                row.addView(labels,new LinearLayout.LayoutParams(0,-2,1));
                CheckBox check=new CheckBox(MainActivity.this);check.setButtonTintList(ColorStateList.valueOf(MINT));check.setChecked(selectedApps.contains(entry.pkg));row.addView(check);
                check.setOnClickListener(v->{if(check.isChecked())selectedApps.add(entry.pkg);else selectedApps.remove(entry.pkg);count.setText(selectedApps.size()+" apps selected");});
                row.setOnClickListener(v->check.performClick());return row;
            }
        };
        list.setAdapter(adapter);panel.addView(list,new LinearLayout.LayoutParams(-1,dp(380)));
        search.addTextChangedListener(new android.text.TextWatcher(){
            public void beforeTextChanged(CharSequence s,int start,int count,int after){}
            public void onTextChanged(CharSequence s,int start,int before,int count){String query=s.toString().trim().toLowerCase(Locale.ROOT);visible.clear();for(AppEntry entry:all)if(entry.label.toLowerCase(Locale.ROOT).contains(query)||entry.pkg.toLowerCase(Locale.ROOT).contains(query))visible.add(entry);adapter.notifyDataSetChanged();}
            public void afterTextChanged(android.text.Editable value){}
        });
        count.setText(selectedApps.size()+" apps selected");
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("Apps routed through VPN").setView(panel).setPositiveButton("Save",null).setNegativeButton("Cancel",null).create();
        dialog.setOnShowListener(ignored->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
            if(selectedApps.isEmpty()){toast("Select at least one app");return;}
            ArrayList<String> chosen=new ArrayList<>();for(AppEntry entry:all)if(selectedApps.contains(entry.pkg))chosen.add(entry.pkg);
            profile.allowedApps=String.join(",",chosen);profile.routeAllApps=false;persist();dialog.dismiss();renderTab("settings");
        }));
        dialog.show();
    }
    void chooseTheme(){
        String[] labels={"System default","Light","Dark"};String mode=UiPalette.mode(this);int selected="light".equals(mode)?1:"dark".equals(mode)?2:0;
        new AlertDialog.Builder(this).setTitle("Appearance").setSingleChoiceItems(labels,selected,(dialog,which)->{UiPalette.setMode(this,new String[]{"system","light","dark"}[which]);dialog.dismiss();recreate();}).setNegativeButton("Cancel",null).show();
    }
    void buildToolsTab(){
        LinearLayout diagnostics=card();diagnostics.addView(text("Tunnel speed & web check",16,TEXT));
        TextView description=text("Tests use the active SOCKS tunnel. Web check identifies CONNECT, TLS or HTTP failures; it does not test device VPN routing or UDP/QUIC.",11,MUTED);description.setLineSpacing(dp(2),1);diagnostics.addView(description);
        LinearLayout controls=row();speedTestButton=button("Speed test",palette.accentSoft,palette.accentStrong);webCheckButton=button("Web check",BG,MINT);diagnosticStop=button("Stop",BG,MUTED);
        controls.addView(speedTestButton,weightedButton());controls.addView(webCheckButton,weightedButton());controls.addView(diagnosticStop,weightedButton());
        LinearLayout.LayoutParams controlsParams=new LinearLayout.LayoutParams(-1,-2);controlsParams.topMargin=dp(12);diagnostics.addView(controls,controlsParams);
        diagnosticHost=new EditText(this);diagnosticHost.setSingleLine(true);diagnosticHost.setText("google.com");diagnosticHost.setHint("HTTPS hostname");diagnosticHost.setTextColor(TEXT);diagnosticHost.setHintTextColor(MUTED);diagnosticHost.setTextSize(13);
        diagnosticHost.setBackground(outline(BG,12));diagnosticHost.setPadding(dp(12),0,dp(12),0);
        LinearLayout.LayoutParams hostParams=new LinearLayout.LayoutParams(-1,dp(42));hostParams.topMargin=dp(10);diagnostics.addView(diagnosticHost,hostParams);
        ScrollView output=new ScrollView(this);output.setBackground(outline(BG,12));output.setPadding(dp(10),dp(6),dp(10),dp(6));
        diagnosticResults=text("Connect to run a tunnel speed or HTTPS check.",11,MUTED);diagnosticResults.setTextIsSelectable(true);diagnosticResults.setLineSpacing(dp(2),1);output.addView(diagnosticResults);
        LinearLayout.LayoutParams outputParams=new LinearLayout.LayoutParams(-1,dp(98));outputParams.topMargin=dp(10);diagnostics.addView(output,outputParams);
        speedTestButton.setOnClickListener(v->startDiagnostic(true));webCheckButton.setOnClickListener(v->startDiagnostic(false));diagnosticStop.setOnClickListener(v->stopDiagnostic());diagnosticStop.setEnabled(false);root.addView(diagnostics);

        LinearLayout manage=card();manage.addView(text("Resolver list",16,TEXT));manage.addView(text("One resolver per line. Shared by all profiles.",11,MUTED));
        resolverEditor=new EditText(this);resolverEditor.setText(String.join("\n",ResolverStore.load(this)));resolverEditor.setTextSize(13);resolverEditor.setTypeface(Typeface.MONOSPACE);resolverEditor.setTextColor(TEXT);resolverEditor.setHintTextColor(MUTED);
        resolverEditor.setHint("1.1.1.1:53\n8.8.8.8:53");resolverEditor.setGravity(Gravity.TOP|Gravity.START);resolverEditor.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_MULTI_LINE|InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        resolverEditor.setVerticalScrollBarEnabled(true);resolverEditor.setBackground(outline(BG,12));resolverEditor.setPadding(dp(12),dp(10),dp(12),dp(10));resolverEditor.setEnabled(!active&&!busy);
        resolverEditor.setOnTouchListener((view,event)->{int action=event.getActionMasked();if(action==MotionEvent.ACTION_DOWN)view.getParent().requestDisallowInterceptTouchEvent(true);if(action==MotionEvent.ACTION_UP||action==MotionEvent.ACTION_CANCEL)view.getParent().requestDisallowInterceptTouchEvent(false);return false;});
        LinearLayout.LayoutParams editorParams=new LinearLayout.LayoutParams(-1,dp(176));editorParams.topMargin=dp(12);editorParams.bottomMargin=dp(12);manage.addView(resolverEditor,editorParams);
        LinearLayout actions=row();resolverSave=button("Save",palette.accentSoft,palette.accentStrong);Button importFile=button("Import file",BG,MINT),exportFile=button("Export",BG,MUTED);
        actions.addView(resolverSave,weightedButton());actions.addView(importFile,weightedButton());actions.addView(exportFile,weightedButton());manage.addView(actions);
        resolverSave.setEnabled(!active&&!busy);resolverSave.setOnClickListener(v->{if(!editable())return;try{saveResolverList(ResolverStore.parse(resolverEditor.getText().toString()));}catch(Exception e){resolverEditor.setError(e.getMessage());}});
        importFile.setOnClickListener(v->openResolverImport());exportFile.setOnClickListener(v->exportResolvers());root.addView(manage);
        Button about=button("About BridgeDNS",BG,MUTED);LinearLayout.LayoutParams aboutParams=new LinearLayout.LayoutParams(-1,dp(42));aboutParams.topMargin=dp(4);root.addView(about,aboutParams);about.setOnClickListener(v->showAbout());
    }
    LinearLayout.LayoutParams weightedButton(){LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(0,dp(42),1);params.leftMargin=dp(3);params.rightMargin=dp(3);return params;}
    void saveResolverList(List<String> values){try{ResolverStore.save(this,values);applyProfileResolvers();event("Resolver list updated ("+ResolverStore.load(this).size()+")");renderTab("tools");toast("Resolvers saved");}catch(Exception e){toast(e.getMessage());}}
    void openResolverImport(){if(!editable())return;try{startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE),resolverImportRequest);}catch(Exception e){toast("Could not open resolver file picker");}}
    void exportResolvers(){try{startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).setType("text/plain").addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_TITLE,"BridgeDNS-resolvers.txt"),resolverExportRequest);}catch(Exception e){toast("Could not open resolver export");}}
    void startDiagnostic(boolean speed){
        if(diagnosticRunning)return;if(!active){diagnosticResults.setText("Connect BridgeDNS first.");return;}
        String host=diagnosticHost.getText().toString().trim();if(!speed&&!ConfigRules.domain(host)){diagnosticHost.setError("Enter a hostname without https://");return;}
        final int generation=++diagnosticGeneration;diagnosticRunning=true;diagnosticResults.setText("");updateDiagnosticButtons();
        diagnosticTask=speedWorker.submit(()->{
            try{
                if(speed){TunnelSpeedTest test=new TunnelSpeedTest();activeSpeedTest=test;try{test.run(line->appendDiagnostic(line,generation));}finally{test.close();activeSpeedTest=null;}}
                else DataProbe.run(host,line->appendDiagnostic(line,generation),socket->diagnosticSocket=socket);
            }catch(Exception e){appendDiagnostic(e.getMessage()==null?e.getClass().getSimpleName():e.getMessage(),generation);}
            finally{diagnosticSocket=null;runOnUiThread(()->{if(generation==diagnosticGeneration){diagnosticRunning=false;updateDiagnosticButtons();}});}
        });
    }
    void appendDiagnostic(String line,int generation){runOnUiThread(()->{if(generation!=diagnosticGeneration||diagnosticResults==null)return;String current=diagnosticResults.getText().toString();diagnosticResults.setText(current.isEmpty()?line:current+"\n"+line);});}
    void updateDiagnosticButtons(){if(speedTestButton!=null)speedTestButton.setEnabled(!diagnosticRunning);if(webCheckButton!=null)webCheckButton.setEnabled(!diagnosticRunning);if(diagnosticStop!=null)diagnosticStop.setEnabled(diagnosticRunning);}
    void stopDiagnostic(){diagnosticGeneration++;diagnosticRunning=false;TunnelSpeedTest test=activeSpeedTest;if(test!=null){test.close();activeSpeedTest=null;}Socket socket=diagnosticSocket;if(socket!=null)try{socket.close();}catch(IOException ignored){}diagnosticSocket=null;if(diagnosticTask!=null)diagnosticTask.cancel(true);updateDiagnosticButtons();}
    void buildLogTab(){
        LinearLayout panel=card();LinearLayout heading=row();heading.addView(text("Connection log",16,TEXT),new LinearLayout.LayoutParams(0,-2,1));Button clear=button("Clear",BG,MINT),copyButton=button("Copy",BG,MUTED);LinearLayout.LayoutParams clearParams=new LinearLayout.LayoutParams(dp(58),dp(40));clearParams.rightMargin=dp(6);heading.addView(clear,clearParams);heading.addView(copyButton,new LinearLayout.LayoutParams(dp(58),dp(40)));panel.addView(heading);
        followLogs=new CheckBox(this);followLogs.setText("Follow new logs");followLogs.setTextColor(MUTED);followLogs.setTextSize(12);followLogs.setButtonTintList(new ColorStateList(new int[][]{new int[]{android.R.attr.state_checked},new int[]{}},new int[]{MINT,MUTED}));followLogs.setChecked(true);panel.addView(followLogs);
        logScroll=new LogPane();logScroll.setFillViewport(true);logScroll.setVerticalScrollBarEnabled(true);eventsText=text("No events yet",11,MUTED);eventsText.setTypeface(Typeface.MONOSPACE);eventsText.setTextIsSelectable(true);logScroll.addView(eventsText);panel.addView(logScroll,new LinearLayout.LayoutParams(-1,dp(420)));root.addView(panel);
        clear.setOnClickListener(v->{cancelDnsTest();cancelSpeedTest();events.clear();AppLog.clear(this);renderEvents();});copyButton.setOnClickListener(v->{copy(String.join("\n",events));toast("Log copied");});followLogs.setOnCheckedChangeListener((v,checked)->{if(checked&&logScroll!=null)logScroll.post(()->logScroll.fullScroll(View.FOCUS_DOWN));});
    }
    void startSpeedTest(){if(!active){toast("Connect BridgeDNS first");return;}if(activeSpeedTest!=null)return;TunnelSpeedTest test=new TunnelSpeedTest();activeSpeedTest=test;if(speedTestButton!=null)speedTestButton.setText("Stop speed test");
        speedWorker.execute(()->{try{test.run(line->runOnUiThread(()->event(line)));}catch(Exception e){runOnUiThread(()->event(e.getMessage()==null?"Speed test failed":e.getMessage()));}finally{test.close();if(activeSpeedTest==test)activeSpeedTest=null;runOnUiThread(()->{if(speedTestButton!=null)speedTestButton.setText("Speed test");});}});
    }
    void cancelSpeedTest(){TunnelSpeedTest test=activeSpeedTest;if(test!=null){test.close();activeSpeedTest=null;}if(speedTestButton!=null)speedTestButton.setText("Speed test");}
    void openProfileClipboard(){importProfileClipboard();}
    void importProfileClipboard(){if(!editable())return;try{ClipboardManager clipboard=(ClipboardManager)getSystemService(CLIPBOARD_SERVICE);ClipData clip=clipboard.getPrimaryClip();if(clip==null)throw new IOException("Clipboard is empty");importProfile(clip.getItemAt(0).coerceToText(this).toString());}catch(Exception e){toast("Could not read profile JSON from clipboard");}}
    String appVersion(){try{android.content.pm.PackageInfo info=getPackageManager().getPackageInfo(getPackageName(),0);return info.versionName==null?"0.2.2":info.versionName;}catch(Exception ignored){return "0.2.2";}}
    void showAbout(){new AlertDialog.Builder(this).setTitle("BridgeDNS "+appVersion()).setMessage("Independent Android client for MasterDnsVPN\nProfiles are encrypted with Android Keystore.\nPublic IP checks use api.ipify.org over HTTPS.\nGeneral UDP support depends on the tunnel core.").setPositiveButton("OK",null).show();}
    boolean editable(){if(active||busy){toast("Disconnect before changing settings");return false;}return true;}
    void refresh(){
        if(currentTab!=null){renderTab(currentTab);return;}
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
        if(currentTab!=null){importProfileModern(value);return;}
        try{Profile imported=Profile.parse(value);imported.validate(false);
            if(imported.encryption!=0&&imported.key.isEmpty()){toast("Enter the server key to finish importing");editProfile(true,imported);}
            else{JSONArray updated=new JSONArray(profiles.toString());updated.put(imported.json());Vault.save(this,updated);profiles=updated;selected=updated.length()-1;profile=imported;getPreferences(0).edit().putInt("selected",selected).apply();refresh();toast("Profile imported");}
        }catch(Exception e){toast("Invalid profile: "+e.getMessage());}
    }
    void importProfileModern(String value){
        try{
            JSONObject source=new JSONObject(value);
            ArrayList<String> mergedResolvers=new ArrayList<>(ResolverStore.load(this));
            if(source.has("resolvers"))mergedResolvers.addAll(ResolverStore.parse(source.get("resolvers").toString()));
            Profile imported=Profile.parse(value);imported.resolvers=String.join("\n",mergedResolvers);imported.validate(false);
            if(source.has("resolvers"))ResolverStore.save(this,mergedResolvers);
            if(imported.encryption!=0&&imported.key.isEmpty()){toast("Enter the server key to finish importing");editProfile(true,imported);return;}
            JSONArray updated=new JSONArray(profiles.toString());updated.put(imported.json());Vault.save(this,updated);profiles=updated;selected=updated.length()-1;profile=imported;profile.resolvers=String.join("\n",ResolverStore.load(this));getPreferences(0).edit().putInt("selected",selected).apply();renderTab("profile");toast("Profile imported");
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
        new AlertDialog.Builder(this).setTitle("Delete profile?").setMessage("Remove \""+profile.name+"\" from this device?").setNegativeButton("Cancel",null).setPositiveButton("Delete",(dialog,which)->{
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
    String connectionSubtitle(){
        if(!lastError.isEmpty()||status.toLowerCase(Locale.ROOT).contains("fail"))return "Connection failed · see Log for details";
        if(busy&&!active)return status==null||status.isEmpty()?"Starting secure connection":status;
        if(active)return profile.vpn?"VPN traffic active":"Proxy traffic active";
        return profile.domain.isEmpty()?"Add a server profile to get started":"Ready to connect";
    }
    void toggle(){
        if(active||busy){sendBroadcast(new Intent(TunnelService.STOP).setPackage(getPackageName()));return;}
        applyProfileResolvers();
        try{profile.validate();}catch(Exception e){toast(e.getMessage());editProfile();return;}
        if(profile.vpn){Intent request=VpnService.prepare(this);if(request!=null){startActivityForResult(request,10);return;}}
        startConnection();
    }
    void startConnection(){try{applyProfileResolvers();lastError="";status="Starting secure connection";lastSample=0;lastUp=0;lastDown=0;TunnelService.write(new File(getNoBackupFilesDir(),"pending-profile.json"),profile.json());Intent i=new Intent(this,TunnelService.class);startForegroundService(i);busy=true;updatePowerState();}catch(Exception e){busy=false;updatePowerState();toast(e.getMessage());}}
    void updatePowerState(){
        if(state!=null)state.setText(displayStatus());
        if(statusSubtitle!=null)statusSubtitle.setText(connectionSubtitle());
        if(orbit==null)return;orbit.running=active;orbit.working=busy&&!active;orbit.setContentDescription(active||busy?"Disconnect tunnel":"Connect tunnel");orbit.invalidate();
    }
    @Override protected void onActivityResult(int req,int result,Intent data){super.onActivityResult(req,result,data);
        if(req==resolverImportRequest){if(result==RESULT_OK&&data!=null&&data.getData()!=null&&editable())try(InputStream input=getContentResolver().openInputStream(data.getData())){if(input==null)throw new IOException();ByteArrayOutputStream bytes=new ByteArrayOutputStream();byte[] buffer=new byte[4096];int n;while((n=input.read(buffer))!=-1)bytes.write(buffer,0,n);ArrayList<String> merged=new ArrayList<>(ResolverStore.load(this));merged.addAll(ResolverStore.parse(bytes.toString("UTF-8")));saveResolverList(merged);toast("Resolvers imported");}catch(Exception e){toast("Invalid resolver file: "+e.getMessage());}return;}
        if(req==resolverExportRequest){if(result==RESULT_OK&&data!=null&&data.getData()!=null)try(OutputStream output=getContentResolver().openOutputStream(data.getData(),"wt")){if(output==null)throw new IOException();output.write(ResolverStore.exportText(this).getBytes(java.nio.charset.StandardCharsets.UTF_8));toast("Resolvers exported");}catch(Exception e){toast("Could not export resolvers");}return;}
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
        if(currentTab!=null){editProfileModern(adding,seed);return;}
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
    void editProfileModern(boolean adding,Profile seed){
        if(!editable())return;final Profile draft;try{draft=Profile.parse(seed.json());draft.resolvers=String.join("\n",ResolverStore.load(this));}catch(Exception e){toast("Could not open profile");return;}
        LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);box.setPadding(dp(22),dp(8),dp(22),dp(8));
        EditText name=field(box,"Profile name",draft.name,false),domain=field(box,"Delegated tunnel domain",draft.domain,false),key=field(box,"Server key",draft.key,true);
        box.addView(text("Encryption · must match the server",12,MUTED));Spinner encryption=new Spinner(this);encryption.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,new String[]{"0 · None","1 · XOR","2 · ChaCha20","3 · AES-128-GCM","4 · AES-192-GCM","5 · AES-256-GCM"}));encryption.setSelection(Math.max(0,Math.min(5,draft.encryption)));box.addView(encryption);
        CheckBox compatibility=new CheckBox(this);compatibility.setText("Compatibility mode · Base64 replies, smaller packets");compatibility.setTextColor(TEXT);compatibility.setChecked(draft.compatibility);box.addView(compatibility);
        box.addView(text("Resolver addresses are managed separately in Tools. Profiles and keys remain encrypted on this device.",11,MUTED));
        ScrollView scroll=new ScrollView(this);scroll.addView(box);AlertDialog dialog=new AlertDialog.Builder(this).setTitle(adding?"Add profile":"Edit profile").setView(scroll).setPositiveButton("Save",null).setNegativeButton("Cancel",null).create();
        dialog.setOnShowListener(d->dialog.getButton(-1).setOnClickListener(v->{try{Profile p=Profile.parse(draft.json());p.name=name.getText().toString().trim();p.domain=domain.getText().toString().trim();p.key=key.getText().toString();p.encryption=encryption.getSelectedItemPosition();p.compatibility=compatibility.isChecked();p.resolvers=String.join("\n",ResolverStore.load(this));p.validate();if(adding)selected=profiles.length();profile=p;persist();renderTab("profile");dialog.dismiss();}catch(Exception e){toast(e.getMessage());}}));dialog.show();
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
        if(w==4)new AlertDialog.Builder(this).setTitle("BridgeDNS "+appVersion()).setMessage("Independent Android client for MasterDnsVPN\nCore: masterking32/MasterDnsVPN (MIT)\nTUN: heiher/hev-socks5-tunnel (MIT)\n\nNo ads or telemetry. Profiles are encrypted using Android Keystore.\n\nGeneral UDP is not supported. Real-device and server testing is required.").setPositiveButton("OK",null).show();
        if(w==5)try{event(NativeBridge.selfTest());toast("Native library check passed");}catch(Throwable e){event("Native library check failed: "+e.toString());}
        if(w==6)testDataPath();
        if(w==7&&editable())try{startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("application/json").addCategory(Intent.CATEGORY_OPENABLE),21);}catch(Exception e){toast("Could not open file picker");}
    }).show();}
    void testResolvers(){
        final String list;
        applyProfileResolvers();
        try{if(!ConfigRules.domain(profile.domain))throw new IllegalArgumentException("Enter a valid tunnel domain");list=ConfigRules.normalizeResolvers(profile.resolvers);}catch(Exception e){toast(e.getMessage());return;}
        final String domain=profile.domain;final int generation=++logGeneration;testing=true;testButton.setText("Stop test");event("DNS response test started. This does not verify the full tunnel.");
        final int parallel=profile.scanConcurrency;
        dnsTask=worker.submit(()->{
            ExecutorService pool=Executors.newFixedThreadPool(parallel);CompletionService<String> results=new ExecutorCompletionService<>(pool);
            try{int total=0;for(String r:list.split("\\r?\\n")){if(Thread.currentThread().isInterrupted())return;if(r.trim().isEmpty())continue;final String resolver=r.trim();results.submit(()->probeResolver(domain,resolver));total++;}
                for(int checked=0;checked<total;checked++){if(Thread.currentThread().isInterrupted())return;String result=results.take().get();final String line="["+(checked+1)+"/"+total+"] "+result;runOnUiThread(()->{if(generation==logGeneration)event(line);});}
            }catch(InterruptedException e){Thread.currentThread().interrupt();}catch(Exception e){runOnUiThread(()->{if(generation==logGeneration)event("DNS test stopped");});}
            finally{pool.shutdownNow();runOnUiThread(()->{if(generation==logGeneration){testing=false;if(testButton!=null)testButton.setText("Test DNS");}});}
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
    void event(String s){if(s==null||s.isEmpty())return;if(profile!=null&&!profile.key.isEmpty())s=s.replace(profile.key,"[key redacted]");AppLog.append(this,s,null);if(events.size()>=300)events.removeFirst();events.addLast(new java.text.SimpleDateFormat("HH:mm:ss",Locale.US).format(new Date())+"  "+s);renderEvents();}
    void renderEvents(){if(eventsText!=null){eventsText.setText(events.isEmpty()?"No events yet":String.join("\n",events));if(followLogs!=null&&followLogs.isChecked()&&logScroll!=null)logScroll.post(()->logScroll.fullScroll(View.FOCUS_DOWN));}}
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
    Button button(String s,int fill,int color){Button b=new Button(this);b.setText(s);b.setTextSize(13);b.setTextColor(color);b.setAllCaps(false);b.setMinWidth(0);b.setMinimumWidth(0);b.setMinHeight(dp(40));b.setPadding(dp(10),0,dp(10),0);b.setBackground(bg(fill,12));b.setStateListAnimator(null);return b;}
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
    final class NavGlyph extends View {
        final String name;final Paint paint=new Paint(3);final int color;
        NavGlyph(String name,int color){super(MainActivity.this);this.name=name;this.color=color;}
        void line(Canvas c,float x1,float y1,float x2,float y2){c.drawLine(x1,y1,x2,y2,paint);}
        @Override protected void onDraw(Canvas c){super.onDraw(c);float x=getWidth()/2f,y=getHeight()/2f,s=dp(1);paint.setColor(color);paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(dp(2));paint.setStrokeCap(Paint.Cap.ROUND);paint.setStrokeJoin(Paint.Join.ROUND);
            if(name.equals("profile")){c.drawCircle(x,y-dp(5),dp(4),paint);RectF shoulders=new RectF(x-dp(9),y, x+dp(9),y+dp(15));c.drawArc(shoulders,190,160,false,paint);}
            else if(name.equals("settings")){c.drawCircle(x,y,dp(6),paint);c.drawCircle(x,y,dp(2),paint);for(int i=0;i<8;i++){double a=Math.PI*i/4;line(c,x+(float)Math.cos(a)*dp(7),y+(float)Math.sin(a)*dp(7),x+(float)Math.cos(a)*dp(10),y+(float)Math.sin(a)*dp(10));}}
            else if(name.equals("connect")){RectF circle=new RectF(x-dp(9),y-dp(9),x+dp(9),y+dp(9));c.drawArc(circle,315,270,false,paint);line(c,x,y-dp(12),x,y+dp(1));}
            else if(name.equals("tools")){for(int i=-1;i<=1;i++){float yy=y+i*dp(7);line(c,x-dp(10),yy,x+dp(10),yy);float knob=x+(i==0?dp(4):i<0?-dp(4):0);paint.setStyle(Paint.Style.FILL);c.drawCircle(knob,yy,dp(2.5f),paint);paint.setStyle(Paint.Style.STROKE);}}
            else {RectF page=new RectF(x-dp(8),y-dp(11),x+dp(8),y+dp(11));c.drawRoundRect(page,dp(2),dp(2),paint);line(c,x-dp(4),y-dp(4),x+dp(4),y-dp(4));line(c,x-dp(4),y,x+dp(4),y);line(c,x-dp(4),y+dp(4),x+dp(3),y+dp(4));}
        }
    }
    final class Orbit extends View {
        final Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);boolean running,working;
        Orbit(){super(MainActivity.this);setContentDescription("Connect tunnel");setFocusable(true);setClickable(true);}
        @Override public CharSequence getAccessibilityClassName(){return Button.class.getName();}
        @Override protected void drawableStateChanged(){super.drawableStateChanged();invalidate();}
        @Override protected void onDraw(Canvas c){
            super.onDraw(c);float x=getWidth()/2f,y=getHeight()/2f,r=Math.min(getWidth(),getHeight())/2f-dp(17);
            if(r<=0)return;float ring=r+dp(6);int edge=running||working?MINT:LINE;
            p.setStyle(Paint.Style.FILL);p.setShader(null);
            if(running||working){
                int glow=running?0x7743dfc6:0x5543dfc6;
                p.setShader(new RadialGradient(x,y,ring+dp(10),new int[]{glow,0x003fdfc6},new float[]{.68f,1f},Shader.TileMode.CLAMP));
                c.drawCircle(x,y,ring+dp(10),p);p.setShader(null);
            }
            int upper=running?0xff078c75:working?0xff176e68:palette.dark?0xff35515a:0xffdceae6;
            int lower=running?0xff00594e:working?0xff0d4d52:palette.dark?0xff18323d:0xffbed6d1;
            p.setShader(new LinearGradient(x-r,y-r,x+r,y+r,upper,lower,Shader.TileMode.CLAMP));c.drawCircle(x,y,r,p);p.setShader(null);
            p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(dp(2));p.setColor(edge);c.drawCircle(x,y,r,p);
            p.setStrokeWidth(dp(2));p.setColor(working?LINE:edge);c.drawCircle(x,y,ring,p);
            if(working){p.setStrokeWidth(dp(3));p.setStrokeCap(Paint.Cap.ROUND);p.setColor(MINT);float angle=(SystemClock.uptimeMillis()%1800)*.2f;c.drawArc(x-ring,y-ring,x+ring,y+ring,angle,100,false,p);postInvalidateDelayed(24);}
            p.setStrokeWidth(dp(5));p.setStrokeCap(Paint.Cap.ROUND);p.setColor(running||working?0xffffffff:palette.dark?0xffbdd2d1:0xff516d70);
            float powerRadius=Math.min(dp(25),r*.42f);RectF power=new RectF(x-powerRadius,y-powerRadius,x+powerRadius,y+powerRadius);
            c.drawArc(power,315,270,false,p);c.drawLine(x,y-powerRadius-dp(8),x,y+dp(1),p);
            p.setShader(null);p.setStyle(Paint.Style.FILL);
        }
    }
}
