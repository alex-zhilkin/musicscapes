package local.musicscapes.tv;
import org.json.*;
import java.io.*;
import java.net.*;
import java.util.*;
/** Resolve a same-name performer using the recording rather than a name guess. */
public final class ArtistRecording {
    static JSONObject read(String path)throws Exception{HttpURLConnection c=(HttpURLConnection)new URL("https://musicbrainz.org/ws/2/"+path).openConnection();c.setConnectTimeout(6000);c.setReadTimeout(6000);c.setInstanceFollowRedirects(false);c.setRequestProperty("User-Agent","MusicScapes/5.0 (android-app://local.musicscapes.tv)");try{if(c.getResponseCode()!=200)throw new IOException("Recording source unavailable");try(InputStream in=c.getInputStream();ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1){if(out.size()+n>1500000)throw new IOException("Large recording response");out.write(b,0,n);}return new JSONObject(out.toString("UTF-8"));}}finally{c.disconnect();}}
    static JSONObject find(String title,String artist,String album,long duration)throws Exception{String q="recording:\""+RecordingMatch.title(title).replace('"',' ').replace('\\',' ')+"\" AND artistname:\""+artist.replace('"',' ').replace('\\',' ')+"\"";return select(read("recording/?fmt=json&limit=20&query="+URLEncoder.encode(q,"UTF-8")).optJSONArray("recordings"),title,artist,album,duration);}
    static JSONObject select(JSONArray rows,String title,String artist,String album,long duration)throws Exception{
        JSONObject best=null;double score=-1;Set<String> identities=new HashSet<>();if(rows==null||duration<=0)return new JSONObject();
        for(int i=0;i<rows.length();i++){JSONObject r=rows.getJSONObject(i);if(r.optInt("score",100)<95)continue;JSONArray credits=r.optJSONArray("artist-credit");if(credits==null)continue;StringBuilder names=new StringBuilder();JSONObject named=null;int matches=0;for(int j=0;j<credits.length();j++){JSONObject credit=credits.optJSONObject(j);if(credit==null)continue;JSONObject a=credit.optJSONObject("artist");if(a==null)continue;names.append(credit.optString("name",a.optString("name"))).append(credit.optString("joinphrase"));if(Matching.normalized(artist).equals(Matching.normalized(a.optString("name")))){named=a;matches++;}}if(matches!=1||named==null||!named.optString("id").matches("[a-f0-9-]{36}"))continue;
            JSONArray releases=r.optJSONArray("releases");RecordingMatch.Fit fit=null;for(int j=0;j<Math.max(1,releases==null?0:releases.length());j++){String release=releases==null||releases.length()==0?"":releases.getJSONObject(j).optString("title");RecordingMatch.Fit f=RecordingMatch.fit(title,artist,album,duration,r.optString("title"),names.toString(),release,r.optDouble("length",0)/1000);if(f!=null&&(fit==null||f.score>fit.score))fit=f;}if(fit==null)continue;
            if(fit.score>score+.02){score=fit.score;identities.clear();best=new JSONObject(named.toString()).put("recordingId",r.optString("id")).put("recordingConfidence",fit.confidence);}if(Math.abs(fit.score-score)<=.02)identities.add(named.getString("id"));
        }return identities.size()==1&&best!=null?best:new JSONObject();
    }
}
