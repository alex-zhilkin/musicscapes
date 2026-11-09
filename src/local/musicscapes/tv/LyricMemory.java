package local.musicscapes.tv;

import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** Bounded derived signals only. No lyric text or unhashed recording keys on disk. */
public final class LyricMemory {
    private static final Object LOCK=new Object();
    private static long epoch;
    static final long TTL=30L*24*60*60*1000;
    private final File file;
    public LyricMemory(File directory){file=new File(directory,"lyric-signals-v1.json");}
    public static final class Entry {final LyricsSignals.Result result;final JSONArray vector;Entry(LyricsSignals.Result r,JSONArray v){result=r;vector=v;}}
    public static long epoch(){synchronized(LOCK){return epoch;}}
    public Entry get(String key,long now){synchronized(LOCK){try{
        JSONObject row=read().optJSONObject(hash(key));if(row==null)return null;
        long age=now-row.optLong("time"),ttl=row.optJSONObject("source").optString("provider").startsWith("lyrics.ovh")?6L*60*60*1000:TTL;
        if(age<0||age>ttl)return null;
        JSONObject source=new JSONObject(row.getJSONObject("source").toString()).put("cached",true).put("observedAt",row.optLong("time"));
        return new Entry(new LyricsSignals.Result(row.getJSONObject("cues"),row.optString("status")+" · saved signals","",source,false,0),row.getJSONArray("vector"));
    }catch(Exception ignored){return null;}}}
    public void put(String key,LyricsSignals.Result result,JSONArray vector,long token,long now){synchronized(LOCK){try{
        if(epoch!=token||result.retryable||result.cues.length()==0&&vector.length()!=384)return;
        JSONObject rows=read(),safeSource=new JSONObject();for(String field:new String[]{"provider","id","url","track","artist","album","confidence","durationMatched","albumMatched","method","instrumental","maxLyricWeight","primaryStatus"})if(result.source.has(field))safeSource.put(field,result.source.get(field));
        JSONObject cues=new JSONObject();Iterator<String> cueKeys=result.cues.keys();while(cueKeys.hasNext()){String field=cueKeys.next();double v=result.cues.optDouble(field);if(field.matches("[a-z]{1,24}")&&Double.isFinite(v)&&v>0)cues.put(field,Math.min(1,v));}
        JSONArray safeVector=new JSONArray();if(vector.length()==384){for(int i=0;i<384;i++){double v=vector.optDouble(i,Double.NaN);if(!Double.isFinite(v)||Math.abs(v)>1)return;safeVector.put(v);}}
        rows.put(hash(key),new JSONObject().put("time",now).put("source",safeSource).put("status",result.status).put("cues",cues).put("vector",safeVector));
        List<String> keys=new ArrayList<>();Iterator<String> rowKeys=rows.keys();while(rowKeys.hasNext())keys.add(rowKeys.next());keys.sort((a,b)->Long.compare(rows.optJSONObject(b).optLong("time"),rows.optJSONObject(a).optLong("time")));
        for(int i=0;i<keys.size();i++)if(i>=200||now-rows.getJSONObject(keys.get(i)).optLong("time")>TTL)rows.remove(keys.get(i));
        File parent=file.getParentFile();if(!parent.isDirectory()&&!parent.mkdirs())return;File pending=new File(file.getPath()+".part");try(FileOutputStream out=new FileOutputStream(pending)){out.write(rows.toString().getBytes(StandardCharsets.UTF_8));}try{Files.move(pending.toPath(),file.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}catch(AtomicMoveNotSupportedException e){Files.move(pending.toPath(),file.toPath(),StandardCopyOption.REPLACE_EXISTING);}
    }catch(Exception ignored){}}}
    public void clear()throws IOException{synchronized(LOCK){epoch++;if(file.exists()&&!file.delete())throw new IOException("Cannot clear lyric signals");File pending=new File(file.getPath()+".part");if(pending.exists()&&!pending.delete())throw new IOException("Cannot clear pending lyric signals");}}
    private JSONObject read()throws Exception{if(!file.isFile()||file.length()>2500000)return new JSONObject();try(InputStream in=new FileInputStream(file);ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1){if(out.size()+n>2500000)return new JSONObject();out.write(b,0,n);}return new JSONObject(out.toString("UTF-8"));}}
    private static String hash(String key)throws Exception{StringBuilder s=new StringBuilder();for(byte b:MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8)))s.append(String.format(Locale.ROOT,"%02x",b&255));return s.toString();}
}
