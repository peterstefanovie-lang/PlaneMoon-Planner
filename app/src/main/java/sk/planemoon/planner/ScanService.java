package sk.planemoon.planner;
import android.app.*;
import android.content.*;
import android.os.*;
import org.json.*;
import java.net.*;
import java.io.*;
import java.util.*;
public class ScanService extends Service {
 private Handler handler; private boolean running; private long lastAlert=0;
 private android.content.SharedPreferences stats(){return getSharedPreferences("stats",MODE_PRIVATE);}
 private void status(String key,String value){stats().edit().putString(key,value).apply();}
 private void error(String message){status("error",message);status("lastError",new java.text.SimpleDateFormat("dd.MM. HH:mm:ss",Locale.getDefault()).format(new Date()));}

 private static final String CHANNEL="plane_moon_scan", ALERT="plane_moon_alert";
 private final Runnable poll=new Runnable(){public void run(){if(!running)return;new Thread(()->{try{scan();}catch(Exception ex){error(ex.getClass().getSimpleName()+": "+ex.getMessage());}finally{if(running)handler.postDelayed(this,30000);}}).start();}};
 @Override public void onCreate(){super.onCreate();handler=new Handler(Looper.getMainLooper());NotificationManager n=getSystemService(NotificationManager.class);n.createNotificationChannel(new NotificationChannel(CHANNEL,"Sledovanie lietadiel",NotificationManager.IMPORTANCE_LOW));n.createNotificationChannel(new NotificationChannel(ALERT,"Upozornenia na tranzity",NotificationManager.IMPORTANCE_HIGH));}
 @Override public int onStartCommand(Intent intent,int flags,int id){if(intent!=null&&"STOP".equals(intent.getAction())){running=false;handler.removeCallbacks(poll);stopForeground(STOP_FOREGROUND_REMOVE);stopSelf();return START_NOT_STICKY;}startForeground(101,notification("Sledovanie aktívne; skenujem iba keď je Mesiac nad obzorom."));if(!running){running=true;handler.post(poll);}return START_STICKY;}
 private Notification notification(String s){Intent stop=new Intent(this,ScanService.class).setAction("STOP");PendingIntent p=PendingIntent.getService(this,1,stop,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);return new Notification.Builder(this,CHANNEL).setSmallIcon(android.R.drawable.ic_menu_compass).setContentTitle("PlaneMoon Planner").setContentText(s).setOngoing(true).addAction(android.R.drawable.ic_menu_close_clear_cancel,"Zastaviť",p).build();}
 private void scan()throws Exception{
  android.content.SharedPreferences pref=getSharedPreferences("scan",MODE_PRIVATE);double lat=Double.longBitsToDouble(pref.getLong("lat",Double.doubleToLongBits(48.8087))),lon=Double.longBitsToDouble(pref.getLong("lon",Double.doubleToLongBits(19.3164))),elev=Double.longBitsToDouble(pref.getLong("elev",Double.doubleToLongBits(525)));
  long now=System.currentTimeMillis();double[] m=moon(now,lat,lon,elev);if(m[1]<=0){status("state","Mesiac pod obzorom");return;}status("state","Skenovanie");
  HttpURLConnection c=null;String raw;
  try{c=(HttpURLConnection)new URL(String.format(Locale.US,"https://opendata.adsb.fi/api/v2/lat/%.4f/lon/%.4f/dist/100",lat,lon)).openConnection();c.setConnectTimeout(10000);c.setReadTimeout(12000);c.setRequestProperty("User-Agent","PlaneMoonPlanner/3.3 (Android; astronomy photography)");try(InputStream in=c.getInputStream();ByteArrayOutputStream b=new ByteArrayOutputStream()){byte[] block=new byte[8192];int n;while((n=in.read(block))!=-1){b.write(block,0,n);if(b.size()>3000000)return;}raw=b.toString("UTF-8");}}finally{if(c!=null)c.disconnect();}
  JSONObject response=new JSONObject(raw);JSONArray aircraft=response.optJSONArray("ac");if(aircraft==null)aircraft=response.optJSONArray("aircraft");if(aircraft==null){String message=response.optString("error",response.optString("message",""));throw new Exception("ADS-B odpoveď bez zoznamu lietadiel"+(message.isEmpty()?" (polia: "+response.names()+")":": "+message));}
  double closest=100;String flight="";int seconds=0;double moonRadius=0;boolean nearAny=false;boolean candidateAny=false;JSONArray approaches=new JSONArray();
  for(int i=0;i<aircraft.length();i++){JSONObject a=aircraft.optJSONObject(i);if(a==null||!a.has("lat")||!a.has("lon")||"ground".equals(a.optString("alt_baro")))continue;
   double alat=a.optDouble("lat",Double.NaN),alon=a.optDouble("lon",Double.NaN),ft=a.optDouble("alt_geom",a.optDouble("alt_baro",Double.NaN)),gs=a.optDouble("gs",Double.NaN),trk=a.optDouble("track",Double.NaN),age=a.optDouble("seen_pos",a.optDouble("seen",0)),vr=a.optDouble("baro_rate",a.optDouble("geom_rate",0));
   if(!Double.isFinite(alat)||!Double.isFinite(alon)||!Double.isFinite(ft)||!Double.isFinite(gs)||!Double.isFinite(trk)||gs<40||age>30)continue;
   double bestSep=99;int bestT=0;double bestRadius=0;for(int t=0;t<=120;t+=2){double dt=t+Math.max(0,age),km=gs*1.852*dt/3600,br=Math.toRadians(trk),p=Math.toRadians(alat),d=km/6371.0088,p2=Math.asin(Math.sin(p)*Math.cos(d)+Math.cos(p)*Math.sin(d)*Math.cos(br)),l2=Math.toRadians(alon)+Math.atan2(Math.sin(br)*Math.sin(d)*Math.cos(p),Math.cos(d)-Math.sin(p)*Math.sin(p2));
    double[] pos=horizontal(lat,lon,elev,Math.toDegrees(p2),Math.toDegrees(l2),(ft+vr*dt/60)*0.3048),moon=moon(now+t*1000L,lat,lon,elev);
    if(pos[1]<=0||moon[1]<=0)continue;double sep=separation(pos,moon);if(sep<bestSep){bestSep=sep;bestT=t;bestRadius=moon[2];}if(sep<2)nearAny=true;if(sep<=moon[2])candidateAny=true;if(sep<closest){closest=sep;flight=a.optString("flight",a.optString("hex","Lietadlo")).trim();seconds=t;moonRadius=moon[2];}
   }
   if(bestSep<2){JSONObject event=new JSONObject();event.put("hex",a.optString("hex",""));event.put("flight",a.optString("flight",a.optString("hex","")).trim());event.put("predictedAt",now+bestT*1000L);event.put("separation",bestSep);event.put("moonRadius",bestRadius);event.put("altFt",ft);event.put("speedKt",gs);event.put("track",trk);approaches.put(event);}
  }
  StatsStore.recordApproaches(this,approaches);
  StatsStore.record(this,aircraft,nearAny,candidateAny);
 if(closest<=moonRadius&&now-lastAlert>60000){lastAlert=now;String msg=flight+" – možný prelet o "+seconds+" s (orientačný odhad).";Notification n=new Notification.Builder(this,ALERT).setSmallIcon(android.R.drawable.ic_dialog_alert).setContentTitle("⚠️ Možný tranzit pred Mesiacom").setContentText(msg).setAutoCancel(true).setDefaults(Notification.DEFAULT_SOUND|Notification.DEFAULT_VIBRATE).build();getSystemService(NotificationManager.class).notify(102,n);}
 }
 private static double mod(double x,double n){return (x%n+n)%n;}
 private static double[] moon(long millis,double lat,double lon,double elev){
 double d=millis/86400000.0+2440587.5-2451545,rad=Math.PI/180;
 double L=Math.toRadians(mod(218.3164477+13.17639648*d,360)),M=Math.toRadians(mod(134.9633964+13.06499295*d,360)),F=Math.toRadians(mod(93.2720950+13.22935024*d,360)),D=Math.toRadians(mod(297.8501921+12.19074912*d,360)),Ms=Math.toRadians(mod(357.5291092+0.98560028*d,360));
 double mlon=L+rad*(6.289*Math.sin(M)+1.274*Math.sin(2*D-M)+0.658*Math.sin(2*D)+0.214*Math.sin(2*M)-0.186*Math.sin(Ms)-0.114*Math.sin(2*F));
 double mlat=rad*(5.128*Math.sin(F)+0.280*Math.sin(M+F)+0.277*Math.sin(M-F)+0.173*Math.sin(2*D-F)+0.055*Math.sin(2*D+F-M)+0.046*Math.sin(2*D-F-M));
 double dist=385000.56-20905.36*Math.cos(M)-3699.11*Math.cos(2*D-M)-2955.97*Math.cos(2*D)-569.93*Math.cos(2*M),eps=Math.toRadians(23.439291-0.0000003563*d);
 double xe=dist*Math.cos(mlat)*Math.cos(mlon),ye=dist*Math.cos(mlat)*Math.sin(mlon),ze=dist*Math.sin(mlat),x=xe,y=ye*Math.cos(eps)-ze*Math.sin(eps),z=ye*Math.sin(eps)+ze*Math.cos(eps);
 double T=d/36525,theta=Math.toRadians(mod(280.46061837+360.98564736629*d+0.000387933*T*T-T*T*T/38710000,360)),p=Math.toRadians(lat),H=theta+Math.toRadians(lon),a=6378.137,e2=0.00669437999014,N=a/Math.sqrt(1-e2*Math.sin(p)*Math.sin(p)),h=elev/1000;
 double dx=x-(N+h)*Math.cos(p)*Math.cos(H),dy=y-(N+h)*Math.cos(p)*Math.sin(H),dz=z-(N*(1-e2)+h)*Math.sin(p);
 double east=-Math.sin(H)*dx+Math.cos(H)*dy,north=-Math.sin(p)*Math.cos(H)*dx-Math.sin(p)*Math.sin(H)*dy+Math.cos(p)*dz,up=Math.cos(p)*Math.cos(H)*dx+Math.cos(p)*Math.sin(H)*dy+Math.sin(p)*dz;
 return new double[]{mod(Math.toDegrees(Math.atan2(east,north)),360),Math.toDegrees(Math.atan2(up,Math.hypot(east,north))),Math.toDegrees(Math.asin(1737.4/Math.sqrt(dx*dx+dy*dy+dz*dz)))};
 }
 private static double[] ecef(double lat,double lon,double h){double p=Math.toRadians(lat),l=Math.toRadians(lon),a=6378137,e2=0.00669437999014,N=a/Math.sqrt(1-e2*Math.sin(p)*Math.sin(p));return new double[]{(N+h)*Math.cos(p)*Math.cos(l),(N+h)*Math.cos(p)*Math.sin(l),(N*(1-e2)+h)*Math.sin(p)};}
 private static double[] horizontal(double lat,double lon,double elev,double tlat,double tlon,double te){double[] o=ecef(lat,lon,elev),v=ecef(tlat,tlon,te);double dx=v[0]-o[0],dy=v[1]-o[1],dz=v[2]-o[2],p=Math.toRadians(lat),l=Math.toRadians(lon);double east=-Math.sin(l)*dx+Math.cos(l)*dy,north=-Math.sin(p)*Math.cos(l)*dx-Math.sin(p)*Math.sin(l)*dy+Math.cos(p)*dz,up=Math.cos(p)*Math.cos(l)*dx+Math.cos(p)*Math.sin(l)*dy+Math.sin(p)*dz;return new double[]{mod(Math.toDegrees(Math.atan2(east,north)),360),Math.toDegrees(Math.atan2(up,Math.hypot(east,north)))};}
 private static double separation(double[] a,double[] b){double p=Math.toRadians(a[1]),q=Math.toRadians(b[1]),d=Math.toRadians(a[0]-b[0]);return Math.toDegrees(Math.acos(Math.max(-1,Math.min(1,Math.sin(p)*Math.sin(q)+Math.cos(p)*Math.cos(q)*Math.cos(d)))));}
 @Override public android.os.IBinder onBind(Intent i){return null;}
 @Override public void onDestroy(){running=false;handler.removeCallbacks(poll);super.onDestroy();}
}