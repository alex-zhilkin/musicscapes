package local.musicscapes.tv;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import java.util.ArrayDeque;

/** Image keys only; persists across app/screensaver sessions without recording listening history. */
public final class ImageHistory {
    private static ImageHistory instance;
    public static synchronized ImageHistory get(Context c){if(instance==null)instance=new ImageHistory(c.getApplicationContext());return instance;}
    private final SharedPreferences prefs;
    private final ArrayDeque<String> keys=new ArrayDeque<>();
    private ImageHistory(Context c){prefs=c.getSharedPreferences("image-history",Context.MODE_PRIVATE);try{JSONArray saved=new JSONArray(prefs.getString("recent","[]"));for(int i=Math.max(0,saved.length()-2000);i<saved.length();i++){String key=saved.optString(i);if(key.matches("[a-z0-9]{1,80}"))keys.add(key);}}catch(Exception ignored){}}
    public synchronized JSONArray snapshot(){JSONArray out=new JSONArray();for(String key:keys)out.put(key);return out;}
    public synchronized void record(String key){keys.add(key);while(keys.size()>2000)keys.removeFirst();prefs.edit().putString("recent",snapshot().toString()).apply();}
}
