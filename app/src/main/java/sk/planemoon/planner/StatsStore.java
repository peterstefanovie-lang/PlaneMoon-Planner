package sk.planemoon.planner;
import android.content.Context;
import android.content.SharedPreferences;
import org.json.*;
import java.text.SimpleDateFormat;
import java.util.*;
public final class StatsStore {
 private static final String[] COUNTS={"scans","nearScans","candidateScans"};
 private StatsStore(){}
 private static String today(){return new SimpleDateFormat("yyyy-MM-dd",Locale.US).format(new Date());}
 private static SharedPreferences pref(Context c){return c.getSharedPreferences("stats",Context.MODE_PRIVATE);}
 private static JSONObject read(SharedPreferences p){try{return new JSONObject(p.getString("archive","{}"));}catch(Exception e){return new JSONObject();}}
 private static JSONObject row(JSONObject archive,String date)throws JSONException{JSONObject v=archive.optJSONObject(date);if(v==null){v=new JSONObject();archive.put(date,v);}return v;}
 private static void save(SharedPreferences p,JSONObject a){p.edit().putString("archive",a.toString()).apply();}
 public static synchronized void migrate(Context c){
  SharedPreferences p=pref(c);if(p.getBoolean("migrated",false))return;
  JSONObject a=read(p);String date=p.getString("day","");if(date.isEmpty())date=today();
  try{JSONObject v=row(a,date);for(String k:COUNTS)v.put(k,v.optInt(k)+p.getInt(k,0));v.put("unique",Math.max(v.optInt("unique"),p.getInt("unique",0)));v.put("seen",p.getString("seen","[]"));trim(a);save(p,a);}catch(Exception ignored){}
  p.edit().putBoolean("migrated",true).apply();
 }
 private static void trim(JSONObject a){ArrayList<String> keys=new ArrayList<>();Iterator<String> it=a.keys();while(it.hasNext())keys.add(it.next());Collections.sort(keys);for(int i=0;i<keys.size()-30;i++)a.remove(keys.get(i));}
 public static synchronized void record(Context c,JSONArray planes,boolean near,boolean candidate){
  migrate(c);SharedPreferences p=pref(c);JSONObject a=read(p);String date=today();
  try{
   JSONObject v=row(a,date);v.put("scans",v.optInt("scans")+1);if(near)v.put("nearScans",v.optInt("nearScans")+1);if(candidate)v.put("candidateScans",v.optInt("candidateScans")+1);
   HashSet<String> ids=new HashSet<>();JSONArray previous=v.optJSONArray("seen");if(previous!=null)for(int i=0;i<previous.length();i++)ids.add(previous.optString(i));
   for(int i=0;i<planes.length();i++){JSONObject item=planes.optJSONObject(i);if(item!=null){String id=item.optString("hex","").trim();if(!id.isEmpty())ids.add(id);}}
   v.put("seen",new JSONArray(ids));v.put("unique",ids.size());v.put("lastCount",planes.length());v.put("lastSuccess",new SimpleDateFormat("dd.MM. HH:mm:ss",Locale.getDefault()).format(new Date()));
   trim(a);save(p,a);
   p.edit().putString("day",date).putString("lastSuccess",v.optString("lastSuccess")).putString("lastCount",String.valueOf(planes.length())).putString("state","Úspešné načítanie").putString("error","").apply();
  }catch(Exception e){p.edit().putString("error","Zápis histórie: "+e.getMessage()).apply();}
 }
 public static synchronized void recordApproaches(Context c,JSONArray candidates){
  SharedPreferences p=pref(c);JSONArray log;try{log=new JSONArray(p.getString("approaches","[]"));}catch(Exception e){log=new JSONArray();}
  long now=System.currentTimeMillis();ArrayList<JSONObject> entries=new ArrayList<>();
  for(int i=0;i<log.length();i++){JSONObject v=log.optJSONObject(i);if(v!=null&&now-v.optLong("predictedAt",now)<31L*86400000L)entries.add(v);}
  for(int i=0;i<candidates.length();i++){JSONObject incoming=candidates.optJSONObject(i);if(incoming==null)continue;
   String hex=incoming.optString("hex","");long when=incoming.optLong("predictedAt");if(hex.isEmpty()||when<=0)continue;
   int match=-1;for(int j=0;j<entries.size();j++){JSONObject old=entries.get(j);if(hex.equals(old.optString("hex"))&&Math.abs(when-old.optLong("predictedAt"))<10*60000L){match=j;break;}}
   if(match<0)entries.add(incoming);else if(incoming.optDouble("separation",99)<entries.get(match).optDouble("separation",99))entries.set(match,incoming);
  }
  entries.sort((a,b)->Long.compare(b.optLong("predictedAt"),a.optLong("predictedAt")));
  JSONArray out=new JSONArray();for(int i=0;i<Math.min(1000,entries.size());i++)out.put(entries.get(i));
  p.edit().putString("approaches",out.toString()).apply();
 }
 public static synchronized String summary(Context c){
  migrate(c);SharedPreferences p=pref(c);JSONObject a=read(p),result=new JSONObject(),total=new JSONObject(),current=a.optJSONObject(today());if(current==null)current=new JSONObject();
  try{
   JSONArray days=new JSONArray();ArrayList<String> keys=new ArrayList<>();Iterator<String> it=a.keys();while(it.hasNext())keys.add(it.next());Collections.sort(keys,Collections.reverseOrder());
   for(String k:COUNTS)total.put(k,0);total.put("uniqueDailySum",0);
   for(String day:keys){JSONObject v=a.optJSONObject(day);if(v==null)continue;JSONObject d=new JSONObject();d.put("day",day);
    for(String k:COUNTS){int n=v.optInt(k);d.put(k,n);total.put(k,total.optInt(k)+n);}
    d.put("unique",v.optInt("unique"));total.put("uniqueDailySum",total.optInt("uniqueDailySum")+v.optInt("unique"));
    days.put(d);
   }
   result.put("approaches",new JSONArray(p.getString("approaches","[]")));result.put("day",today());result.put("today",current);result.put("total",total);result.put("days",days);
   for(String k:new String[]{"lastSuccess","lastError","lastCount","state","error"})result.put(k,p.getString(k,""));
  }catch(Exception ignored){}
  return result.toString();
 }
 public static synchronized void reset(Context c){pref(c).edit().clear().apply();}
}
