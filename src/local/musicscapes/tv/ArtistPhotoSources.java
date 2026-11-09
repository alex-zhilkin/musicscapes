package local.musicscapes.tv;

import android.content.*;
import org.json.*;
import java.io.*;
import java.net.*;
import java.util.*;

/** Optional keyed sources. All calls run on the existing single discovery worker. */
public final class ArtistPhotoSources {
    private final Context context;
    private final LinkedHashMap<String,Long> attempted=new LinkedHashMap<>();
    private final Map<String,Long> cooldown=new HashMap<>();
    private Map<String,String> licences;
    private long lastRequest;
    private static final class ApiFailure extends IOException{final int status;ApiFailure(int code){super("Provider request unavailable");status=code;}}
    public ArtistPhotoSources(Context context){this.context=context.getApplicationContext();}
    private SharedPreferences prefs(){return context.getSharedPreferences("artist-photo-sources",0);}
    public List<JSONObject> discover(JSONObject query,java.util.concurrent.Callable<Boolean> valid){
        List<JSONObject> out=new ArrayList<>();if(!ArtistPhotoRules.identity(query))return out;
        for(String source:new String[]{"fanart","flickr"}){
            if(BuildProfile.STORE||!enabled(source)||source.equals("flickr")&&!ArtistPhotoRules.flickrName(query))continue;
            long now=System.currentTimeMillis();String attempt=source+":"+query.optString("artistId");
            if(now<cooldown.getOrDefault(source,0L)||now-attempted.getOrDefault(attempt,0L)<60L*60*1000)continue;
            attempted.put(attempt,now);while(attempted.size()>96)attempted.remove(attempted.keySet().iterator().next());
            int count=0;String outcome="completed";
            try{
                if(!valid.call())break;
                if(source.equals("fanart")){
                    JSONObject data=read("fanart","https://webservice.fanart.tv/v3.2/music/"+query.getString("artistId"),null,valid);
                    List<JSONObject> photos=ArtistPhotoRules.fanart(data,query,BuildProfile.STORE);out.addAll(photos);count=photos.size();
                }else{
                    if(licences==null)licences=ArtistPhotoRules.flickrLicences(flickr("flickr.photos.licenses.getInfo",new LinkedHashMap<>(),valid));
                    if(licences.isEmpty())continue;
                    Map<String,String> args=new LinkedHashMap<>();args.put("text","\""+query.getString("text")+"\" -poster -tribute -logo -statue");args.put("license",String.join(",",licences.keySet()));args.put("media","photos");args.put("content_types","0");args.put("safe_search","1");args.put("sort","relevance");args.put("per_page","12");args.put("extras","description,license,owner_name");
                    JSONObject data=flickr("flickr.photos.search",args,valid);JSONArray photos=data.getJSONObject("photos").getJSONArray("photo");int checked=0;
                    for(int i=0;i<photos.length()&&checked<6&&count<4;i++){
                        if(!valid.call())break;JSONObject p=photos.getJSONObject(i);
                        String description=p.optJSONObject("description")==null?"":p.getJSONObject("description").optString("_content");
                        if(!VisualTags.artistSubject(p.optString("title"),DiscoveryRules.clean(description),query.optString("text"),query.optString("artistType"))||!licences.containsKey(p.optString("license")))continue;
                        Map<String,String> sizeArgs=new LinkedHashMap<>();sizeArgs.put("photo_id",p.optString("id"));checked++;
                        try{JSONObject r=ArtistPhotoRules.flickr(p,flickr("flickr.photos.getSizes",sizeArgs,valid),query,licences);if(r!=null){out.add(r);count++;}}
                        catch(ApiFailure error){if(error.status!=404)throw error;}
                    }
                }
                prefs().edit().putString(source+"Status",count>0?"Connected":"Connected · no suitable images").apply();
            }catch(Exception e){int code=e instanceof ApiFailure?((ApiFailure)e).status:0;outcome=code==404?"no_artist_images":code==401||code==403?"key_rejected":"unavailable";cooldown.put(source,Math.max(cooldown.getOrDefault(source,0L),now+10L*60*1000));prefs().edit().putString(source+"Status",code==404?"Connected · no artist images":code==401||code==403?"API key rejected":"Unavailable · saved images retained").apply();}
            try{ReviewLog.get(context).record("artist_source",new JSONObject().put("provider",source).put("outcome",outcome).put("count",count));}catch(Exception ignored){}
        }
        out.sort((a,b)->Integer.compare(b.optInt("width"),a.optInt("width")));return out;
    }
    private boolean enabled(String source){return !prefs().getString(source+"Key","").isEmpty();}
    public boolean available(String source){return System.currentTimeMillis()>=cooldown.getOrDefault(source,0L);}
    public void coolDown(String source,long seconds){cooldown.put(source,System.currentTimeMillis()+Math.max(60,Math.min(86400,seconds))*1000);}
    private JSONObject flickr(String method,Map<String,String> fields,java.util.concurrent.Callable<Boolean> valid)throws Exception{
        Map<String,String> all=new LinkedHashMap<>();all.put("method",method);all.put("api_key",prefs().getString("flickrKey",""));all.put("format","json");all.put("nojsoncallback","1");all.putAll(fields);
        StringBuilder body=new StringBuilder();for(Map.Entry<String,String> p:all.entrySet()){if(body.length()>0)body.append('&');body.append(URLEncoder.encode(p.getKey(),"UTF-8")).append('=').append(URLEncoder.encode(p.getValue(),"UTF-8"));}
        JSONObject r=read("flickr","https://api.flickr.com/services/rest/",body.toString(),valid);if(!r.optString("stat").equals("ok")){int code=r.optInt("code");throw new ApiFailure(code==100?401:code==1||code==2?404:503);}return r;
    }
    private JSONObject read(String source,String url,String body,java.util.concurrent.Callable<Boolean> valid)throws Exception{
        long wait=1200-(System.currentTimeMillis()-lastRequest);if(wait>0)Thread.sleep(wait);if(!valid.call())throw new IOException("Cancelled");lastRequest=System.currentTimeMillis();
        HttpURLConnection c=(HttpURLConnection)SourceConsent.open(new URL(url));c.setInstanceFollowRedirects(false);c.setConnectTimeout(6000);c.setReadTimeout(8000);c.setRequestProperty("User-Agent","MusicScapes/"+BuildProfile.VERSION+" (personal Android TV application)");
        if(source.equals("fanart"))c.setRequestProperty("client-key",prefs().getString("fanartKey",""));
        try{
            if(body!=null){c.setRequestMethod("POST");c.setDoOutput(true);c.setRequestProperty("Content-Type","application/x-www-form-urlencoded; charset=UTF-8");byte[] data=body.getBytes("UTF-8");c.setFixedLengthStreamingMode(data.length);try(OutputStream out=c.getOutputStream()){out.write(data);}}
            int code=c.getResponseCode();if(code==429||code==503){long seconds=300;try{seconds=Math.max(60,Math.min(86400,Long.parseLong(c.getHeaderField("Retry-After"))));}catch(Exception ignored){}cooldown.put(source,System.currentTimeMillis()+seconds*1000);throw new IOException("Source cooling down");}
            if(code!=200)throw new ApiFailure(code);if(c.getContentLengthLong()>2000000)throw new IOException("Provider response too large");
            try(InputStream in=c.getInputStream();ByteArrayOutputStream bytes=new ByteArrayOutputStream()){byte[] buffer=new byte[16384];int n;while((n=in.read(buffer))!=-1){if(bytes.size()+n>2000000||!valid.call())throw new IOException("Response cancelled or too large");bytes.write(buffer,0,n);}return new JSONObject(bytes.toString("UTF-8"));}
        }finally{c.disconnect();}
    }
    public static JSONObject status(Context c)throws Exception{
        SharedPreferences p=c.getSharedPreferences("artist-photo-sources",0);return new JSONObject().put("fanart",p.getString("fanartKey","").isEmpty()?"Needs API key":p.getString("fanartStatus","Configured")).put("flickr",p.getString("flickrKey","").isEmpty()?"Needs API key":p.getString("flickrStatus","Configured"));
    }
    public static void configure(Context c,JSONObject config)throws Exception{
        if(BuildProfile.STORE)throw new IOException("Personal builds only");SharedPreferences.Editor edit=c.getSharedPreferences("artist-photo-sources",0).edit();
        for(String source:new String[]{"fanart","flickr"})if(config.has(source+"Key")){String key=config.getString(source+"Key").trim();if(!key.isEmpty()&&!key.matches("[a-fA-F0-9]{32}"))throw new IOException("Invalid key format");edit.putString(source+"Key",key).putString(source+"Status",key.isEmpty()?"Needs API key":"Configured");}
        if(!edit.commit())throw new IOException("Could not save keys");
    }
}
