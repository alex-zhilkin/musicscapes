package local.musicscapes.tv;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Converts lyrics to a few visual cues locally. Raw lyrics are never shown or saved. */
public final class LyricsSignals {
    public static final class Result {
        final JSONObject cues;
        final String status;
        final String semanticText;
        final JSONObject source;
        final boolean retryable;
        final long retryAfter;
        Result(JSONObject cues, String status) { this(cues,status,""); }
        Result(JSONObject cues,String status,String semanticText){this(cues,status,semanticText,new JSONObject(),false,0);}
        Result(JSONObject cues,String status,String semanticText,JSONObject source,boolean retryable,long retryAfter){this.cues=cues;this.status=status;this.semanticText=semanticText;this.source=source;this.retryable=retryable;this.retryAfter=retryAfter;}
        Result withoutText(){return new Result(cues,status,"",source,retryable,retryAfter);}
    }
    private static final Map<String,String> WORDS = new LinkedHashMap<>();
    static {
        WORDS.put("road", "road roads highway highways drive driving truck trucks car wheels street streets");
        WORDS.put("home", "home hometown porch house smalltown backyard kitchen");
        WORDS.put("country", "cowboy cowboys ranch farm farms fields meadow tennessee texas countryside");
        WORDS.put("night", "night nights midnight moon moonlight stars starlight darkness");
        WORDS.put("city", "city cities downtown town skyline building buildings pavement subway");
        WORDS.put("rain", "rain rains raining rainy storm storms thunder raindrops");
        WORDS.put("water", "river rivers sea seas ocean oceans lake lakes waves beach beaches shore shoreline water");
        WORDS.put("mountain", "mountain mountains valley valleys hill hills peak peaks canyon canyons");
        WORDS.put("forest", "forest forests woods woodland tree trees pine pines");
        WORDS.put("desert", "desert deserts sand sands dunes dust dusty");
        WORDS.put("sunrise", "sunrise dawn morning daybreak");
        WORDS.put("sunset", "sunset sundown dusk evening twilight");
        WORDS.put("lonely", "lonely loneliness lonesome alone solitude missing heartbreak heartbroken");
        WORDS.put("warm", "summer summers sunshine sunlight golden warm warmth");
        WORDS.put("cold", "winter winters snow snowy ice icy freezing cold frost");
        WORDS.put("travel", "travel travels wander wandering journey leaving escape train flight");
        WORDS.put("wild", "wild freedom free running wilderness");
        WORDS.put("calm", "peace peaceful quiet calm rest breathe breathing stillness");
        WORDS.put("sky", "sky skies clouds cloud heaven space universe");
        WORDS.put("neon", "neon electric electricity");
    }
    private static volatile long unavailableUntil;
    private static volatile long backupUnavailableUntil;
    private static JSONObject lastFailure=new JSONObject();
    static final class ServiceFailure extends java.io.IOException{final int code;final long retryAfter;ServiceFailure(int code,long retryAfter){super("Lyrics service unavailable");this.code=code;this.retryAfter=retryAfter;}}
    interface Request{String get(String address)throws Exception;void pause(long millis)throws Exception;}
    private static final Request NETWORK=new Request(){public String get(String address)throws Exception{return fetch(address);}public void pause(long millis)throws Exception{Thread.sleep(millis);}};
    // One short server-error retry per lookup, shared by signature and search.
    // Rate limits and long Retry-After responses always return to the scheduler.
    static String fetchWithRetry(String address,Request request,int[] budget)throws Exception{
        try{return request.get(address);}catch(ServiceFailure e){
            if(budget[0]<=0||!retrySoon(e.code,e.retryAfter))throw e;
            budget[0]--;request.pause(e.retryAfter+(long)(Math.random()*250));return request.get(address);
        }
    }
    static boolean retrySoon(int code,long delay){return (code==500||code==502||code==503||code==504)&&delay>=0&&delay<=3000;}
    static long retryDelay(String header,int code,long now){
        if(header!=null)try{return Math.min(Math.max(0,Long.parseLong(header.trim())),Long.MAX_VALUE/2000)*1000;}catch(Exception ignored){
            try{java.text.SimpleDateFormat format=new java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz",Locale.US);format.setLenient(false);return Math.max(0,format.parse(header).getTime()-now);}catch(Exception ignoredDate){}
        }
        return code==429?60000:2000;
    }
    public static Result lookup(String song, String artist, long duration) {
        return lookup(song,artist,"",duration);
    }
    private static Result retry(String status,long delay){return new Result(new JSONObject(),status,"",new JSONObject(),true,Math.max(1000,delay));}
    private static Result retry(String status,long delay,JSONObject source){return new Result(new JSONObject(),status,"",source,true,Math.max(1000,delay));}
    public static synchronized Result lookup(String song,String artist,String album,long duration){
        Result primary=primary(song,artist,album,duration);
        if(!primary.retryable||!backupEligible(song,artist,album,primary.status))return primary;
        if(System.currentTimeMillis()<backupUnavailableUntil)return backupFailure(primary,"cooldown",0);
        try{
            String address="https://api.lyrics.ovh/v1/"+URLEncoder.encode(artist,"UTF-8").replace("+","%20")+"/"+URLEncoder.encode(song,"UTF-8").replace("+","%20");
            String body=fetch(address);if(body==null)return backupFailure(primary,"not found",404);
            Result result=backup(new JSONObject(body),song,artist,primary.status);return result.retryable?backupFailure(primary,"unusable text",200):result;
        }catch(Exception e){int code=e instanceof ServiceFailure?((ServiceFailure)e).code:0;long requested=e instanceof ServiceFailure?((ServiceFailure)e).retryAfter:0;backupUnavailableUntil=System.currentTimeMillis()+Math.max(code==429?60000:30000,requested);return backupFailure(primary,code==429?"rate limit":"unavailable",code);}
    }
    private static Result backupFailure(Result primary,String status,int code){try{JSONObject source=new JSONObject(primary.source.toString()).put("backupProvider","lyrics.ovh").put("backupStatus",status).put("backupHttpStatus",code);return new Result(primary.cues,primary.status,"",source,primary.retryable,primary.retryAfter);}catch(Exception ignored){return primary;}}
    static boolean backupEligible(String song,String artist,String album,String status){
        return !song.trim().isEmpty()&&!artist.trim().isEmpty()&&RecordingMatch.versions(song,album).isEmpty()&&!status.contains("Conflicting")&&!status.contains("Inconsistent")&&!status.contains("Instrumental");
    }
    static Result backup(JSONObject record,String song,String artist,String primaryStatus)throws Exception{
        String text=cleanLyrics(new JSONObject().put("plainLyrics",record.optString("lyrics")));
        if(text.length()<80||text.split("\\s+").length<12||text.toLowerCase(Locale.ROOT).matches("(?s)^(error|no lyrics found|lyrics not found).*"))return retry("Lyrics backup has no usable text",300000);
        if(text.length()>12000)text=text.substring(0,12000);
        JSONObject source=new JSONObject().put("provider","lyrics.ovh artist/title backup").put("url","https://lyricsovh.docs.apiary.io/").put("track",song).put("artist",artist).put("confidence","limited · recording version unverified").put("method","artist/title request only").put("durationMatched",false).put("albumMatched",false).put("maxLyricWeight",.15).put("primaryStatus",primaryStatus);
        return new Result(extract(text),"Lyrics backup · limited confidence · up to 15% influence",text,source,false,0);
    }
    private static Result primary(String song,String artist,String album,long duration){
        long now=System.currentTimeMillis();if(now<unavailableUntil)return retry("LRCLIB temporarily unavailable",unavailableUntil-now,lastFailure);
        try {
            String signature="track_name="+URLEncoder.encode(song,"UTF-8")+"&artist_name="+URLEncoder.encode(artist,"UTF-8");if(!album.isEmpty())signature+="&album_name="+URLEncoder.encode(album,"UTF-8");
            if(duration>=1000&&duration<=3600000)signature+="&duration="+Math.round(duration/1000.0);
            int[] retryBudget={1};String exact=fetchWithRetry("https://lrclib.net/api/get?"+signature,NETWORK,retryBudget);if(exact!=null){Result matched=select(new JSONArray().put(new JSONObject(exact)),song,artist,album,duration,"signature");if(!matched.retryable)return matched;}
            String query="track_name="+URLEncoder.encode(RecordingMatch.title(song),"UTF-8")+"&artist_name="+URLEncoder.encode(artist,"UTF-8");
            NETWORK.pause(300);String search=fetchWithRetry("https://lrclib.net/api/search?"+query,NETWORK,retryBudget);if(search==null)return retry("No confident lyrics match",300000);
            return select(new JSONArray(search),song,artist,album,duration,"validated search");
        }catch(Exception e){int code=e instanceof ServiceFailure?((ServiceFailure)e).code:0;long requested=e instanceof ServiceFailure?((ServiceFailure)e).retryAfter:0;long base=Math.max(code==429?60000:30000,requested),delay=base+(long)(Math.random()*(base/5));JSONObject failure=SourceHealth.failure("LRCLIB community lyrics",code,code==429?"rate_limit":code>=500?"server":code>0?"http":e.getClass().getSimpleName(),delay,now);if(code==429||code>=500){unavailableUntil=System.currentTimeMillis()+delay;lastFailure=failure;}return retry("LRCLIB lookup unavailable",delay,failure);}
    }
    private static String fetch(String address)throws Exception{
        HttpURLConnection c=(HttpURLConnection)new URL(address).openConnection();c.setConnectTimeout(6000);c.setReadTimeout(6000);c.setInstanceFollowRedirects(false);c.setRequestProperty("User-Agent","MusicScapes/5.14 (android-app://local.musicscapes.tv)");
        try{int code=c.getResponseCode();if(code==404)return null;if(code!=200)throw new ServiceFailure(code,retryDelay(c.getHeaderField("Retry-After"),code,System.currentTimeMillis()));
            try(InputStream in=c.getInputStream();ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1){if(out.size()+n>1500000)throw new java.io.IOException("Large lyrics response");out.write(b,0,n);}return out.toString("UTF-8");}
        }finally{c.disconnect();}
    }
    static String cleanLyrics(JSONObject record){
        String lyrics=record.isNull("plainLyrics")?"":record.optString("plainLyrics");if(lyrics.trim().isEmpty()&&!record.isNull("syncedLyrics"))lyrics=record.optString("syncedLyrics");
        return lyrics.replaceAll("\\[[^]\\r\\n]*\\]","").replaceAll("(?m)^\\s*(?:https?://\\S+|[0-9]+ Contributors?|You might also like|Embed)\\s*$","").trim();
    }
    static Result select(JSONArray rows,String song,String artist,String album,long duration,String method)throws Exception{
        JSONObject best=null;RecordingMatch.Fit bestFit=null;String selectedText="";boolean ambiguous=false;
        for(int i=0;i<rows.length();i++){JSONObject row=rows.getJSONObject(i);RecordingMatch.Fit fit=RecordingMatch.fit(song,artist,album,duration,row.optString("trackName"),row.optString("artistName"),row.optString("albumName"),row.optDouble("duration",0));if(fit==null)continue;
            String text=cleanLyrics(row),normalized=RecordingMatch.text(text);if(bestFit==null||fit.score>bestFit.score+.02){best=row;bestFit=fit;selectedText=text;ambiguous=false;}
            else if(Math.abs(fit.score-bestFit.score)<=.02){String previous=RecordingMatch.text(selectedText);if(row.optBoolean("instrumental")!=best.optBoolean("instrumental")||!previous.contains(normalized)&&!normalized.contains(previous))ambiguous=true;else if(text.length()>selectedText.length()){best=row;selectedText=text;bestFit=fit;}}
        }
        if(best==null||ambiguous)return retry(ambiguous?"Conflicting lyrics records · musical context only":"No confident lyrics match",300000);
        if(best.optBoolean("instrumental")&&!selectedText.isEmpty())return retry("Inconsistent instrumental record · musical context only",300000);
        long id=best.optLong("id");JSONObject source=new JSONObject().put("provider","LRCLIB community lyrics").put("id",id).put("url",id>0?"https://lrclib.net/api/get/"+id:"https://lrclib.net/docs").put("track",best.optString("trackName")).put("artist",best.optString("artistName")).put("album",best.optString("albumName")).put("confidence",bestFit.confidence).put("durationMatched",bestFit.duration).put("albumMatched",bestFit.album).put("method",method).put("instrumental",best.optBoolean("instrumental"));
        if(best.optBoolean("instrumental"))return new Result(new JSONObject(),"Instrumental · musical context only","",source,false,0);
        if(selectedText.isEmpty())return retry("Lyrics text unavailable",300000);
        String lyrics=selectedText.length()>12000?selectedText.substring(0,12000):selectedText;JSONObject cues=extract(lyrics);
        return new Result(cues,"LRCLIB lyrics · "+bestFit.confidence+" recording match",lyrics,source,false,0);
    }
    static JSONObject extract(String lyrics) throws Exception {
        Set<String> tokens=new HashSet<>(Arrays.asList(lyrics.toLowerCase(Locale.ROOT).split("[^\\p{L}]+")));
        List<Map.Entry<String,Integer>> hits=new ArrayList<>();
        int count=0;
        for(Map.Entry<String,String> entry:WORDS.entrySet()) {
            int matched=0;
            for(String word:entry.getValue().split(" "))if(tokens.contains(word))matched++;
            if(matched>0){hits.add(new java.util.AbstractMap.SimpleEntry<>(entry.getKey(),matched));count+=matched;}
        }
        JSONObject result=new JSONObject();
        if(count<2)return result;
        hits.sort((a,b)->Integer.compare(b.getValue(),a.getValue()));
        for(int i=0;i<Math.min(4,hits.size());i++)result.put(hits.get(i).getKey(),Math.min(1.0,hits.get(i).getValue()/3.0));
        return result;
    }
}
