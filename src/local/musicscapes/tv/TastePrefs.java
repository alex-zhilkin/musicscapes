package local.musicscapes.tv;
import org.json.*;
import java.util.Iterator;
/** Explicit image preferences, kept independently of the diagnostic history. */
public final class TastePrefs {
    public static JSONObject clean(JSONObject input)throws Exception{
        JSONObject votes=new JSONObject(),raw=input.optJSONObject("votes");int count=0;if(raw!=null){Iterator<String> keys=raw.keys();while(keys.hasNext()&&count<200){String key=keys.next();JSONObject v=raw.optJSONObject(key);if(!key.matches("[a-z0-9]{1,80}")||v==null||Math.abs(v.optInt("value"))!=1)continue;String kind=v.optString("kind");if(!kind.matches("photo|art|space|artist"))continue;JSONArray tags=new JSONArray(),a=v.optJSONArray("tags");if(a!=null)for(int i=0;i<Math.min(20,a.length());i++)if(a.optString(i).matches("[a-z]{1,32}"))tags.put(a.optString(i));votes.put(key,new JSONObject().put("value",v.getInt("value")).put("kind",kind).put("tags",tags).put("temperature",Math.max(-1,Math.min(1,v.optDouble("temperature",0)))).put("brightness",Math.max(0,Math.min(1,v.optDouble("brightness",.4)))));count++;}}
        JSONArray hidden=new JSONArray(),a=input.optJSONArray("hidden");java.util.Set<String> unique=new java.util.LinkedHashSet<>();if(a!=null)for(int i=0;i<a.length()&&unique.size()<2000;i++){String key=a.optString(i);if(key.matches("[a-z0-9]{1,80}"))unique.add(key);}for(String key:unique)hidden.put(key);return new JSONObject().put("votes",votes).put("hidden",hidden);
    }
}
