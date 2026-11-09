package local.musicscapes.tv;

import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** A bounded, single-writer journal. No lyrics, vectors or arbitrary bridge fields. */
public final class ReviewStore {
    static final int MAX_EVENTS=5000,MAX_BYTES=8*1024*1024,MAX_EVENT_BYTES=8192;
    static final long RETENTION=30L*24*60*60*1000;
    private final File file;
    private final ArrayDeque<Entry> entries=new ArrayDeque<>();
    private int bytes;
    private boolean rewriteRequired;
    private static final class Entry{final long time;final byte[] line;final String id;Entry(long t,byte[] l,String id){time=t;line=l;this.id=id;}}
    public ReviewStore(File directory,long now)throws Exception{
        if(!directory.isDirectory()&&!directory.mkdirs())throw new IOException("Review directory unavailable");file=new File(directory,"events.jsonl");
        if(file.isFile())try(BufferedReader in=new BufferedReader(new InputStreamReader(new FileInputStream(file),StandardCharsets.UTF_8))){String line;while((line=in.readLine())!=null){if(line.length()>MAX_EVENT_BYTES)continue;try{JSONObject v=new JSONObject(line);long time=v.optLong("time");byte[] raw=(line+"\n").getBytes(StandardCharsets.UTF_8);if(time>=now-RETENTION&&raw.length<=MAX_EVENT_BYTES){entries.add(new Entry(time,raw,v.optString("eventId")));bytes+=raw.length;trim(now);}}catch(Exception ignored){}}}
        trim(now);rewrite();
    }
    static JSONObject song(JSONObject source)throws Exception{return strings(source,"title,artist,album",200).put("durationMs",Math.max(0,source.optLong("durationMs")));}
    private static JSONObject strings(JSONObject source,String fields,int limit)throws Exception{JSONObject out=new JSONObject();if(source==null)return out;for(String key:fields.split(",")){Object value=source.opt(key);if(value instanceof String||value instanceof Number){String s=String.valueOf(value).replaceAll("[\\p{Cntrl}&&[^\\t]]"," ");out.put(key,s.length()>limit?s.substring(0,limit):s);}}return out;}
    private static void numbers(JSONObject from,JSONObject to,String fields)throws Exception{if(from==null)return;for(String key:fields.split(",")){Object v=from.opt(key);if(v instanceof Boolean)to.put(key,v);else if(v instanceof Number&&Double.isFinite(((Number)v).doubleValue()))to.put(key,v);}}
    private static JSONArray list(JSONArray a,int count,int length){JSONArray out=new JSONArray();if(a!=null)for(int i=0;i<Math.min(count,a.length());i++){Object v=a.opt(i);if(v instanceof String){String s=(String)v;out.put(s.length()>length?s.substring(0,length):s);}}return out;}
    private static JSONObject source(JSONObject v)throws Exception{JSONObject out=strings(v,"provider,id,artistId,track,artist,album,confidence,method,errorKind,primaryStatus,backupProvider,backupStatus",200);if(v!=null){out.put("url",strings(v,"url",512).optString("url"));numbers(v,out,"albumMatched,durationMatched,instrumental,httpStatus,retryAfterMs,latencyMs,maxLyricWeight,cached,observedAt,backupHttpStatus");}return out;}
    private static JSONObject visual(JSONObject v)throws Exception{JSONObject out=new JSONObject();if(v==null)return out;numbers(v,out,"brightness,temperature,saturation,contrast,energy");JSONArray p=v.optJSONArray("palette"),bins=new JSONArray();if(p!=null&&p.length()==8){boolean valid=true;for(int i=0;i<8;i++){Object n=p.opt(i);if(!(n instanceof Number)||!Double.isFinite(((Number)n).doubleValue())||((Number)n).doubleValue()<0||((Number)n).doubleValue()>1){valid=false;break;}bins.put(n);}if(valid)out.put("palette",bins);}return out;}
    private static JSONObject transition(JSONObject v)throws Exception{JSONObject out=strings(v,"previousKey,measuredOn",80);numbers(v,out,"colourKnown,colourReset,lastPaletteFit,runningPaletteFit,paletteFit,brightnessDelta,staleSelection");return out;}
    private static JSONObject image(JSONObject v)throws Exception{JSONObject out=strings(v,"key,title,displayTitle,creator,creatorKey,seriesKey,kind,cluster,artistId,credit,source,license",220);if(v!=null){out.put("sourceUrl",strings(v,"sourceUrl",512).optString("sourceUrl"));out.put("tags",list(v.optJSONArray("tags"),32,48));out.put("placeKeys",list(v.optJSONArray("placeKeys"),6,80));numbers(v,out,"width,height");if(v.optJSONObject("visual")!=null)out.put("visual",visual(v.optJSONObject("visual")));}return out;}
    private static JSONObject decision(JSONObject v)throws Exception{JSONObject out=strings(v,"selectionId,reason,profile,world,evidence,lyricMethod,selectionReason",160);if(v!=null){numbers(v,out,"styleWeight,lyricWeight,styleFit,lyricFit,score,utility,poolSize,freshCount,rank,previousGap,repeat,bestScore,quality,taste,paletteFit,scenePenalty,brightnessPenalty,holdSeconds,evening,creatorRepeat,placeRepeat,seriesRepeat,sourcePenalty,compositionPenalty,lastPaletteFit,runningPaletteFit,colourKnown,colourReset,brightnessDelta,bestRelevantScore,relevanceTolerance,relevanceLoss");out.put("directionSources",list(v.optJSONArray("directionSources"),3,512));JSONArray choices=new JSONArray(),a=v.optJSONArray("alternatives");if(a!=null)for(int i=0;i<Math.min(5,a.length());i++){JSONObject r=a.optJSONObject(i);if(r!=null){JSONObject c=strings(r,"key,title",160);numbers(r,c,"score,utility,duplicate");choices.put(c);}}out.put("alternatives",choices);}return out;}
    static JSONObject sanitize(JSONObject in)throws Exception{
        String kind=in.optString("kind");if(!kind.matches("session|track_state|image_selected|image_loaded|image_failed|feedback|discovery"))return null;
        JSONObject out=new JSONObject().put("schema",1).put("kind",kind);
        for(String key:new String[]{"sessionId","mode","appVersion","status","outcome","genre","genreStatus","artistStatus","lyricsStatus","mlStatus","musicState","lyricsState","rating"})if(in.opt(key) instanceof String)out.put(key,strings(in,key,240).getString(key));
        numbers(in,out,"playing,cached,hasLyricVector,latencyMs,count,examined,rejectedDuplicates,downloadErrors,apiErrors");
        for(String key:new String[]{"song","selectedFor"})if(in.optJSONObject(key)!=null)out.put(key,song(in.getJSONObject(key)));
        for(String key:new String[]{"recordingSource","lyricsSource"})if(in.optJSONObject(key)!=null)out.put(key,source(in.getJSONObject(key)));
        if(in.optJSONObject("artistIdentity")!=null)out.put("artistIdentity",strings(in.getJSONObject("artistIdentity"),"mbid,wikidata,name,photoIdentity,recordingId,recordingConfidence",200));
        JSONArray genres=new JSONArray(),a=in.optJSONArray("artistGenres");if(a!=null)for(int i=0;i<Math.min(24,a.length());i++){JSONObject r=a.optJSONObject(i);if(r!=null){JSONObject g=strings(r,"name",80);numbers(r,g,"count");genres.put(g);}}if(in.has("artistGenres"))out.put("artistGenres",genres);
        JSONObject cues=new JSONObject(),raw=in.optJSONObject("lyricCues");if(raw!=null)for(String key:"road home country night city rain water mountain forest desert sunrise sunset lonely warm cold travel wild calm sky neon".split(" "))if(raw.has(key)){double n=raw.optDouble(key);if(Double.isFinite(n))cues.put(key,Math.max(0,Math.min(1,n)));}if(in.has("lyricCues"))out.put("lyricCues",cues);
        if(in.optJSONObject("image")!=null)out.put("image",image(in.getJSONObject("image")));
        if(in.optJSONObject("transition")!=null)out.put("transition",transition(in.optJSONObject("transition")));
        if(in.optJSONObject("decision")!=null)out.put("decision",decision(in.getJSONObject("decision")));
        if(in.has("queries"))out.put("queries",list(in.optJSONArray("queries"),4,180));
        if(kind.equals("feedback")&&!out.optString("rating").matches("good|poor"))return null;
        return out;
    }
    public boolean append(JSONObject event,long now)throws Exception{
        JSONObject clean=sanitize(event);if(clean==null)return false;clean.put("time",now).put("eventId",UUID.randomUUID().toString());byte[] line=(clean+"\n").getBytes(StandardCharsets.UTF_8);if(line.length>MAX_EVENT_BYTES)return false;
        entries.add(new Entry(now,line,clean.getString("eventId")));bytes+=line.length;
        try{if(trim(now)||rewriteRequired)rewrite();else try(FileOutputStream out=new FileOutputStream(file,true)){out.write(line);}}catch(Exception e){rewriteRequired=true;throw e;}return true;
    }
    private boolean trim(long now){boolean changed=false;int countLimit=entries.size()>MAX_EVENTS?MAX_EVENTS-250:MAX_EVENTS,byteLimit=bytes>MAX_BYTES?MAX_BYTES-512*1024:MAX_BYTES;while(!entries.isEmpty()&&(entries.peekFirst().time<now-RETENTION||entries.size()>countLimit||bytes>byteLimit)){bytes-=entries.removeFirst().line.length;changed=true;}return changed;}
    private void rewrite()throws Exception{File pending=new File(file.getPath()+".part");try(FileOutputStream out=new FileOutputStream(pending)){for(Entry e:entries)out.write(e.line);out.getFD().sync();}for(int attempt=0;;attempt++){try{try{Files.move(pending.toPath(),file.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}catch(AtomicMoveNotSupportedException e){Files.move(pending.toPath(),file.toPath(),StandardCopyOption.REPLACE_EXISTING);}rewriteRequired=false;return;}catch(AccessDeniedException e){if(attempt>=3)throw e;Thread.sleep(30L*(attempt+1));}}}
    public void clear()throws Exception{entries.clear();bytes=0;rewrite();}
    public JSONArray batchAfter(String id,int limit,long now)throws Exception{if(trim(now))rewrite();boolean found=id==null||id.isEmpty();if(!found){for(Entry e:entries)if(e.id.equals(id)){found=true;break;}}boolean include=id==null||id.isEmpty()||!found;JSONArray batch=new JSONArray();for(Entry e:entries){if(include){batch.put(new JSONObject(new String(e.line,StandardCharsets.UTF_8)));if(batch.length()>=Math.max(1,Math.min(limit,20)))break;}else if(e.id.equals(id))include=true;}return batch;}
    public void export(File destination,long now)throws Exception{
        if(trim(now))rewrite();File parent=destination.getParentFile();if(!parent.isDirectory()&&!parent.mkdirs())throw new IOException("Export directory unavailable");File pending=new File(destination.getPath()+".part");
        try(FileOutputStream out=new FileOutputStream(pending)){JSONObject header=new JSONObject().put("kind","review_export").put("schema",1).put("appVersion","5.14").put("exportedAt",now).put("events",entries.size()).put("maxEvents",MAX_EVENTS).put("maxPayloadBytes",MAX_BYTES).put("retentionDays",30).put("containsRawLyrics",false);out.write((header+"\n").getBytes(StandardCharsets.UTF_8));for(Entry e:entries)out.write(e.line);out.getFD().sync();}
        Files.move(pending.toPath(),destination.toPath(),StandardCopyOption.REPLACE_EXISTING);
    }
    private static String trackKey(JSONObject v){return v==null?"":v.optString("artist")+"\n"+v.optString("album")+"\n"+v.optString("title")+"\n"+v.optLong("durationMs");}
    public JSONObject summary(long now)throws Exception{
        if(trim(now))rewrite();Map<String,JSONObject> tracks=new LinkedHashMap<>(),ratings=new LinkedHashMap<>();Set<String> images=new HashSet<>(),outages=new HashSet<>();ArrayDeque<String> recentImages=new ArrayDeque<>();JSONArray recent=new JSONArray();int loaded=0,failed=0,good=0,poor=0,repeats=0,selected=0,discoveryDuplicates=0;List<JSONObject> tail=new ArrayList<>();
        for(Entry e:entries){JSONObject v=new JSONObject(new String(e.line,StandardCharsets.UTF_8));String kind=v.optString("kind");JSONObject song=v.optJSONObject("song"),image=v.optJSONObject("image"),d=v.optJSONObject("decision");String key=image==null?"":image.optString("key");
            if(song!=null&&!song.optString("title").isEmpty()){String track=trackKey(song);if(kind.equals("track_state")||!tracks.containsKey(track))tracks.put(track,v);if((v.optString("lyricsStatus")+v.optString("genreStatus")+v.optString("artistStatus")).contains("unavailable"))outages.add(track);}
            if(kind.equals("image_selected"))selected++;
            if(kind.equals("image_loaded")){loaded++;images.add(key);String reason=d==null?"":d.optString("reason");if(!reason.startsWith("browse_")&&recentImages.contains(key))repeats++;recentImages.add(key);while(recentImages.size()>60)recentImages.removeFirst();}
            if(kind.equals("image_failed"))failed++;
            if(kind.equals("feedback")&&!v.optString("outcome").startsWith("taste_"))ratings.put((d==null?v.optString("eventId"):d.optString("selectionId",v.optString("eventId")))+"|"+trackKey(song),v);
            if(kind.equals("discovery"))discoveryDuplicates+=v.optInt("rejectedDuplicates");
            if(kind.equals("feedback")||kind.equals("image_loaded")||kind.equals("image_failed")){tail.add(v);if(tail.size()>12)tail.remove(0);}
        }
        for(JSONObject rating:ratings.values())if(rating.optString("rating").equals("good"))good++;else poor++;
        int lyrics=0,backups=0,cachedLyrics=0,instrumentals=0,unavailable=0,pending=0;for(JSONObject v:tracks.values()){JSONObject source=v.optJSONObject("lyricsSource");if(source!=null&&source.optBoolean("cached"))cachedLyrics++;if(source!=null&&source.optBoolean("instrumental"))instrumentals++;else if(source!=null&&source.optLong("id")>0)lyrics++;else if(source!=null&&source.optString("provider").startsWith("lyrics.ovh")&&source.optDouble("maxLyricWeight")>0)backups++;else if(v.optString("lyricsStatus").contains("unavailable"))unavailable++;else if(v.optString("lyricsStatus").startsWith("Finding"))pending++;}
        for(int i=tail.size()-1;i>=0;i--)recent.put(tail.get(i));
        return new JSONObject().put("events",entries.size()).put("payloadBytes",bytes).put("tracks",tracks.size()).put("selected",selected).put("loaded",loaded).put("uniqueImages",images.size()).put("recentRepeats",repeats).put("failedImages",failed).put("good",good).put("poor",poor).put("lyricMatches",lyrics).put("lyricBackups",backups).put("lyricCacheHits",cachedLyrics).put("instrumentals",instrumentals).put("lyricUnavailable",unavailable).put("lyricPending",pending).put("lyricMissing",Math.max(0,tracks.size()-lyrics-backups-instrumentals-unavailable-pending)).put("tracksWithOutages",outages.size()).put("discoveryDuplicates",discoveryDuplicates).put("oldestAt",entries.isEmpty()?0:entries.peekFirst().time).put("recent",recent);
    }
}
