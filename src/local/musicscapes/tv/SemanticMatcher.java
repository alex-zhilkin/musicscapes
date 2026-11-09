package local.musicscapes.tv;

import android.content.Context;
import android.os.Process;
import android.util.Log;
import org.json.JSONArray;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** One small process-wide encoder, with inference away from the photo render thread. */
public final class SemanticMatcher {
    public interface Listener{void result(JSONArray vector,String status);}
    private static final ExecutorService jobs=Executors.newSingleThreadExecutor();
    private static SemanticEncoder encoder;
    private static boolean unavailable;
    private static final java.util.concurrent.atomic.AtomicInteger visibleViews=new java.util.concurrent.atomic.AtomicInteger();
    public static void visible(){visibleViews.incrementAndGet();}
    public static void hidden(){
        visibleViews.updateAndGet(n->Math.max(0,n-1));
        jobs.execute(()->{if(visibleViews.get()==0&&encoder!=null){try{encoder.close();}catch(Exception ignored){}encoder=null;Log.i("MusicScapes","Released local model while no screens are visible");}});
    }
    public static void describe(Context source,String text,Listener listener){run(source,text,listener,false);}
    public static void submit(Context source,String text,Listener listener){
        run(source,text,listener,true);
    }
    private static void run(Context source,String text,Listener listener,boolean lyrics){
        final Context context=source.getApplicationContext();
        jobs.execute(()->{
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
            if(visibleViews.get()==0){listener.result(new JSONArray(),"ML paused while pictures are hidden");return;}
            if(unavailable){listener.result(new JSONArray(),"ML unavailable · using visual tags");return;}
            try{
                if(lyrics&&!likelyEnglish(text)){listener.result(new JSONArray(),"Lyrics language uncertain · using visual cues");return;}
                if(encoder==null){
                    File model=new File(context.getNoBackupFilesDir(),"minilm-afdb6f1a.onnx");
                    if(!model.exists()||model.length()!=22972370){
                        File pending=new File(model.getPath()+".part");
                        try(InputStream input=context.getAssets().open("models/minilm.onnx");FileOutputStream output=new FileOutputStream(pending)){
                            byte[] block=new byte[65536];int n;while((n=input.read(block))!=-1)output.write(block,0,n);
                        }
                        if(model.exists()&&!model.delete())throw new Exception("Cannot replace incomplete model");
                        if(pending.length()!=22972370||!pending.renameTo(model))throw new Exception("Model installation failed");
                    }
                    encoder=new SemanticEncoder(model.getAbsolutePath(),context.getAssets().open("models/vocab.txt"));
                    Log.i("MusicScapes","Local semantic model ready");
                }
                long start=System.currentTimeMillis();
                List<String> chunks=lyrics?chunks(text):java.util.Collections.singletonList(text);float[] mean=new float[384];
                for(String chunk:chunks){if(visibleViews.get()==0){listener.result(new JSONArray(),"ML paused while pictures are hidden");return;}float[] vector=encoder.encode(chunk);for(int i=0;i<mean.length;i++)mean[i]+=vector[i]/chunks.size();}
                SemanticEncoder.normalize(mean);JSONArray output=new JSONArray();for(float x:mean)output.put((double)x);
                Log.i("MusicScapes","Local "+(lyrics?"lyric":"image description")+" embedding computed in "+(System.currentTimeMillis()-start)+"ms");
                listener.result(output,lyrics?"Local ML lyric matching":"Local ML image description");
            }catch(Throwable e){
                unavailable=true;
                if(encoder!=null){try{encoder.close();}catch(Exception ignored){}encoder=null;}
                Log.w("MusicScapes","Semantic model unavailable: "+e.getClass().getSimpleName());
                listener.result(new JSONArray(),"ML unavailable · using visual tags");
            }
        });
    }
    static List<String> chunks(String text){
        Set<String> lines=new LinkedHashSet<>();for(String line:text.split("\\r?\\n")){String normalized=line.trim();if(!normalized.isEmpty())lines.add(normalized);}
        String[] words=String.join(" ",lines).split("\\s+");List<String> output=new ArrayList<>();
        int count=Math.min(3,Math.max(1,(words.length+69)/70));
        for(int i=0;i<count;i++){
            int start=count==1?0:Math.round((words.length-Math.min(words.length,70))*i/(float)(count-1));
            output.add(String.join(" ",Arrays.copyOfRange(words,start,Math.min(words.length,start+70))));
        }
        return output;
    }
    static boolean likelyEnglish(String text){
        Set<String> markers=new java.util.HashSet<>(Arrays.asList("the","and","you","your","i","my","with","that","this","love","heart","where","when","we","was","were","is","are","in","to","of","me"));
        int hits=0,total=0;for(String word:text.toLowerCase(Locale.ROOT).split("[^\\p{L}]+")){if(word.isEmpty())continue;total++;if(markers.contains(word))hits++;}
        return total>=8&&hits>=3&&hits/(double)total>=.1;
    }
}
