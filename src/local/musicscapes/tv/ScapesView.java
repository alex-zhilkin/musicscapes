package local.musicscapes.tv;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebChromeClient;
import android.webkit.ConsoleMessage;
import android.widget.FrameLayout;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class ScapesView extends FrameLayout {
    public interface PlaybackListener { void changed(boolean playing); }
    private WebView web;
    private android.widget.TextView recoveryNotice;
    private long recoveryWindow;
    private int recoveryAttempts;
    private final PhotoSurface photoSurface;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final MediaSessionManager sessions;
    private final ComponentName access;
    private final PlaybackListener playbackListener;
    private final SharedPreferences prefs;
    private final String reviewSession=java.util.UUID.randomUUID().toString();
    private String lastReviewState="";
    private final ExecutorService lookup = Executors.newSingleThreadExecutor();
    private final ExecutorService artists = Executors.newSingleThreadExecutor();
    private final ExecutorService lyricsLookup=Executors.newSingleThreadExecutor();
    private final ExecutorService libraryDelivery=Executors.newSingleThreadExecutor();
    private final java.util.concurrent.atomic.AtomicInteger libraryEpoch=new java.util.concurrent.atomic.AtomicInteger();
    private long nextLibraryDelivery;
    private int preparedLibraryEpoch;
    private static final Map<String,TrackMetadata.Result> cache = new LinkedHashMap<String,TrackMetadata.Result>() {
        @Override protected boolean removeEldestEntry(Map.Entry<String,TrackMetadata.Result> e) { return size() > 80; }
    };
    private MediaController controller;
    private boolean running, ready, disposed, permission;
    private String title = "", artist = "", album="", genre = "", genreStatus = "", lookupKey = "";
    private JSONObject artistIdentity=new JSONObject();
    private JSONObject recordingSource=new JSONObject(),lyricsSource=new JSONObject();
    private long nextArtistRetryAt,nextLyricsRetryAt,nextGenreRetryAt;
    private boolean lyricsRetryNeeded;
    private JSONObject lyricCues = new JSONObject();
    private String lyricsStatus = "";
    private JSONArray lyricVector=new JSONArray(),artistGenres=new JSONArray();
    private String mlStatus="",artistStatus="",artistKey="";
    private static final Map<String,ArtistStyles.Result> artistCache=new LinkedHashMap<String,ArtistStyles.Result>(){
        @Override protected boolean removeEldestEntry(Map.Entry<String,ArtistStyles.Result> e){return size()>96;}
    };
    private static final Map<String,JSONArray> semanticCache=new LinkedHashMap<String,JSONArray>(){
        @Override protected boolean removeEldestEntry(Map.Entry<String,JSONArray> e){return size()>80;}
    };
    private static final Map<String,LyricsSignals.Result> lyricCache = new LinkedHashMap<String,LyricsSignals.Result>() {
        @Override protected boolean removeEldestEntry(Map.Entry<String,LyricsSignals.Result> e) { return size()>80; }
    };
    private boolean playing;
    private volatile int generation;
    private final OnlineLibrary.Listener libraryListener=this::receiveLibrary;
    private final MediaController.Callback callback = new MediaController.Callback() {
        @Override public void onMetadataChanged(MediaMetadata data) { update(); }
        @Override public void onPlaybackStateChanged(PlaybackState state) { update(); }
        @Override public void onSessionDestroyed() { bind(null); refresh(); }
    };
    private final MediaSessionManager.OnActiveSessionsChangedListener sessionListener = list -> select(list);
    private final Runnable poll = new Runnable() {
        @Override public void run() { if (running) { refresh(); main.postDelayed(this, 5000); } }
    };

    public ScapesView(Context context, PlaybackListener listener) {
        super(context);
        playbackListener = listener;
        prefs = context.getSharedPreferences("scapes", Context.MODE_PRIVATE);
        sessions = (MediaSessionManager) context.getSystemService(Context.MEDIA_SESSION_SERVICE);
        access = new ComponentName(context, MusicAccess.class);
        photoSurface = new PhotoSurface(context);
        addView(photoSurface, new LayoutParams(-1,-1));
        createWebView();
    }
    private void createWebView(){
        if(disposed||web!=null)return;
        if(recoveryNotice!=null){removeView(recoveryNotice);recoveryNotice=null;}
        web = new WebView(getContext());
        web.setBackgroundColor(android.graphics.Color.TRANSPARENT);
        web.getSettings().setJavaScriptEnabled(true);
        web.getSettings().setAllowFileAccess(true);
        web.getSettings().setAllowContentAccess(false);
        web.getSettings().setAllowFileAccessFromFileURLs(false);
        web.getSettings().setAllowUniversalAccessFromFileURLs(false);
        web.getSettings().setMediaPlaybackRequiresUserGesture(true);
        web.addJavascriptInterface(new Bridge(), "Native");
        photoSurface.setLoadListener((key,loaded)->main.post(()->send("window.nativePhotoResult && window.nativePhotoResult("+JSONObject.quote(key)+","+loaded+")")));
        web.setWebChromeClient(new WebChromeClient(){@Override public boolean onConsoleMessage(ConsoleMessage m){if(m.messageLevel()==ConsoleMessage.MessageLevel.ERROR)Log.w("MusicScapes","View script error at "+m.lineNumber()+": "+m.message());return true;}});
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean onRenderProcessGone(WebView view,android.webkit.RenderProcessGoneDetail detail){
                // A dead renderer cannot be reused. Every affected view must handle this
                // callback, including an app view hidden behind a screensaver.
                if(view!=web)return true;
                ready=false;libraryEpoch.incrementAndGet();removeView(view);web=null;
                view.removeJavascriptInterface("Native");view.destroy();
                Log.w("MusicScapes","Picture controls renderer exited; recovering locally");
                if(!disposed&&running)recoverWebView(false);
                return true;
            }
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) { return true; }
            @Override public void onPageFinished(WebView view, String url) {
                if (view!=web||!url.equals("file:///android_asset/index.html") || disposed) return;
                ready = true;
                send("window.setScreenMode && window.setScreenMode("+(getContext() instanceof android.service.dreams.DreamService)+")");
                send("window.restoreHistory && window.restoreHistory("+ImageHistory.get(getContext()).snapshot()+")");
                send("window.restoreTaste && window.restoreTaste("+prefs.getString("taste","{}")+")");
                send("window.restore(" + JSONObject.quote(prefs.getString("settings", "{}")) + ")");
                publish();
                receiveLibrary(OnlineLibrary.get(getContext()).snapshot());
            }
        });
        addView(web, new LayoutParams(-1, -1));
        web.loadUrl("file:///android_asset/index.html");
    }
    private void recoverWebView(boolean manual){
        if(disposed||web!=null)return;
        long now=android.os.SystemClock.uptimeMillis();
        if(manual||now-recoveryWindow>60000){recoveryWindow=now;recoveryAttempts=0;}
        if(recoveryAttempts>=3){
            if(recoveryNotice==null){recoveryNotice=new android.widget.TextView(getContext());recoveryNotice.setText("Picture controls could not restart.\nPress OK to try again.");recoveryNotice.setTextColor(android.graphics.Color.WHITE);recoveryNotice.setTextSize(24);recoveryNotice.setGravity(android.view.Gravity.CENTER);recoveryNotice.setBackgroundColor(0xAA14201B);addView(recoveryNotice,new LayoutParams(-1,-1));}
            return;
        }
        int delay=500*(1<<recoveryAttempts++);
        main.postDelayed(()->{if(!disposed&&running&&web==null)createWebView();},delay);
    }
    public void start() {
        if (running || disposed) return;
        running = true; SemanticMatcher.visible();photoSurface.setActive(true); if(web==null)recoverWebView(false);else web.onResume();
        try{ReviewLog.get(getContext()).record("session",new JSONObject().put("sessionId",reviewSession).put("appVersion","5.14").put("mode",getContext() instanceof android.service.dreams.DreamService?"screensaver":"app").put("outcome","started"));}catch(Exception ignored){}
        OnlineLibrary.get(getContext()).attach(libraryListener);
        send("window.resumeScapes && window.resumeScapes()");
        if(PrivacyGate.allowed(getContext()))try { sessions.addOnActiveSessionsChangedListener(sessionListener, access, main); } catch (SecurityException ignored) {}
        poll.run();
    }
    public void stop() {
        if(running)SemanticMatcher.hidden();
        send("window.pauseScapes && window.pauseScapes()");
        running = false; photoSurface.setActive(false); generation++; lookupKey = ""; artistKey="";
        libraryEpoch.incrementAndGet();
        OnlineLibrary.get(getContext()).detach(libraryListener);
        main.removeCallbacks(poll);
        sessions.removeOnActiveSessionsChangedListener(sessionListener);
        bind(null); if(web!=null)web.onPause();
    }
    public void dispose() { if (disposed) return; stop(); disposed = true; lookup.shutdownNow(); artists.shutdownNow(); lyricsLookup.shutdownNow(); libraryDelivery.shutdownNow(); photoSurface.dispose(); if(web!=null){web.removeJavascriptInterface("Native");web.destroy();web=null;} }
    private void receiveLibrary(JSONObject snapshot){
        if(disposed)return;final boolean full=snapshot.optBoolean("full",true);final int epoch=full?libraryEpoch.incrementAndGet():libraryEpoch.get();
        try{libraryDelivery.execute(()->{
            if(epoch!=preparedLibraryEpoch){preparedLibraryEpoch=epoch;nextLibraryDelivery=0;}
            JSONArray images=snapshot.optJSONArray("images");int count=images==null?0:images.length(),chunks=Math.max(1,(count+23)/24);
            long starts=Math.max(android.os.SystemClock.uptimeMillis(),nextLibraryDelivery);
            for(int part=0;part<chunks;part++){
                if(disposed||epoch!=libraryEpoch.get())return;
                try{JSONObject payload=new JSONObject();JSONArray block=new JSONArray();for(int i=part*24;i<Math.min(count,(part+1)*24);i++)block.put(images.get(i));
                    payload.put("images",block);payload.put("full",false);payload.put("reset",full&&part==0);payload.put("complete",part==chunks-1);payload.put("status",snapshot.optString("status"));payload.put("count",snapshot.optInt("count"));
                    if(part==0)payload.put("removed",snapshot.optJSONArray("removed"));final String script="window.syncOnline && window.syncOnline("+payload+")";
                    main.postAtTime(()->{if(running&&!disposed&&epoch==libraryEpoch.get())send(script);},starts+part*24L);
                }catch(Exception ignored){}
            }
            nextLibraryDelivery=starts+chunks*24L;
        });}catch(java.util.concurrent.RejectedExecutionException ignored){}
    }
    public void remote(int key) { if(web==null){if(key==23||key==66)recoverWebView(true);else if(key==4&&getContext() instanceof Activity)((Activity)getContext()).finish();return;}send("window.remote(" + key + ")"); }
    private void send(String script) { if (ready && !disposed&&web!=null) web.evaluateJavascript(script, null); }
    private void refresh() {
        if(!PrivacyGate.allowed(getContext())){permission=false;bind(null);title="";artist="";album="";genre="";playing=false;lyricCues=new JSONObject();lyricVector=new JSONArray();artistGenres=new JSONArray();artistIdentity=new JSONObject();recordingSource=new JSONObject();lyricsSource=new JSONObject();lyricsStatus="";genreStatus="";artistStatus="";publish();return;}
        try { permission = true; select(sessions.getActiveSessions(access)); }
        catch (SecurityException e) { permission = false; bind(null); publish(); }
    }
    private void select(List<MediaController> list) {
        if(!PrivacyGate.allowed(getContext()))return;
        MediaController selected = null;
        if (list != null) {
            for (MediaController item : list) if ("com.spotify.tv.android".equals(item.getPackageName())) { selected = item; break; }
            if (selected == null) for (MediaController item : list) {
                PlaybackState state = item.getPlaybackState();
                if (state != null && state.getState() == PlaybackState.STATE_PLAYING) { selected = item; break; }
            }
        }
        if (controller == null || selected == null || !controller.getSessionToken().equals(selected.getSessionToken())) bind(selected);
        update();
    }
    private void bind(MediaController next) {
        if (controller != null) controller.unregisterCallback(callback);
        controller = next;
        if (controller != null) controller.registerCallback(callback, main);
    }
    private static String text(MediaMetadata data, String key) { String s = data == null ? null : data.getString(key); return s == null ? "" : s; }
    private void update() {
        if (!running || !PrivacyGate.allowed(getContext())) return;
        MediaMetadata data = controller == null ? null : controller.getMetadata();
        PlaybackState state = controller == null ? null : controller.getPlaybackState();
        playing = state != null && state.getState() == PlaybackState.STATE_PLAYING;
        title = text(data, MediaMetadata.METADATA_KEY_TITLE);
        artist = text(data, MediaMetadata.METADATA_KEY_ARTIST);
        if (artist.isEmpty()) artist = text(data, MediaMetadata.METADATA_KEY_ALBUM_ARTIST);
        album=text(data,MediaMetadata.METADATA_KEY_ALBUM);
        final long recordingDuration=data==null?0:data.getLong(MediaMetadata.METADATA_KEY_DURATION);
        final String artistRecordingKey=artist+"\n"+title+"\n"+album+"\n"+recordingDuration/1000;
        long now=System.currentTimeMillis();
        if(artistRecordingKey.equals(artistKey)&&artistStatus.contains("unavailable")&&now>=nextArtistRetryAt)artistKey="";
        if(!artistRecordingKey.equals(artistKey)){
            artistKey=artistRecordingKey;artistGenres=new JSONArray();artistIdentity=new JSONObject();artistStatus="";nextArtistRetryAt=now+120000;
            if(!artist.isEmpty()){
                ArtistStyles.Result cached=artistCache.get(artistRecordingKey);if(cached==null)cached=artistCache.get("artist:"+artist);
                if(cached!=null){artistGenres=cached.genres;artistIdentity=cached.identity;artistStatus=cached.status;}
                else{
                    artistStatus="Finding artist styles";final String requestArtist=artist,requestTitle=title,requestAlbum=album;
                    artists.execute(()->{
                        if(!artistRecordingKey.equals(artistKey)||!running)return;
                        ArtistStyles.Result result=ArtistStyles.lookup(requestArtist,requestTitle,requestAlbum,recordingDuration);
                        main.post(()->{
                            if(disposed)return;if(!result.status.contains("unavailable")){artistCache.put(artistRecordingKey,result);if(result.genres.length()>0&&!result.identity.has("recordingId")&&Matching.normalized(requestArtist).length()>=6)artistCache.put("artist:"+requestArtist,result);}
                            if(running&&artistRecordingKey.equals(artistKey)){artistGenres=result.genres;artistIdentity=result.identity;artistStatus=result.status;nextArtistRetryAt=System.currentTimeMillis()+120000;publish();}
                        });
                    });
                }
            }
        }
        String key = artist + "\n" + title+"\n"+album+"\n"+recordingDuration/1000;
        if(key.equals(lookupKey)&&(lyricsRetryNeeded&&now>=nextLyricsRetryAt||genreStatus.contains("unavailable")&&now>=nextGenreRetryAt))lookupKey="";
        if (!key.equals(lookupKey)) {
            lookupKey = key; int ticket = ++generation;
            lyricCues = new JSONObject(); lyricsStatus = "";lyricsSource=new JSONObject();recordingSource=new JSONObject();lyricsRetryNeeded=false;nextLyricsRetryAt=now+120000;nextGenreRetryAt=now+120000;
            lyricVector=semanticCache.containsKey(key)?semanticCache.get(key):new JSONArray();
            mlStatus=lyricVector.length()>0?"Local ML lyric matching":"";
            genre = text(data, MediaMetadata.METADATA_KEY_GENRE);
            genreStatus = "";
            if(!genre.isEmpty())try{recordingSource.put("provider","Android media metadata").put("confidence","provided");}catch(Exception ignored){}
            if (genre.isEmpty() && !artist.isEmpty() && !title.isEmpty()) {
                TrackMetadata.Result saved = cache.get(key);
                if (saved != null) { genre = saved.genre; genreStatus = saved.status;recordingSource=saved.source; }
                else {
                    genreStatus = "Finding the mood";
                    final String song = title, performer = artist,release=album;
                    lookup.execute(() -> resolve(ticket, key, song, performer,release,recordingDuration));
                }
            }
            if (BuildProfile.LYRICS && !artist.isEmpty() && !title.isEmpty()) {
                LyricsSignals.Result savedLyrics = lyricCache.get(key);
                if (savedLyrics != null) { lyricCues = savedLyrics.cues; lyricsStatus = savedLyrics.status;lyricsSource=savedLyrics.source; }
                else {
                    lyricsStatus = "Finding lyric cues";
                    final String lyricsSong=title, lyricsArtist=artist,lyricsAlbum=album;
                    lyricsLookup.execute(() -> {
                        if (ticket!=generation || !running) return;
                        LyricMemory memory=new LyricMemory(getContext().getFilesDir());long memoryToken=LyricMemory.epoch();
                        LyricMemory.Entry remembered=memory.get(key,System.currentTimeMillis());
                        LyricsSignals.Result result=remembered==null?LyricsSignals.lookup(lyricsSong,lyricsArtist,lyricsAlbum,recordingDuration):remembered.result;
                        if(remembered==null)memory.put(key,result,new JSONArray(),memoryToken,System.currentTimeMillis());
                        main.post(() -> {
                            if(memoryToken!=LyricMemory.epoch())return;
                            if(!result.retryable)lyricCache.put(key,result.withoutText());
                            if(disposed || !running || ticket!=generation)return;
                            lyricCues=result.cues;lyricsStatus=result.status;lyricsSource=result.source;lyricsRetryNeeded=result.retryable;nextLyricsRetryAt=System.currentTimeMillis()+result.retryAfter;
                            if(remembered!=null&&remembered.vector.length()==384){lyricVector=remembered.vector;semanticCache.put(key,lyricVector);mlStatus="Local ML · saved lyric signals";}
                            if(!result.semanticText.isEmpty()){
                                mlStatus="Reading lyrical atmosphere locally";
                                SemanticMatcher.submit(getContext(),result.semanticText,(vector,status)->main.post(()->{
                                    if(disposed||memoryToken!=LyricMemory.epoch())return;if(vector.length()>0){semanticCache.put(key,vector);try{lyricsLookup.execute(()->memory.put(key,result,vector,memoryToken,System.currentTimeMillis()));}catch(java.util.concurrent.RejectedExecutionException ignored){}}
                                    if(!running||ticket!=generation)return;
                                    lyricVector=vector;mlStatus=status;publish();
                                }));
                            }
                            publish();
                        });
                    });
                }
            }
        }
        playbackListener.changed(playing);
        publish();
    }
    private void publish() {
        try {
            JSONObject value = new JSONObject();
            value.put("title", title); value.put("artist", artist); value.put("genre", genre);
            value.put("album",album);value.put("artistIdentity",artistIdentity);
            value.put("durationMs",controller==null||controller.getMetadata()==null?0:controller.getMetadata().getLong(MediaMetadata.METADATA_KEY_DURATION));
            value.put("recordingSource",recordingSource);value.put("lyricsSource",lyricsSource);
            value.put("playing", playing); value.put("permission", permission); value.put("genreStatus", genreStatus);
            value.put("lyricCues",lyricCues); value.put("lyricsStatus",lyricsStatus);
            value.put("lyricVector",lyricVector);value.put("mlStatus",mlStatus);value.put("artistGenres",artistGenres);value.put("artistStatus",artistStatus);
            String musicState=!PrivacyGate.allowed(getContext())?"access_disabled":!permission?"access_missing":controller==null?"no_active_session":title.isEmpty()?"metadata_missing":playing?"playing":"paused";
            String lyricsState=title.isEmpty()?"not_requested":!BuildProfile.LYRICS?"not_included":lyricsSource.optBoolean("instrumental")?"confirmed_instrumental":lyricsSource.optDouble("maxLyricWeight")>0&&lyricsSource.optDouble("maxLyricWeight")<=.15?"limited_backup":lyricsSource.optLong("id")>0?"matched":lyricsStatus.startsWith("Finding")?"pending":lyricsStatus.contains("unavailable")?"lookup_unavailable":lyricsStatus.isEmpty()?"not_requested":"unmatched";
            value.put("musicState",musicState);value.put("lyricsState",lyricsState);
            if(running){
                JSONObject review=new JSONObject().put("song",new JSONObject().put("title",title).put("artist",artist).put("album",album).put("durationMs",value.optLong("durationMs"))).put("hasLyricVector",lyricVector.length()==384);
                for(String key:new String[]{"genre","playing","recordingSource","lyricsSource","genreStatus","artistStatus","lyricsStatus","mlStatus","artistIdentity","artistGenres","lyricCues","musicState","lyricsState"})review.put(key,value.opt(key));
                String state=review.toString();if(!state.equals(lastReviewState)){lastReviewState=state;review.put("sessionId",reviewSession);ReviewLog.get(getContext()).record("track_state",review);}
            }
            send("window.nowPlaying(" + value + ")");
        } catch (Exception e) { Log.e("MusicScapes", "Cannot show playback state", e); }
    }
    private void resolve(int ticket,String key,String song,String performer,String release,long duration){
        if(ticket!=generation||!running||!PrivacyGate.allowed(getContext()))return;
        TrackMetadata.Result result=TrackMetadata.lookup(song,performer,release,duration);
        main.post(()->{
            if(result.cacheable)cache.put(key,result);
            if(disposed||!running||ticket!=generation)return;
            genre=result.genre;genreStatus=result.status;recordingSource=result.source;nextGenreRetryAt=System.currentTimeMillis()+120000;publish();
        });
    }
    final class Bridge {
        @JavascriptInterface public void screensaver(){main.post(()->{if(!(getContext() instanceof Activity))return;try{getContext().startActivity(new Intent(Settings.ACTION_DREAM_SETTINGS));}catch(Exception e){new android.app.AlertDialog.Builder(getContext()).setTitle("Screen saver setup").setMessage("This TV does not expose the standard screen saver settings. Look in Android TV Settings for Screen saver and choose MusicScapes if available. You can always open MusicScapes as an app while music plays.").setPositiveButton("Return",null).show();}});}
        @JavascriptInterface public void privacy(){main.post(()->{if(getContext() instanceof Activity)PrivacyGate.settings((Activity)getContext(),()->{stop();start();});});}
        @JavascriptInterface public void taste(String raw){if(raw==null||raw.length()>300000)return;try{prefs.edit().putString("taste",TastePrefs.clean(new JSONObject(raw)).toString()).apply();}catch(Exception ignored){}}
        @JavascriptInterface public void room(boolean evening){photoSurface.setEvening(evening);}
        @JavascriptInterface public void reviewEvent(String payload){
            if(payload==null||payload.length()>20000)return;
            try{JSONObject event=new JSONObject(payload);if(!event.optString("kind").matches("image_selected|image_loaded|image_failed|feedback"))return;event.put("sessionId",reviewSession);ReviewLog.get(getContext()).record(event);}catch(Exception ignored){}
        }
        @JavascriptInterface public void review(String command){
            if(!"summary".equals(command)&&!"export".equals(command)&&!"clear".equals(command)&&!"toggle".equals(command)&&!"dev".equals(command))return;
            ReviewLog.get(getContext()).request(command,value->main.post(()->{if(value.optBoolean("cleared")){lyricCache.clear();semanticCache.clear();lyricCues=new JSONObject();lyricVector=new JSONArray();lyricsSource=new JSONObject();lyricsStatus="";lookupKey="";generation++;}send("window.reviewData && window.reviewData("+value+")");}));
        }
        @JavascriptInterface public void photo(String name) { photoSurface.load(name); }
        @JavascriptInterface public void discover(String plan) { OnlineLibrary.get(getContext()).request(plan); }
        @JavascriptInterface public String artistSources(){try{return ArtistPhotoSources.status(getContext()).toString();}catch(Exception ignored){return "{}";}}
        @JavascriptInterface public void save(String settings) {
            try {
                JSONObject v = new JSONObject(settings), clean = new JSONObject();
                String mood = v.optString("mode", "auto");
                if (!mood.matches("auto|country|jazz|electronic|acoustic|folk|chill|rock|classical|pop|hiphop|world")) return;
                clean.put("mode", mood); clean.put("showTrack", v.optBoolean("showTrack", true));
                clean.put("online",v.optBoolean("online",true));
                String style=v.optString("style","auto");if(!style.matches("[a-z]{1,32}"))style="auto";clean.put("style",style);
                String imageType=v.optString("imageType","mixed");if(!imageType.matches("mixed|photos|art|space|artists"))imageType="mixed";clean.put("imageType",imageType);
                String artistPhotos=v.optString("artistPhotos","often");if(!artistPhotos.matches("off|occasional|often"))artistPhotos="often";clean.put("artistPhotos",artistPhotos);clean.put("artistCadenceVersion",2);
                clean.put("interval", Math.min(120, Math.max(15, v.optInt("interval", 35))));
                String pace=v.optString("paceMode","auto"),light=v.optString("roomLight","auto");clean.put("paceMode",pace.equals("fixed")?"fixed":"auto");clean.put("roomLight",light.matches("auto|natural|evening")?light:"auto");
                prefs.edit().putString("settings", clean.toString()).apply();
            } catch (Exception ignored) {}
        }
        @JavascriptInterface public void exit() { main.post(() -> { if (getContext() instanceof Activity) ((Activity)getContext()).finish(); }); }
        @JavascriptInterface public void permission() { main.post(() -> {
            if(BuildProfile.STORE&&getContext() instanceof Activity){PrivacyGate.request((Activity)getContext(),()->{stop();start();});return;}
            try { getContext().startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); }
            catch (Exception e) { Log.w("MusicScapes", "Song access settings unavailable on this TV"); }
        }); }
    }
}
