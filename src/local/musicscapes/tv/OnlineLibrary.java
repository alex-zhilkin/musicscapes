package local.musicscapes.tv;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapRegionDecoder;
import android.graphics.Rect;
import android.os.Process;
import android.util.Log;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.net.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;

/** Commons discovery: 4K scenery and verified wide artist photos down to Full HD. */
public final class OnlineLibrary {
    private static final int MAX_IMAGES=1024;
    private static final long MAX_BYTES=2048L*1024*1024;
    public interface Listener { void changed(JSONObject snapshot); }
    private static OnlineLibrary instance;
    public static synchronized OnlineLibrary get(Context c){if(instance==null)instance=new OnlineLibrary(c.getApplicationContext());return instance;}
    private final Context context;
    private final ArtistPhotoSources artistSources;
    private final File directory,index;
    private final ExecutorService jobs=Executors.newSingleThreadExecutor();
    private final LinkedHashMap<String,JSONObject> records=new LinkedHashMap<>();
    private final DiscoveryGate gate=new DiscoveryGate();
    private final Map<String,Integer> pages=new HashMap<>();
    private final Set<String> changed=new LinkedHashSet<>(),removed=new LinkedHashSet<>();
    private final Set<String> rejectedCopies=new LinkedHashSet<>();
    private final List<VisualFingerprint> bundledFingerprints=new ArrayList<>();
    private volatile boolean initialized;
    private boolean fullDelivery=true;
    private Listener listener;
    private volatile boolean active;
    private volatile String pinned="",status="Online discovery ready";
    private long lastApi;
    private volatile long backoff;
    private OnlineLibrary(Context c){
        context=c;artistSources=new ArtistPhotoSources(c);directory=new File(c.getNoBackupFilesDir(),"online-images");directory.mkdirs();index=new File(directory,"index.json");
        jobs.execute(this::initialize);
    }
    private void initialize(){
        try{
            JSONObject saved=new JSONObject(new String(read(new FileInputStream(index),24000000),"UTF-8"));JSONArray list=saved.optJSONArray("images");
            if(list!=null)for(int i=0;i<list.length();i++){JSONObject r=list.getJSONObject(i);String k=r.optString("key");if(k.matches("web[a-f0-9]{24}")&&image(k).isFile()&&DiscoveryRules.librarySize(r.optString("kind"),r.optInt("width"),r.optInt("height"))){if(r.optInt("tagSchema")!=3){r.put("tags",VisualTags.merge(r.optJSONArray("tags"),r.optString("tagText",r.optString("caption"))));r.put("tagSchema",3);r.put("tagEvidence","Source caption and retained source/category tags");}synchronized(this){records.put(k,r);}}}
            for(JSONObject r:records.values()){
                if(r.optString("kind").equals("photo"))r.put("creatorKey",ImageDiversity.creator(r.optString("artist")));
                if(!r.has("placeKeys"))r.put("placeKeys",ImageDiversity.places(r.optString("caption")));
                r.put("seriesKey",ImageDiversity.series(r.optString("title"),r.optString("artistId",r.optString("artist"))));
                if(!r.optString("kind").equals("artist")&&!ImageDiversity.artworkSubject(r.optString("title"),r.optString("caption")))r.put("disabled",true);
            }
            JSONObject offsets=saved.optJSONObject("pages");if(offsets!=null){Iterator<String> keys=offsets.keys();while(keys.hasNext()){String k=keys.next();pages.put(k,offsets.optInt(k));}}
            JSONArray rejected=saved.optJSONArray("copies");if(rejected!=null)for(int i=Math.max(0,rejected.length()-8000);i<rejected.length();i++)rejectedCopies.add(rejected.optString(i));
        }catch(Exception ignored){}
        try{JSONObject values=new JSONObject(new String(read(context.getAssets().open("fingerprints.json"),100000),"UTF-8"));Iterator<String> keys=values.keys();while(keys.hasNext()){VisualFingerprint fp=VisualFingerprint.decode(values.optString(keys.next()));if(fp!=null)bundledFingerprints.add(fp);}}catch(Exception ignored){}
        // Incomplete downloads from a killed process never count as cached images.
        File[] files=directory.listFiles();if(files!=null)for(File f:files)if(f.getName().endsWith(".part")||f.getName().endsWith(".jpg")&&!records.containsKey(f.getName().replace(".jpg","")))f.delete();
        pruneExpired();evict();
        initialized=true;emit();jobs.execute(this::refreshVisuals);
    }
    private void refreshVisuals(){
        if(!active)return;Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);int processed=0;boolean more=false;
        for(JSONObject record:new ArrayList<>(records.values())){if(record.optJSONObject("visual")!=null)continue;if(processed++>=8){more=true;break;}try{JSONObject visual=measureFile(image(record.getString("key")));synchronized(this){record.put("visual",visual);record.put("energy",visual.optDouble("energy",.4));changed.add(record.getString("key"));}}catch(Exception ignored){try{record.put("visual",new JSONObject().put("schema",1).put("accepted",false).put("quality",0));changed.add(record.getString("key"));}catch(Exception unavailable){}}}
        if(processed>0)synchronized(this){save();emit();}if(more&&active)jobs.execute(this::refreshVisuals);
    }
    public synchronized void attach(Listener next){listener=next;active=true;fullDelivery=true;emit();if(initialized){jobs.execute(this::pruneExpired);jobs.execute(this::refreshVisuals);}}
    public synchronized void detach(Listener old){if(listener==old){listener=null;active=false;gate.cancel();}}
    public synchronized void pin(String key){pinned=key;JSONObject record=records.get(key);if(record!=null)try{record.put("used",System.currentTimeMillis());}catch(Exception ignored){}}
    public File image(String key){if(!key.matches("web[a-f0-9]{24}"))throw new IllegalArgumentException("Invalid image key");return new File(directory,key+".jpg");}
    public synchronized JSONObject snapshot(){
        JSONObject out=new JSONObject();JSONArray images=new JSONArray();if(initialized)for(JSONObject r:records.values())images.put(copy(r));
        try{out.put("images",images);out.put("full",true);out.put("status",initialized?status:"Opening the image collection");out.put("count",records.size());}catch(Exception ignored){}return out;
    }
    private synchronized void emit(){
        if(listener==null)return;
        if(fullDelivery||!initialized){listener.changed(snapshot());fullDelivery=!initialized;changed.clear();removed.clear();return;}
        JSONObject out=new JSONObject();JSONArray images=new JSONArray(),deleted=new JSONArray();for(String key:changed)if(records.containsKey(key))images.put(copy(records.get(key)));for(String key:removed)deleted.put(key);
        try{out.put("images",images);out.put("removed",deleted);out.put("full",false);out.put("status",status);out.put("count",records.size());}catch(Exception ignored){}
        changed.clear();removed.clear();listener.changed(out);
    }
    private static JSONObject copy(JSONObject source){JSONObject out=new JSONObject();Iterator<String> keys=source.keys();while(keys.hasNext()){String key=keys.next();try{out.put(key,source.opt(key));}catch(Exception ignored){}}return out;}
    private void state(String value){if(value.equals(status))return;status=value;emit();}
    public void request(String raw){
        if(raw==null||raw.length()>50000)return;
        try{
            JSONObject plan=new JSONObject(raw);
            jobs.execute(this::pruneExpired);
            if(!plan.optBoolean("enabled",true)){gate.cancel();state(plan.optBoolean("idle")?"Recent pictures · discovery resting":"Offline library · saved images available");return;}
            String id=plan.optString("profile"),type=plan.optString("type","mixed");
            if(!id.matches("[a-z]{1,32}")||!type.matches("mixed|photos|art|space|artists")||plan.optJSONArray("queries")==null)return;
            String key=id+":"+type+":"+plan.optString("worldId")+":"+plan.optString("artistId");long now=System.currentTimeMillis();
            if(!active)return;final int ticket=gate.request(key,now,backoff);
            if(ticket==0){if(!gate.working())state(now<backoff?"Online source busy · using saved images":"Live library · "+size()+" saved widescreen images");return;}
            jobs.execute(()->discover(plan,key,ticket));
        }catch(Exception ignored){}
    }
    private void discover(JSONObject plan,String planKey,int ticket){
        Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);if(!valid(ticket)){gate.finish(ticket,System.currentTimeMillis(),false);return;}
        state("Discovering 4K landscapes · "+plan.optString("name","your style"));int added=0,examined=0,rejectedDuplicates=0,downloadErrors=0,apiErrors=0;long started=System.currentTimeMillis();JSONArray queried=new JSONArray();String outcome="completed";Set<String> batchCreators=new HashSet<>();
        try{
            prepareExistingFingerprints(ticket);
            JSONArray queries=plan.getJSONArray("queries"),target=plan.optJSONArray("vector"),world=plan.optJSONArray("worldVector");
            boolean warming=matchingCount(plan)<24;
            int budget=warming?10:6;
            int page; synchronized(this){page=pages.getOrDefault(planKey,0);}
            List<JSONObject> artistQueries=new ArrayList<>(),sceneQueries=new ArrayList<>();
            for(int i=0;i<queries.length();i++){JSONObject query=queries.getJSONObject(i);(query.optString("kind").equals("artist")?artistQueries:sceneQueries).add(query);}
            // Provider failures remain independent of Commons and never prevent cached playback.
            if(!artistQueries.isEmpty()){
                List<JSONObject> extra=artistSources.discover(artistQueries.get(0),()->valid(ticket));int accepted=0;
                for(JSONObject r:extra){if(!valid(ticket)||accepted>=3||added>=budget)break;examined++;if(admitArtist(r,plan,ticket)){added++;accepted++;}}
            }
            // Complementary searches per batch. Offsets rotate beyond the first results.
            int photoSearchIndex=0;
            for(int q=0;q<Math.min(4,queries.length())&&added<budget&&valid(ticket);q++){
                boolean artistSlot=!artistQueries.isEmpty()&&(q==0||sceneQueries.isEmpty());
                JSONObject query=artistSlot?artistQueries.get((page+q)%artistQueries.size()):sceneQueries.get((page*3+q-(artistQueries.isEmpty()?0:1))%sceneQueries.size());String terms=query.optString("text");
                queried.put(terms);
                boolean artistQuery=query.optString("kind").equals("artist");
                if(!artistQuery&&!terms.matches("[a-zA-Z0-9 ,'-]{3,180}"))continue;
                String artistFile=artistQuery?DiscoveryRules.artistFile(query):"",artistSearch=artistQuery?DiscoveryRules.artistSearch(query):"";if(artistQuery&&artistSearch.isEmpty()&&artistFile.isEmpty())continue;
                int offset=(page/Math.max(1,(queries.length()+2)/3)%20)*30;
                if(artistQuery)offset=(page/Math.max(1,artistQueries.size())%20)*30;
                boolean qualityTier=!artistQuery&&query.optString("kind").equals("photo")&&DiscoveryRules.qualitySearch("photo",photoSearchIndex++,page);query.put("curatedSource",qualityTier);
                String search=(artistQuery?"filetype:bitmap filew:>1919 fileh:>1079 ":"filetype:bitmap filew:>3839 fileh:>2159 ")+(artistQuery?artistSearch:terms)+" -logo -poster -collage -diagram -annotated -infographic"+(query.optString("kind").equals("space")?" hastemplate:PD-USGov-NASA":"")+(qualityTier?" hastemplate:Quality_image":"");
                long delay=Math.max(0,1200-(System.currentTimeMillis()-lastApi));if(delay>0)Thread.sleep(delay);if(!valid(ticket))return;lastApi=System.currentTimeMillis();
                String url="https://commons.wikimedia.org/w/api.php?action=query&generator=search&gsrnamespace=6&gsrlimit=30&gsroffset="+offset+"&gsrsearch="+URLEncoder.encode(search,"UTF-8")+"&prop=imageinfo%7Ccategories&cllimit=30&clshow=!hidden&iiprop=url%7Csize%7Cmime%7Cextmetadata&iiextmetadatalanguage=en&iiurlwidth=4800&format=json";
                if(!artistFile.isEmpty())url="https://commons.wikimedia.org/w/api.php?action=query&titles="+URLEncoder.encode(artistFile,"UTF-8")+"&prop=imageinfo%7Ccategories&cllimit=30&clshow=!hidden&iiprop=url%7Csize%7Cmime%7Cextmetadata&iiextmetadatalanguage=en&iiurlwidth=4800&format=json";
                JSONObject result=null;
                for(int retry=0;retry<3;retry++){
                    result=new JSONObject(new String(fetch(url,2000000,false),"UTF-8"));if(!result.has("error"))break;
                    String code=result.getJSONObject("error").optString("code");if(!code.contains("busy")&&!code.contains("timeout"))break;
                    if(retry<2){Thread.sleep(2500L*(retry+1));if(!valid(ticket))return;}
                }
                if(result.has("error")){apiErrors++;Log.w("MusicScapes","Commons search busy; trying another visual direction");continue;}
                JSONObject pagesResult=result.optJSONObject("query");
                if(pagesResult==null)continue;JSONObject results=pagesResult.optJSONObject("pages");if(results==null)continue;
                List<JSONObject> candidates=new ArrayList<>();Iterator<String> keys=results.keys();while(keys.hasNext())candidates.add(results.getJSONObject(keys.next()));
                Log.i("MusicScapes","Discovery search: "+terms+" · "+candidates.size()+" results");
                candidates.sort(Comparator.comparingInt(r->r.optInt("index",999)));
                List<JSONObject> matches=new ArrayList<>();
                for(JSONObject item:candidates){
                    if(!valid(ticket)||added>=budget||examined>=48)break;
                    JSONObject r=candidate(item,plan,query);if(r==null||!creatorAvailable(r,batchCreators))continue;
                    synchronized(this){if(records.containsKey(r.optString("key"))||rejectedCopies.contains(r.optString("key")))continue;}examined++;
                    JSONArray vector=describe(r.optString("caption"));if(!valid(ticket))return;
                    double generic=target==null?-1:DiscoveryRules.cosine(target,vector),specific=world==null?-1:DiscoveryRules.cosine(world,vector);
                    double fit=artistQuery?1:specific>=0?.4*generic+.6*specific:generic;
                    if(vector.length()!=384||fit<.24||!artistQuery&&specific>=0&&specific<.2)continue;
                    r.put("vector",vector);r.put("discoveryFit",fit);
                    matches.add(r);
                }
                matches.sort((a,b)->Double.compare(b.optDouble("discoveryFit")+.04*b.optDouble("sourceQuality")+(artistQuery&&b.optInt("width")>=3840?.1:0),a.optDouble("discoveryFit")+.04*a.optDouble("sourceQuality")+(artistQuery&&a.optInt("width")>=3840?.1:0)));
                int fromDirection=0;
                for(JSONObject r:matches){
                    if(!valid(ticket)||added>=budget||fromDirection>=(artistQuery?3:queries.length()>1?3:budget))break;
                    if(!creatorAvailable(r,batchCreators))continue;
                    double fit=r.optDouble("discoveryFit");
                    String key=r.getString("key");File download=new File(directory,key+".download.part"),pending=new File(directory,key+".jpg.part");
                    try{
                        if(directory.getUsableSpace()<256L*1024*1024){state("Low storage · using saved images");return;}
                        download(r.getString("downloadUrl"),download,24000000,ticket);
                        if(!valid(ticket))return;
                        JSONObject visual=prepare(download,pending,r.optString("kind").equals("artist"));if(!valid(ticket))return;r.put("visual",visual);r.put("energy",visual.optDouble("energy",.4));r.put("width",visual.optInt("outputWidth",3840));r.put("height",visual.optInt("outputHeight",2160));r.put("modification","Composition-aware crop to 16:9 and resized to "+r.optInt("width")+"×"+r.optInt("height"));
                        VisualFingerprint fingerprint=fingerprint(pending);
                        if(duplicate(fingerprint)){rejectedDuplicates++;synchronized(this){rejectedCopies.add(key);while(rejectedCopies.size()>8000)rejectedCopies.remove(rejectedCopies.iterator().next());}Log.i("MusicScapes","Skipping a visually similar image");continue;}
                        r.put("fingerprint",fingerprint.encode());
                        if(!pending.renameTo(image(key)))throw new IOException("Cannot save completed image");
                        r.remove("downloadUrl");r.put("used",System.currentTimeMillis());
                        synchronized(this){records.put(key,r);changed.add(key);evict();save();}if(!r.optString("creatorKey").isEmpty())batchCreators.add(r.optString("creatorKey"));added++;fromDirection++;
                        state("Live library · "+size()+" saved widescreen images");Log.i("MusicScapes","Discovered "+key+" · "+r.optString("title")+" · cosine "+String.format(Locale.ROOT,"%.3f",fit));
                    }catch(Exception e){downloadErrors++;Log.w("MusicScapes","Image candidate skipped: "+e.getClass().getSimpleName());}
                    finally{download.delete();pending.delete();}
                }
            }
            synchronized(this){pages.put(planKey,page+1);save();}
            if(valid(ticket))state(added>0?"Live library · "+size()+" saved widescreen images":"Live library · "+size()+" saved · exploring more next time");
        }catch(InterruptedException e){outcome="cancelled";Thread.currentThread().interrupt();}
        catch(Exception e){outcome="source_error";apiErrors++;Log.w("MusicScapes","Discovery paused: "+e.getClass().getSimpleName()+" "+e.getMessage());if(valid(ticket))state("Online source unavailable · using saved images");}
        finally{try{ReviewLog.get(context).record("discovery",new JSONObject().put("decision",new JSONObject().put("profile",plan.optString("profile")).put("world",plan.optString("worldId"))).put("outcome",outcome.equals("completed")&&!valid(ticket)?"cancelled":outcome).put("count",added).put("examined",examined).put("rejectedDuplicates",rejectedDuplicates).put("downloadErrors",downloadErrors).put("apiErrors",apiErrors).put("latencyMs",System.currentTimeMillis()-started).put("queries",queried));}catch(Exception ignored){}gate.finish(ticket,System.currentTimeMillis(),added>0,matchingCount(plan)<24?45000:180000);}
    }
    private boolean valid(int ticket){return active&&gate.current(ticket)&&System.currentTimeMillis()>=backoff;}
    private synchronized void pruneExpired(){
        long now=System.currentTimeMillis();boolean dirty=false;Iterator<Map.Entry<String,JSONObject>> list=records.entrySet().iterator();
        while(list.hasNext()){Map.Entry<String,JSONObject> entry=list.next();JSONObject r=entry.getValue();if(!r.optString("imageProvider").equals("flickr")||r.optLong("expiresAt",Long.MAX_VALUE)>now)continue;
            image(entry.getKey()).delete();changed.remove(entry.getKey());removed.add(entry.getKey());list.remove();dirty=true;}
        if(dirty){save();emit();}
    }
    private boolean admitArtist(JSONObject r,JSONObject plan,int ticket){
        String key=r.optString("key"),provider=r.optString("imageProvider");
        if(!key.matches("web[a-f0-9]{24}")||!r.optString("kind").equals("artist")||!plan.optString("artistId").equals(r.optString("artistId"))||!ArtistPhotoRules.imageUrl(r.optString("downloadUrl"),provider)||!artistSources.available(provider))return false;
        synchronized(this){if(records.containsKey(key)||rejectedCopies.contains(key))return false;}
        File download=new File(directory,key+".download.part"),pending=new File(directory,key+".jpg.part");
        try{
            if(directory.getUsableSpace()<256L*1024*1024)return false;
            JSONArray vector=describe(r.optString("caption"));if(!valid(ticket)||vector.length()!=384)return false;
            r.put("vector",vector).put("discoveryFit",1).put("discoveryProfile",plan.optString("profile")).put("discoveryWorld",plan.optString("worldId"));
            download(r.getString("downloadUrl"),download,24000000,ticket,provider);if(!valid(ticket))return false;
            JSONObject visual=prepare(download,pending,true);if(!valid(ticket))return false;
            VisualFingerprint fp=fingerprint(pending);if(duplicate(fp))return false;
            if(!pending.renameTo(image(key)))throw new IOException("Cannot save artist photograph");
            r.put("visual",visual).put("energy",visual.optDouble("energy",.4)).put("width",visual.getInt("outputWidth")).put("height",visual.getInt("outputHeight")).put("fingerprint",fp.encode()).put("used",System.currentTimeMillis());
            r.put("modification","Composition-aware 16:9 crop; resized to "+r.getInt("width")+"×"+r.getInt("height"));r.remove("downloadUrl");
            synchronized(this){records.put(key,r);changed.add(key);evict();save();emit();}return true;
        }catch(Exception e){Log.i("MusicScapes","Optional artist photograph unavailable; keeping saved images");return false;}
        finally{download.delete();pending.delete();}
    }
    private synchronized boolean duplicate(VisualFingerprint fingerprint){for(VisualFingerprint fp:bundledFingerprints)if(fingerprint.similar(fp))return true;for(JSONObject r:records.values()){VisualFingerprint old=VisualFingerprint.decode(r.optString("fingerprint"));if(old!=null&&fingerprint.similar(old))return true;}return false;}
    static VisualFingerprint fingerprint(File image)throws IOException{
        BitmapFactory.Options o=new BitmapFactory.Options();o.inSampleSize=128;Bitmap sample=BitmapFactory.decodeFile(image.getPath(),o);if(sample==null)throw new IOException("Cannot sample image");
        Bitmap small=null;try{small=Bitmap.createScaledBitmap(sample,9,8,true);int[] pixels=new int[72];small.getPixels(pixels,0,9,0,0,9,8);return VisualFingerprint.of(pixels);}finally{if(small!=null&&small!=sample)small.recycle();sample.recycle();}
    }
    private synchronized int size(){return records.size();}
    private synchronized boolean creatorAvailable(JSONObject candidate,Set<String> batch){
        String creator=candidate.optString("creatorKey");if(!candidate.optString("kind").equals("photo")||creator.isEmpty())return true;
        if(batch.contains(creator))return false;
        int photos=0,same=0;for(JSONObject r:records.values())if(r.optString("kind").equals("photo")&&!r.optBoolean("disabled")){photos++;if(creator.equals(r.optString("creatorKey",ImageDiversity.creator(r.optString("artist")))))same++;}
        return same<ImageDiversity.creatorLimit(photos);
    }
    private synchronized int matchingCount(JSONObject plan){int total=0;for(JSONObject r:records.values())if(r.optString("discoveryProfile").equals(plan.optString("profile"))&&r.optString("discoveryWorld").equals(plan.optString("worldId")))total++;return total;}
    private void prepareExistingFingerprints(int ticket){
        List<JSONObject> list; synchronized(this){list=new ArrayList<>(records.values());}
        for(JSONObject record:list){if(!valid(ticket))return;if(VisualFingerprint.decode(record.optString("fingerprint"))!=null)continue;
            try{String value=fingerprint(image(record.optString("key"))).encode();synchronized(this){record.put("fingerprint",value);}}
            catch(Exception ignored){}
        }
    }
    private JSONObject candidate(JSONObject page,JSONObject plan,JSONObject query){
        try{
            JSONObject info=page.getJSONArray("imageinfo").getJSONObject(0),meta=info.getJSONObject("extmetadata");
            boolean artistQuery=query.optString("kind").equals("artist");
            String title=DiscoveryRules.clean(page.optString("title").replaceFirst("^File:","").replaceFirst("(?i)\\.(jpg|jpeg|png)$","").replace('_',' '));
            if(DiscoveryRules.unwanted(title)||!(artistQuery?DiscoveryRules.artistHd(info.optInt("width"),info.optInt("height")):DiscoveryRules.full4k(info.optInt("width"),info.optInt("height"))))return null;
            Log.i("MusicScapes","Wide candidate: "+title);
            if(!info.optString("mime").matches("image/(jpeg|png)")&&!info.optString("thumbmime").matches("image/(jpeg|png)"))return null;
            String license=DiscoveryRules.license(meta);if(license.isEmpty()){Log.i("MusicScapes","Candidate licence rejected: "+DiscoveryRules.plain(meta.optJSONObject("LicenseShortName")));return null;}
            String description=DiscoveryRules.plain(meta.optJSONObject("ImageDescription"));if(description.length()>600)description=description.substring(0,600);
            if(DiscoveryRules.unwanted(title+" "+description))return null;
            String kind=DiscoveryRules.kind(title,description),want=query.optString("kind","photo"),type=plan.optString("type","mixed");
            if(kind.equals("documentary"))return null;
            if(want.equals("artist")){
                if(!kind.equals("photo")||!VisualTags.artistSubject(title,description,query.optString("text"),query.optString("artistType")))return null;
                kind="artist";
            }
            else if(kind.equals("photo")&&VisualTags.extract(title+" "+description).toString().contains("\"performance\""))return null;
            if(!want.equals(kind)||type.equals("photos")&&kind.equals("art")||type.equals("art")&&!kind.equals("art")||type.equals("space")&&!kind.equals("space")||type.equals("artists")&&!kind.equals("artist"))return null;
            if(kind.equals("space")&&!license.equalsIgnoreCase("public domain")&&!license.equalsIgnoreCase("CC0"))return null;
            String original=info.optString("url").split("\\?",2)[0];
            String download=DiscoveryRules.downloadUrl(info,artistQuery);if(download.isEmpty())return null;
            String creator=DiscoveryRules.plain(meta.optJSONObject("Artist")),credit=DiscoveryRules.plain(meta.optJSONObject("Credit"));
            if(creator.isEmpty()&&!license.equalsIgnoreCase("public domain")&&!license.equalsIgnoreCase("CC0"))return null;
            String source=info.optString("descriptionurl");if(!source.startsWith("https://commons.wikimedia.org/wiki/File:")){Log.i("MusicScapes","Candidate source link rejected: "+source);return null;}
            String caption=title+". "+description;String key="web"+hash(original).substring(0,24);
            StringBuilder categoryEvidence=new StringBuilder();JSONArray categories=page.optJSONArray("categories");if(categories!=null)for(int i=0;i<Math.min(30,categories.length());i++){String category=categories.getJSONObject(i).optString("title").replaceFirst("^Category:","");if(!category.toLowerCase(Locale.ROOT).matches(".*(license|licence|uploaded|flickr|taken on|photographs by|files with|images with|copyright|creative commons).*"))categoryEvidence.append(' ').append(category);}
            String evidence=caption+" "+categoryEvidence;
            JSONObject families=new JSONObject();String family=plan.optString("family");if(family.matches("country|jazz|electronic|folk|chill|rock|classical|pop|hiphop|world"))families.put(family,.8);
            JSONObject r=new JSONObject();r.put("key",key);r.put("title",title);r.put("caption",caption);r.put("tags",VisualTags.extract(evidence));r.put("tagSchema",3);r.put("tagText",evidence.substring(0,Math.min(3000,evidence.length())));r.put("tagEvidence","Source caption and visible Commons categories");r.put("families",families);r.put("discoveryProfile",plan.optString("profile"));r.put("discoveryWorld",plan.optString("worldId"));
            if(kind.equals("artist")){r.put("artistId",query.optString("artistId"));r.put("artistName",query.optString("text"));r.put("artistQid",query.optString("artistQid"));r.put("identityEvidence",query.optString("artistSearch").equals("depicts")?"Verified artist identity, Commons depicts search and named source caption":"Confirmed artist category and named source caption");}
            r.put("energy",.4);r.put("cluster","web:"+kind+":"+query.optString("text"));r.put("kind",kind);r.put("artist",creator.isEmpty()?credit:creator);
            r.put("seriesKey",ImageDiversity.series(title,r.optString("artistId",r.optString("artist"))));
            if(kind.equals("photo"))r.put("creatorKey",ImageDiversity.creator(r.optString("artist")));r.put("placeKeys",ImageDiversity.places(evidence));
            r.put("credit",credit);r.put("source","Wikimedia Commons");r.put("sourceUrl",source);r.put("license",license);r.put("licenseUrl",DiscoveryRules.plain(meta.optJSONObject("LicenseUrl")));
            String assessment=DiscoveryRules.plain(meta.optJSONObject("Assessments")).toLowerCase(Locale.ROOT);r.put("sourceQuality",query.optBoolean("curatedSource")||assessment.contains("featured")||assessment.contains("quality image")?1:0);
            boolean fourK=DiscoveryRules.full4k(info.optInt("width"),info.optInt("height"));r.put("imageUrl",original);r.put("downloadUrl",download);r.put("width",fourK?3840:1920);r.put("height",fourK?2160:1080);r.put("originalWidth",info.optInt("width"));r.put("originalHeight",info.optInt("height"));
            r.put("remote",true);r.put("modification","Center cropped to 16:9 and resized to 3840×2160");return r;
        }catch(Exception e){Log.w("MusicScapes","Candidate metadata skipped: "+e.getClass().getSimpleName()+" "+e.getMessage());return null;}
    }
    private JSONArray describe(String text)throws InterruptedException{
        CountDownLatch done=new CountDownLatch(1);final JSONArray[] vector={new JSONArray()};
        SemanticMatcher.describe(context,text,(v,s)->{vector[0]=v;done.countDown();});if(!done.await(45,TimeUnit.SECONDS))return new JSONArray();return vector[0];
    }
    static JSONObject measureFile(File file)throws Exception{BitmapFactory.Options o=new BitmapFactory.Options();o.inSampleSize=8;Bitmap decoded=BitmapFactory.decodeFile(file.getPath(),o);if(decoded==null)throw new IOException("Visual sample unavailable");try{return measureBitmap(decoded);}finally{decoded.recycle();}}
    private static JSONObject measureBitmap(Bitmap image)throws Exception{Bitmap small=Bitmap.createScaledBitmap(image,256,144,true);try{int[] pixels=new int[256*144];small.getPixels(pixels,0,256,0,0,256,144);return VisualQuality.measure(pixels,256,144);}finally{if(small!=image)small.recycle();}}
    static JSONObject prepare(File input,File output)throws Exception{
        return prepare(input,output,false);
    }
    static JSONObject prepare(File input,File output,boolean artist)throws Exception{
        BitmapFactory.Options bounds=new BitmapFactory.Options();bounds.inJustDecodeBounds=true;BitmapFactory.decodeFile(input.getPath(),bounds);
        if(!(artist?DiscoveryRules.artistHd(bounds.outWidth,bounds.outHeight):DiscoveryRules.full4k(bounds.outWidth,bounds.outHeight)))throw new IOException("Image dimensions failed");
        final int outputW=DiscoveryRules.full4k(bounds.outWidth,bounds.outHeight)?3840:1920,outputH=outputW*9/16;
        if("image/jpeg".equals(bounds.outMimeType)){
            try(FileInputStream exifInput=new FileInputStream(input)){
                int orientation=new android.media.ExifInterface(exifInput).getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION,1);
                if(orientation!=1&&orientation!=0)throw new IOException("Image orientation is unsuitable");
            }
        }
        int w=bounds.outWidth,h=bounds.outHeight,cw=w,ch=h;if(w/(double)h>16d/9)cw=(int)Math.floor(h*16d/9);else ch=(int)Math.floor(w*9d/16);
        if(cw<outputW||ch<outputH)throw new IOException("Full-resolution widescreen crop unavailable");
        double cropOrigin=.5;BitmapFactory.Options previewOptions=new BitmapFactory.Options();previewOptions.inSampleSize=32;Bitmap preview=BitmapFactory.decodeFile(input.getPath(),previewOptions);if(preview!=null){Bitmap sample=Bitmap.createScaledBitmap(preview,192,Math.max(8,(int)Math.round(192d*h/w)),true);try{int sw=sample.getWidth(),sh=sample.getHeight();int[] pixels=new int[sw*sh];sample.getPixels(pixels,0,sw,0,0,sw,sh);cropOrigin=VisualQuality.cropOrigin(pixels,sw,sh);}finally{if(sample!=preview)sample.recycle();preview.recycle();}}
        BitmapRegionDecoder decoder=null;Bitmap cropped=null,scaled=null;
        try{
            if(android.os.Build.VERSION.SDK_INT>=28){
                final double origin=cropOrigin,scale=Math.max(outputW/(double)cw,outputH/(double)ch);final int targetW=Math.max(outputW,(int)Math.ceil(w*scale)),targetH=Math.max(outputH,(int)Math.ceil(h*scale));
                scaled=android.graphics.ImageDecoder.decodeBitmap(android.graphics.ImageDecoder.createSource(input),(d,info,source)->{d.setAllocator(android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE);d.setMemorySizePolicy(android.graphics.ImageDecoder.MEMORY_POLICY_LOW_RAM);d.setTargetColorSpace(android.graphics.ColorSpace.get(android.graphics.ColorSpace.Named.SRGB));d.setTargetSize(targetW,targetH);int x=(int)Math.round((targetW-outputW)*origin),y=(int)Math.round((targetH-outputH)*origin);d.setCrop(new Rect(x,y,x+outputW,y+outputH));});
            }else{
                decoder=BitmapRegionDecoder.newInstance(input.getPath(),false);BitmapFactory.Options o=new BitmapFactory.Options();o.inPreferredConfig=Bitmap.Config.RGB_565;
                while(cw/(o.inSampleSize==0?2:o.inSampleSize*2)>=outputW&&ch/(o.inSampleSize==0?2:o.inSampleSize*2)>=outputH)o.inSampleSize=o.inSampleSize==0?2:o.inSampleSize*2;
                int x=(int)Math.round((w-cw)*cropOrigin),y=(int)Math.round((h-ch)*cropOrigin);cropped=decoder.decodeRegion(new Rect(x,y,x+cw,y+ch),o);if(cropped==null)throw new IOException("Image decoding failed");scaled=Bitmap.createScaledBitmap(cropped,outputW,outputH,true);
            }
            JSONObject visual=measureBitmap(scaled);if(!visual.optBoolean("accepted"))throw new IOException("Blank or overwhelmingly clipped image");
            Log.i("MusicScapes","Online image validated: "+bounds.outWidth+"x"+bounds.outHeight+" -> "+scaled.getWidth()+"x"+scaled.getHeight());
            try(FileOutputStream stream=new FileOutputStream(output)){if(!scaled.compress(Bitmap.CompressFormat.JPEG,90,stream))throw new IOException("Image encoding failed");}
            return visual.put("outputWidth",outputW).put("outputHeight",outputH);
        }finally{if(scaled!=null&&scaled!=cropped)scaled.recycle();if(cropped!=null)cropped.recycle();if(decoder!=null)decoder.recycle();}
    }
    private byte[] fetch(String address,int cap,boolean image)throws Exception{
        String next=address;
        for(int redirects=0;redirects<4;redirects++){
            if(image&&!DiscoveryRules.allowedImage(next))throw new IOException("Untrusted image URL");
            HttpURLConnection c=(HttpURLConnection)new URL(next).openConnection();c.setInstanceFollowRedirects(false);c.setConnectTimeout(10000);c.setReadTimeout(15000);c.setRequestProperty("User-Agent","MusicScapes/3.0 (personal Android TV image companion; Commons API)");
            try{
                int code=c.getResponseCode();
                if(code==429||code==503){long seconds=300;try{seconds=Math.max(60,Math.min(1800,Long.parseLong(c.getHeaderField("Retry-After"))));}catch(Exception ignored){}backoff=System.currentTimeMillis()+seconds*1000;state("Online source busy · using saved images");throw new IOException("Source busy");}
                if(code>=300&&code<400){next=new URL(new URL(next),c.getHeaderField("Location")).toString();if(!image&&!next.startsWith("https://commons.wikimedia.org/w/api.php"))throw new IOException("API redirect rejected");continue;}
                if(code!=200||c.getContentLengthLong()>cap)throw new IOException("HTTP "+code+" or file too large");
                return read(c.getInputStream(),cap);
            }finally{c.disconnect();}
        }throw new IOException("Too many redirects");
    }
    private void download(String address,File destination,int cap,int ticket)throws Exception{
        download(address,destination,cap,ticket,"commons");
    }
    private void download(String address,File destination,int cap,int ticket,String provider)throws Exception{
        String next=address;
        for(int redirects=0;redirects<4;redirects++){
            if(!valid(ticket))throw new IOException("Discovery cancelled");
            if(!ArtistPhotoRules.imageUrl(next,provider))throw new IOException("Untrusted image URL");
            if(!provider.equals("commons")&&!artistSources.available(provider))throw new IOException("Artist source cooling down");
            if(System.currentTimeMillis()<backoff)throw new IOException("Source cooling down");
            HttpURLConnection c=(HttpURLConnection)new URL(next).openConnection();c.setInstanceFollowRedirects(false);c.setConnectTimeout(10000);c.setReadTimeout(15000);c.setRequestProperty("User-Agent","MusicScapes/4.0 (android-app://local.musicscapes.tv)");
            try{int code=c.getResponseCode();if(code==429||code==503){long seconds=300;try{seconds=Math.max(60,Math.min(86400,Long.parseLong(c.getHeaderField("Retry-After"))));}catch(Exception ignored){}if(provider.equals("commons")){backoff=System.currentTimeMillis()+seconds*1000;state("Online source busy · using saved images");}else artistSources.coolDown(provider,seconds);throw new IOException("Source busy");}
                if(code>=300&&code<400){next=new URL(new URL(next),c.getHeaderField("Location")).toString();continue;}
                if(code!=200||c.getContentLengthLong()>cap)throw new IOException("HTTP "+code+" or file too large");
                try(InputStream in=c.getInputStream();FileOutputStream out=new FileOutputStream(destination)){byte[] bytes=new byte[65536];int n,total=0;while((n=in.read(bytes))!=-1){if(!valid(ticket))throw new IOException("Discovery cancelled");total+=n;if(total>cap)throw new IOException("Image too large");out.write(bytes,0,n);}}return;
            }finally{c.disconnect();}
        }throw new IOException("Too many redirects");
    }
    private static byte[] read(InputStream input,int cap)throws IOException{try(InputStream in=input;ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] block=new byte[32768];int n;while((n=in.read(block))!=-1){if(out.size()+n>cap)throw new IOException("Response too large");out.write(block,0,n);}return out.toByteArray();}}
    private static String hash(String text)throws Exception{byte[] bytes=MessageDigest.getInstance("SHA-256").digest(text.getBytes("UTF-8"));StringBuilder out=new StringBuilder();for(byte b:bytes)out.append(String.format(Locale.ROOT,"%02x",b&255));return out.toString();}
    private synchronized void evict(){
        long bytes=0;for(String key:records.keySet())bytes+=image(key).length();
        List<JSONObject> ordered=new ArrayList<>(records.values());ordered.sort(Comparator.comparingLong(r->r.optLong("used")));
        for(JSONObject r:ordered){if(records.size()<=MAX_IMAGES&&bytes<=MAX_BYTES)break;String key=r.optString("key");if(key.equals(pinned))continue;File f=image(key);long size=f.length();if(f.delete()){records.remove(key);changed.remove(key);removed.add(key);bytes-=size;}}
    }
    /** Personal, shell-requested diagnostics. Public image metadata/vectors only; no lyrics. */
    public void exportReview(File output)throws Exception{
        List<JSONObject> list=new ArrayList<>();synchronized(this){if(!initialized)throw new IOException("Image library still opening");for(JSONObject r:records.values())list.add(copy(r));}
        File pending=new File(output.getPath()+".part");try(java.io.BufferedOutputStream stream=new java.io.BufferedOutputStream(new FileOutputStream(pending))){
            stream.write((new JSONObject().put("kind","library_export").put("appVersion",BuildProfile.VERSION).put("count",list.size()).put("exportedAt",System.currentTimeMillis()).toString()+"\n").getBytes("UTF-8"));
            for(JSONObject record:list)stream.write((record.toString()+"\n").getBytes("UTF-8"));
        }
        if(!pending.renameTo(output))throw new IOException("Image review export unavailable");
    }
    private synchronized void save(){
        try{JSONObject out=new JSONObject();JSONArray list=new JSONArray();for(JSONObject r:records.values())list.put(r);out.put("images",list);out.put("pages",new JSONObject(pages));out.put("copies",new JSONArray(rejectedCopies));File temp=new File(directory,"index.part");try(FileOutputStream stream=new FileOutputStream(temp)){stream.write(out.toString().getBytes("UTF-8"));}if(!temp.renameTo(index))throw new IOException("Index rename failed");}
        catch(Exception e){Log.w("MusicScapes","Image index could not be saved");}
    }
}
