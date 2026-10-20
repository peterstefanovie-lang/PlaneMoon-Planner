package sk.planemoon.planner;
import android.app.Activity;
import android.os.Bundle;
import android.content.Intent;
import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Build;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import org.json.JSONObject;
import java.net.HttpURLConnection;
import java.net.URL;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
public class MainActivity extends Activity {
 private WebView web;
 private final ExecutorService executor=Executors.newSingleThreadExecutor();
 @Override public void onCreate(Bundle state){super.onCreate(state);web=new WebView(this);web.getSettings().setJavaScriptEnabled(true);web.getSettings().setAllowFileAccess(false);web.getSettings().setAllowContentAccess(false);web.getSettings().setDomStorageEnabled(true);web.setWebViewClient(new WebViewClient());web.addJavascriptInterface(new Bridge(),"PlaneMoonAndroid");setContentView(web);web.loadUrl("file:///android_asset/index.html");if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},8);}
 class Bridge {
 @JavascriptInterface public void startBackground(double lat,double lon,double elev){if(!Double.isFinite(lat)||!Double.isFinite(lon)||!Double.isFinite(elev)||Math.abs(lat)>90||Math.abs(lon)>180)return;getSharedPreferences("scan",MODE_PRIVATE).edit().putLong("lat",Double.doubleToLongBits(lat)).putLong("lon",Double.doubleToLongBits(lon)).putLong("elev",Double.doubleToLongBits(elev)).apply();runOnUiThread(()->{try{startForegroundService(new Intent(MainActivity.this,ScanService.class));}catch(Exception ex){web.evaluateJavascript("document.getElementById('bgstatus').textContent="+JSONObject.quote("Službu sa nepodarilo spustiť: "+ex.getMessage()),null);}});}
 @JavascriptInterface public void stopBackground(){runOnUiThread(()->startService(new Intent(MainActivity.this,ScanService.class).setAction("STOP")));}

 @JavascriptInterface public void loadAircraft(double lat,double lon){
 if(!Double.isFinite(lat)||!Double.isFinite(lon)||Math.abs(lat)>90||Math.abs(lon)>180)return;
 executor.execute(()->{String output;HttpURLConnection c=null;try{
 String endpoint=String.format(java.util.Locale.US,"https://opendata.adsb.fi/api/v2/lat/%.4f/lon/%.4f/dist/100",lat,lon);
 c=(HttpURLConnection)new URL(endpoint).openConnection();c.setRequestMethod("GET");c.setConnectTimeout(10000);c.setReadTimeout(12000);c.setRequestProperty("Accept","application/json");c.setRequestProperty("User-Agent","PlaneMoonPlanner/1.5 (Android; astronomy photo planning)");
 int code=c.getResponseCode();if(code!=200){String detail="";try(InputStream err=c.getErrorStream()){if(err!=null){byte[] b=new byte[400];int n=err.read(b);if(n>0)detail=new String(b,0,n,java.nio.charset.StandardCharsets.UTF_8).replaceAll("[\\r\\n]+"," ");}}throw new Exception("adsb.fi HTTP "+code+" "+detail); }
 try(InputStream in=c.getInputStream();ByteArrayOutputStream buf=new ByteArrayOutputStream()){
 byte[] block=new byte[8192];int n;while((n=in.read(block))!=-1){buf.write(block,0,n);if(buf.size()>3000000)throw new Exception("Odpoveď je príliš veľká");}
 output=buf.toString("UTF-8");new JSONObject(output);
 }
 }catch(Exception ex){output="{\"error\":"+JSONObject.quote(ex.getMessage()==null?"Chyba siete":ex.getMessage())+"}";}finally{if(c!=null)c.disconnect();}
 final String payload=JSONObject.quote(output);runOnUiThread(()->web.evaluateJavascript("window.onAircraftData("+payload+")",null));
 });
 }
 }
 @Override protected void onDestroy(){executor.shutdownNow();web.destroy();super.onDestroy();}
}