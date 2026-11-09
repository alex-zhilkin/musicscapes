package local.musicscapes.tv;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;

/** Community genre evidence only; ambiguous same-name artists are deliberately rejected. */
public final class ArtistStyles {
    public static final class Result {
        final JSONArray genres;final String status;final JSONObject identity;
        Result(JSONArray genres,String status){this(genres,status,new JSONObject());}
        Result(JSONArray genres,String status,JSONObject identity){this.genres=genres;this.status=status;this.identity=identity;}
    }
    private static long nextRequest;
    public static Result lookup(String artist){return lookup(artist,"","",0);}
    public static synchronized Result lookup(String artist,String title,String album,long duration){
        HttpURLConnection connection=null;
        try{
            long delay=nextRequest-System.currentTimeMillis();
            if(delay>1500)return fallback(artist);
            if(delay>0)Thread.sleep(delay);
            nextRequest=System.currentTimeMillis()+1200;
            String safe=artist.replace('"',' ').replace('\\',' ').trim();
            String query=URLEncoder.encode("artist:\""+safe+"\"","UTF-8");
            connection=(HttpURLConnection)new URL("https://musicbrainz.org/ws/2/artist/?fmt=json&limit=5&query="+query).openConnection();
            connection.setConnectTimeout(6000);connection.setReadTimeout(6000);
            connection.setRequestProperty("User-Agent","MusicScapes/2.0 (android-app://local.musicscapes.tv)");
            int response=connection.getResponseCode();
            if(response==503||response==429){long seconds=60;try{seconds=Long.parseLong(connection.getHeaderField("Retry-After"));}catch(Exception ignored){}nextRequest=System.currentTimeMillis()+Math.max(5,Math.min(seconds,3600))*1000;return fallback(artist);}
            if(response!=200)return fallback(artist);
            JSONArray artists;
            try(InputStream input=connection.getInputStream();ByteArrayOutputStream bytes=new ByteArrayOutputStream()){
                byte[] block=new byte[8192];int n;while((n=input.read(block))!=-1){bytes.write(block,0,n);if(bytes.size()>1000000)throw new Exception("Large artist response");}
                artists=new JSONObject(bytes.toString("UTF-8")).optJSONArray("artists");
            }
            JSONObject selected=null;int matches=0;
            if(artists!=null)for(int i=0;i<artists.length();i++){
                JSONObject candidate=artists.getJSONObject(i);
                if(candidate.optInt("score")>=95&&Matching.normalized(artist).equals(Matching.normalized(candidate.optString("name")))){selected=candidate;matches++;}
            }
            JSONObject recording=new JSONObject();
            if((matches!=1||Matching.normalized(artist).length()<6)&&!title.isEmpty()&&duration>0){
                long wait=nextRequest-System.currentTimeMillis();if(wait>0)Thread.sleep(wait);nextRequest=System.currentTimeMillis()+1200;
                recording=ArtistRecording.find(title,artist,album,duration);if(recording.length()==0)return new Result(new JSONArray(),"Recording artist is uncertain · using song evidence");
                wait=nextRequest-System.currentTimeMillis();if(wait>0)Thread.sleep(wait);nextRequest=System.currentTimeMillis()+1200;
                selected=ArtistRecording.read("artist/"+recording.getString("id")+"?fmt=json&inc=tags");
            }else if(matches!=1)return matches==0?fallback(artist):new Result(new JSONArray(),"Artist name is ambiguous · using song genre");
            JSONArray tags=selected.optJSONArray("tags"),output=new JSONArray();List<JSONObject> ordered=new ArrayList<>();
            if(tags!=null)for(int i=0;i<tags.length();i++){JSONObject tag=tags.getJSONObject(i);if(tag.optInt("count")>0)ordered.add(tag);}
            ordered.sort((a,b)->Integer.compare(b.optInt("count"),a.optInt("count")));
            for(int i=0;i<Math.min(24,ordered.size());i++){JSONObject clean=new JSONObject();clean.put("name",ordered.get(i).optString("name"));clean.put("count",ordered.get(i).optInt("count"));output.put(clean);}
            long identityDelay=nextRequest-System.currentTimeMillis();if(identityDelay>0)Thread.sleep(identityDelay);nextRequest=System.currentTimeMillis()+1200;
            JSONObject identity=ArtistIdentity.lookup(selected.optString("id"),selected.optString("name",artist));
            if(output.length()==0&&!identity.optString("mbid").isEmpty()){
                JSONObject alternative=ArtistIdentity.find(identity.optString("name",artist));
                if(identity.optString("mbid").equals(alternative.optString("mbid"))&&alternative.optJSONArray("genres")!=null&&alternative.optJSONArray("genres").length()>0){output=alternative.optJSONArray("genres");identity.put("genreProvider","Wikidata CC0");}
            }
            if(recording.length()>0)identity.put("mbid",recording.getString("id")).put("recordingId",recording.optString("recordingId")).put("recordingConfidence",recording.optString("recordingConfidence"));
            return new Result(output,identity.has("genreProvider")?"Wikidata genre fallback · verified artist":output.length()>0?(recording.length()>0?"MusicBrainz recording-confirmed artist tags":"MusicBrainz artist tags"):"Artist has no detailed genre tags",identity);
        }catch(InterruptedException e){Thread.currentThread().interrupt();return new Result(new JSONArray(),"Artist lookup cancelled");}
        catch(Exception e){return fallback(artist);}
        finally{if(connection!=null)connection.disconnect();}
    }
    private static Result fallback(String artist){JSONObject identity=ArtistIdentity.find(artist);JSONArray tags=identity.optJSONArray("genres");return new Result(tags==null?new JSONArray():tags,identity.length()>0?(tags!=null&&tags.length()>0?"Wikidata artist identity and genre evidence":"Artist identity found · genre tags unavailable"):"Artist tags unavailable",identity);}
}
