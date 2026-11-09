package local.musicscapes.tv;

import org.json.*;
import java.io.*;
import java.net.*;

/** Official Apple recording catalogue, with album, version and duration validation. */
public final class TrackMetadata {
    public static final class Result {
        final String genre,status;final JSONObject source;final boolean cacheable;
        Result(String g,String s,JSONObject p,boolean c){genre=g;status=s;source=p;cacheable=c;}
    }
    private static long nextRequest,unavailableUntil;
    private static JSONObject lastFailure=new JSONObject();
    public static synchronized Result lookup(String title,String artist,String album,long duration){
        HttpURLConnection c=null;long started=System.currentTimeMillis();
        try{
            long now=System.currentTimeMillis();if(now<unavailableUntil)return new Result("","Recording metadata temporarily unavailable",lastFailure,false);
            long wait=nextRequest-now;if(wait>0)Thread.sleep(wait);nextRequest=System.currentTimeMillis()+3500;
            String term=URLEncoder.encode(artist+" "+RecordingMatch.title(title),"UTF-8");
            c=(HttpURLConnection)new URL("https://itunes.apple.com/search?media=music&entity=song&limit=20&term="+term).openConnection();c.setConnectTimeout(6000);c.setReadTimeout(6000);c.setInstanceFollowRedirects(false);c.setRequestProperty("User-Agent","MusicScapes/4.1 (personal Android TV app)");
            int code=c.getResponseCode();if(code==429||code>=500){unavailableUntil=System.currentTimeMillis()+120000;lastFailure=SourceHealth.failure("Apple iTunes catalogue",code,code==429?"rate_limit":"server",120000,started);return new Result("","Recording metadata temporarily unavailable",lastFailure,false);}if(code!=200)return new Result("","Recording metadata unavailable",SourceHealth.failure("Apple iTunes catalogue",code,"http",120000,started),false);
            try(InputStream in=c.getInputStream();ByteArrayOutputStream bytes=new ByteArrayOutputStream()){byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1){if(bytes.size()+n>1500000)throw new IOException("Large catalogue response");bytes.write(b,0,n);}return select(new JSONObject(bytes.toString("UTF-8")).getJSONArray("results"),title,artist,album,duration);}
        }catch(InterruptedException e){Thread.currentThread().interrupt();return new Result("","Metadata lookup cancelled",new JSONObject(),false);}
        catch(Exception e){return new Result("","Recording metadata unavailable",SourceHealth.failure("Apple iTunes catalogue",0,e.getClass().getSimpleName(),120000,started),false);}finally{if(c!=null)c.disconnect();}
    }
    static Result select(JSONArray rows,String title,String artist,String album,long duration)throws Exception{
        JSONObject best=null;RecordingMatch.Fit fit=null;String genre="";boolean conflict=false;
        for(int i=0;i<rows.length();i++){JSONObject row=rows.getJSONObject(i);if(!row.optString("kind","song").equals("song"))continue;
            RecordingMatch.Fit candidate=RecordingMatch.fit(title,artist,album,duration,row.optString("trackName"),row.optString("artistName"),row.optString("collectionName"),row.optDouble("trackTimeMillis",0)/1000.0);if(candidate==null)continue;
            String g=row.optString("primaryGenreName");if(g.isEmpty())continue;
            if(fit==null||candidate.score>fit.score+.02){best=row;fit=candidate;genre=g;conflict=false;}
            else if(Math.abs(candidate.score-fit.score)<=.02&&!genre.equalsIgnoreCase(g))conflict=true;
        }
        if(best==null||conflict)return new Result("",conflict?"Recording genre is ambiguous · using artist evidence":"No confident recording match",new JSONObject(),true);
        JSONObject source=new JSONObject().put("provider","Apple iTunes catalogue").put("id",best.optString("trackId")).put("artistId",best.optString("artistId")).put("url",best.optString("trackViewUrl")).put("track",best.optString("trackName")).put("artist",best.optString("artistName")).put("album",best.optString("collectionName")).put("confidence",fit.confidence).put("durationMatched",fit.duration).put("albumMatched",fit.album);
        return new Result(genre,"",source,true);
    }
}
