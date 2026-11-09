package local.musicscapes.tv;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;
import org.json.*;
import java.io.File;
import java.util.concurrent.*;

/** The only journal writer: disk work and export never block photo rendering. */
public final class ReviewLog {
    public interface Listener{void result(JSONObject value);}
    public interface BatchListener{void result(JSONArray value);}
    private static ReviewLog instance;
    public static synchronized ReviewLog get(Context context){if(instance==null)instance=new ReviewLog(context.getApplicationContext());return instance;}
    private final Context context;private final SharedPreferences prefs;
    private final ThreadPoolExecutor jobs=new ThreadPoolExecutor(1,1,0,TimeUnit.SECONDS,new ArrayBlockingQueue<Runnable>(256),new ThreadPoolExecutor.AbortPolicy());
    private final java.util.concurrent.atomic.AtomicInteger dropped=new java.util.concurrent.atomic.AtomicInteger();
    private ReviewStore store;
    private ReviewLog(Context c){context=c;prefs=c.getSharedPreferences("review",Context.MODE_PRIVATE);}
    public boolean enabled(){return prefs.getBoolean("enabled",!BuildProfile.STORE);}
    private ReviewStore store()throws Exception{if(store==null)store=new ReviewStore(new File(context.getNoBackupFilesDir(),"review"),System.currentTimeMillis());return store;}
    public void record(JSONObject event){if(!enabled())return;try{event.put("appVersion","5.14");}catch(Exception ignored){}final String copy=event.toString();try{jobs.execute(()->{try{if(!store().append(new JSONObject(copy),System.currentTimeMillis()))dropped.incrementAndGet();else DevLogClient.get(context).available();}catch(Exception e){dropped.incrementAndGet();Log.w("MusicScapes","Review record unavailable: "+e.getClass().getSimpleName());}});}catch(RejectedExecutionException e){dropped.incrementAndGet();}}
    public void batch(String ack,BatchListener listener){try{jobs.execute(()->{try{listener.result(store().batchAfter(ack,20,System.currentTimeMillis()));}catch(Exception e){listener.result(null);}});}catch(RejectedExecutionException e){listener.result(null);}}
    public void record(String kind,JSONObject data){try{data.put("kind",kind);record(data);}catch(Exception ignored){}}
    private File exported()throws Exception{File base=context.getExternalFilesDir("review");if(base==null)throw new java.io.IOException("TV export storage unavailable");return new File(base,"MusicScapes-review.jsonl");}
    public void request(String command,Listener listener){
        if(command.equals("toggle")){prefs.edit().putBoolean("enabled",!enabled()).apply();DevLogClient.get(context).reset();}
        if(command.equals("dev"))DevLogClient.get(context).toggle();
        try{jobs.execute(()->{try{String status="";if(command.equals("clear")){store().clear();new LyricMemory(context.getFilesDir()).clear();dropped.set(0);DevLogClient.get(context).reset();File f=exported();if(f.exists()&&!f.delete())throw new java.io.IOException("Old export could not be removed");File p=new File(f.getPath()+".part");if(p.exists())p.delete();status="TV review history and saved lyric signals cleared. Computer copies remain.";}
            if(command.equals("export")){store().export(exported(),System.currentTimeMillis());status="Review saved on this TV. Ask me to collect it.";}
            JSONObject value=store().summary(System.currentTimeMillis()).put("enabled",enabled()).put("droppedEvents",dropped.get()).put("cleared",command.equals("clear")).put("status",status);JSONObject dev=DevLogClient.get(context).state();java.util.Iterator<String> keys=dev.keys();while(keys.hasNext()){String key=keys.next();value.put(key,dev.get(key));}listener.result(value);
        }catch(Exception e){failure(listener,"Review storage unavailable. Please try again.");}});}catch(RejectedExecutionException e){failure(listener,"Review is busy. Please try again.");}
    }
    private void failure(Listener listener,String message){try{listener.result(new JSONObject().put("enabled",enabled()).put("error",message));}catch(Exception ignored){}}
}
