package dev.bridgedns.vpn;

import android.annotation.SuppressLint;
import android.app.*;
import android.content.*;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.*;
import android.text.*;
import android.view.*;
import android.widget.*;
import org.json.*;
import java.io.*;
import java.util.*;

public final class ResolverActivity extends Activity {
    int BG,CARD,TEXT,MUTED,MINT;UiPalette palette;
    final Handler handler=new Handler(Looper.getMainLooper());
    final ArrayList<Row> rows=new ArrayList<>(),shown=new ArrayList<>();
    final Map<String,Row> previous=new HashMap<>();
    ListView list;BaseAdapter adapter;TextView summary;EditText search;Spinner sort;
    long lastCaptured=0,lastLive=0;boolean connected=false;
    final BroadcastReceiver receiver=new BroadcastReceiver(){public void onReceive(Context c,Intent i){
        if(TunnelService.STATE.equals(i.getAction())){connected=i.getBooleanExtra("active",false);lastLive=SystemClock.elapsedRealtime();updateSummary();}else load();
    }};
    final Runnable poll=new Runnable(){public void run(){sendBroadcast(new Intent(TunnelService.QUERY).setPackage(getPackageName()));sendBroadcast(new Intent(TunnelService.RESOLVERS_QUERY).setPackage(getPackageName()));updateSummary();handler.postDelayed(this,2000);}};
    @Override protected void attachBaseContext(Context base){android.content.res.Configuration configuration=new android.content.res.Configuration(base.getResources().getConfiguration());configuration.setLocale(Locale.ENGLISH);configuration.setLayoutDirection(Locale.ENGLISH);super.attachBaseContext(base.createConfigurationContext(configuration));}
    @Override public void onCreate(Bundle state){UiPalette.apply(this);super.onCreate(state);palette=UiPalette.from(this);BG=palette.background;CARD=palette.card;TEXT=palette.text;MUTED=palette.muted;MINT=palette.accent;getWindow().setStatusBarColor(BG);getWindow().setNavigationBarColor(BG);
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setPadding(dp(20),dp(16),dp(20),dp(8));root.setBackgroundColor(BG);root.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);setContentView(root);root.setOnApplyWindowInsetsListener((v,insets)->{root.setPadding(dp(20),dp(16)+insets.getSystemWindowInsetTop(),dp(20),dp(8)+insets.getSystemWindowInsetBottom());return insets.consumeSystemWindowInsets();});
        LinearLayout title=row();Button back=button("‹ Back");LinearLayout.LayoutParams backParams=new LinearLayout.LayoutParams(dp(78),dp(42));backParams.rightMargin=dp(12);title.addView(back,backParams);back.setOnClickListener(v->finish());title.addView(text("Resolver dashboard",19,TEXT),new LinearLayout.LayoutParams(0,-2,1));root.addView(title);
        summary=text("Connect to collect live measurements",12,MUTED);root.addView(summary);
        LinearLayout filters=row();sort=new Spinner(this);String[] modes={"Latency ↑","DNS download ↓","DNS upload ↓","Loss ↑","Healthy first","Address"};
        ArrayAdapter<String> sortAdapter=new ArrayAdapter<String>(this,android.R.layout.simple_spinner_dropdown_item,modes){public View getView(int pos,View v,ViewGroup parent){TextView t=(TextView)super.getView(pos,v,parent);t.setTextColor(MINT);t.setTextSize(13);return t;}};sort.setAdapter(sortAdapter);filters.addView(sort,new LinearLayout.LayoutParams(dp(155),dp(48)));
        search=new EditText(this);search.setHint("Filter IP");search.setTextColor(TEXT);search.setTextSize(13);search.setSingleLine(true);LinearLayout.LayoutParams searchParams=new LinearLayout.LayoutParams(0,dp(44),1);searchParams.leftMargin=dp(10);filters.addView(search,searchParams);LinearLayout.LayoutParams filterParams=new LinearLayout.LayoutParams(-1,-2);filterParams.topMargin=dp(12);filterParams.bottomMargin=dp(8);root.addView(filters,filterParams);
        sort.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){public void onNothingSelected(AdapterView<?> p){}public void onItemSelected(AdapterView<?> p,View v,int n,long id){render();}});
        search.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int st,int count,int after){}public void onTextChanged(CharSequence s,int st,int before,int count){render();}public void afterTextChanged(Editable e){}});
        list=new ListView(this);list.setDivider(new android.graphics.drawable.ColorDrawable(BG));list.setDividerHeight(dp(8));list.setCacheColorHint(BG);adapter=new BaseAdapter(){public int getCount(){return shown.size();}public Object getItem(int n){return shown.get(n);}public long getItemId(int n){return n;}
            public View getView(int n,View recycled,ViewGroup parent){LinearLayout box=recycled instanceof LinearLayout?(LinearLayout)recycled:new LinearLayout(ResolverActivity.this);box.removeAllViews();box.setOrientation(LinearLayout.VERTICAL);box.setPadding(dp(12),dp(9),dp(12),dp(9));box.setBackground(background(CARD));Row r=shown.get(n);
                LinearLayout top=row();TextView address=text(r.address,15,TEXT);address.setTypeface(Typeface.MONOSPACE);top.addView(address,new LinearLayout.LayoutParams(0,-2,1));top.addView(text(r.healthy?"Healthy":r.tested?"Inactive":"Untested",11,r.healthy?MINT:MUTED));box.addView(top);
                box.addView(text("RTT "+(r.rtt<0?"—":String.format(Locale.US,"%.0f ms",r.rtt))+"    Loss "+(r.loss<0?"—":String.format(Locale.US,"%.1f%%",r.loss))+"    MTU ↑"+r.upMtu+" ↓"+r.downMtu,11,MUTED));
                box.addView(text("DNS ↓ "+MainActivity.format(r.downRate)+"/s    ↑ "+MainActivity.format(r.upRate)+"/s",13,MINT));
                box.addView(text("Runtime totals  ↓ "+MainActivity.format(r.down)+"    ↑ "+MainActivity.format(r.up),11,MUTED));return box;}};
        list.setAdapter(adapter);root.addView(list,new LinearLayout.LayoutParams(-1,0,1));load();
    }
    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    @Override public void onStart(){super.onStart();IntentFilter f=new IntentFilter(TunnelService.RESOLVERS_STATE);f.addAction(TunnelService.STATE);if(Build.VERSION.SDK_INT>=33)registerReceiver(receiver,f,Context.RECEIVER_NOT_EXPORTED);else registerReceiver(receiver,f);handler.post(poll);}
    @Override public void onStop(){handler.removeCallbacks(poll);unregisterReceiver(receiver);super.onStop();}
    void load(){try{File file=new File(getNoBackupFilesDir(),"resolver-snapshot.json");if(!file.isFile())return;JSONObject data=new JSONObject(new String(java.nio.file.Files.readAllBytes(file.toPath()),java.nio.charset.StandardCharsets.UTF_8));long captured=data.getLong("captured_at");if(captured==lastCaptured)return;
        JSONArray input=data.getJSONArray("rows");rows.clear();double seconds=(captured-lastCaptured)/1000.0;
        for(int n=0;n<input.length();n++){Row r=new Row(input.getJSONObject(n));Row old=previous.get(r.key);if(old!=null&&lastCaptured>0&&seconds>0&&r.up>=old.up&&r.down>=old.down){r.upRate=(r.up-old.up)/seconds;r.downRate=(r.down-old.down)/seconds;}rows.add(r);}
        previous.clear();for(Row r:rows)previous.put(r.key,r);lastCaptured=captured;render();updateSummary();
    }catch(Exception ignored){summary.setText("Could not read resolver measurements");}}
    void render(){if(adapter==null||search==null)return;shown.clear();String filter=search.getText().toString().trim().toLowerCase(Locale.US);for(Row r:rows)if(r.address.toLowerCase(Locale.US).contains(filter))shown.add(r);
        int mode=sort.getSelectedItemPosition();Comparator<Row> comparator;
        switch(mode){case 1:comparator=Comparator.comparingDouble((Row r)->r.downRate).reversed();break;case 2:comparator=Comparator.comparingDouble((Row r)->r.upRate).reversed();break;case 3:comparator=Comparator.comparingDouble(r->r.loss<0?Double.MAX_VALUE:r.loss);break;case 4:comparator=Comparator.comparingInt(r->r.healthy?0:r.tested?1:2);break;case 5:comparator=Comparator.comparing(r->r.address);break;default:comparator=Comparator.comparingDouble(r->r.rtt<0?Double.MAX_VALUE:r.rtt);}
        shown.sort(comparator.thenComparing(r->r.address));adapter.notifyDataSetChanged();}
    void updateSummary(){if(summary==null)return;boolean live=connected&&SystemClock.elapsedRealtime()-lastLive<6000;long count=0;for(Row r:rows)if(r.healthy)count++;summary.setText((live?"Live session":"Last captured session")+" · "+count+" / "+rows.size()+" healthy\nRTT = DNS response latency · rates include DNS overhead");}
    static final class Row{String key,address;boolean healthy,tested;double rtt,loss,upRate,downRate;long up,down;int upMtu,downMtu;
        Row(JSONObject j){key=j.optString("key");address=j.optString("resolver");healthy=j.optBoolean("healthy");tested=j.optBoolean("tested");rtt=j.optDouble("rtt_ms",-1);loss=j.optDouble("loss_pct",-1);up=j.optLong("up");down=j.optLong("down");upMtu=j.optInt("upload_mtu");downMtu=j.optInt("download_mtu");}}
    int dp(int n){return (int)(n*getResources().getDisplayMetrics().density+.5f);}LinearLayout row(){LinearLayout r=new LinearLayout(this);r.setOrientation(LinearLayout.HORIZONTAL);r.setGravity(Gravity.CENTER_VERTICAL);return r;}
    TextView text(String s,int size,int color){TextView t=new TextView(this);t.setText(s);t.setTextSize(size);t.setTextColor(color);t.setPadding(0,dp(3),0,dp(3));return t;}
    GradientDrawable background(int color){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(14));return d;}
    Button button(String s){Button b=new Button(this);b.setText(s);b.setTextSize(12);b.setTextColor(MINT);b.setAllCaps(false);b.setMinWidth(0);b.setMinimumWidth(0);b.setPadding(dp(8),0,dp(8),0);b.setBackground(background(BG));return b;}
}
