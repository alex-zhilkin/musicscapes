package local.musicscapes.tv;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.Log;
import android.view.Display;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.WindowManager;
import java.io.InputStream;
import java.io.FileInputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** A separate full-resolution buffer keeps photos sharp when Android's UI is 1080p. */
public final class PhotoSurface extends SurfaceView implements SurfaceHolder.Callback {
    public interface LoadListener{void result(String key,boolean loaded);}
    private volatile LoadListener loadListener;
    private String pendingName;
    public void setLoadListener(LoadListener listener){loadListener=listener;}
    private void failed(String name,int ticket){LoadListener listener=loadListener;if(!disposed&&ticket==request&&listener!=null)listener.result(name,false);}
    private final ExecutorService decoder = Executors.newSingleThreadExecutor(r->new Thread(()->{android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);r.run();},"MusicScapes-decode"));
    private final Object lock = new Object();
    private volatile boolean surfaceAlive, active = true, disposed;
    private volatile int request;
    private Bitmap pending, current, previous;
    private Thread renderer;
    private long started, previousStarted;
    private double pendingBrightness=.4,currentBrightness=.4,previousBrightness=.4;
    private volatile boolean evening;
    private long revision;
    private float shade;
    private long fadeMillis=3500;
    private String renderedName;
    private int renderedTicket;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final RectF rect = new RectF();

    public PhotoSurface(Context context) {
        super(context);
        Display display = ((WindowManager)context.getSystemService(Context.WINDOW_SERVICE)).getDefaultDisplay();
        Display.Mode mode = display.getMode();
        int width = Math.min(3840, Math.max(mode.getPhysicalWidth(), mode.getPhysicalHeight()));
        int height = Math.round(width * 9f / 16f);
        getHolder().setFixedSize(width, height);
        getHolder().addCallback(this);
        Log.i("MusicScapes", "Photo buffer requested: " + width + "x" + height);
    }
    public void load(String name) {
        if (disposed || !name.matches("[a-z0-9]{1,80}")) return;
        OnlineLibrary.get(getContext()).pin(name);
        final int ticket = ++request;
        decoder.execute(() -> {
            if (ticket != request || disposed) return;
            try (InputStream input = name.startsWith("web")?new FileInputStream(OnlineLibrary.get(getContext()).image(name)):getContext().getAssets().open("photos/" + name + ".jpg")) {
                BitmapFactory.Options options = new BitmapFactory.Options();
                options.inPreferredConfig = Bitmap.Config.RGB_565;
                Bitmap image = BitmapFactory.decodeStream(input, null, options);
                if (image == null) {failed(name,ticket);return;}
                synchronized (lock) {
                    if (disposed || ticket != request) { image.recycle(); return; }
                    if (pending != null) pending.recycle();
                    pending = image;
                    pendingName=name;
                    Bitmap sample=Bitmap.createScaledBitmap(image,32,18,true);try{int[] pixels=new int[576];sample.getPixels(pixels,0,32,0,0,32,18);double sum=0;for(int p:pixels)sum+=(.2126*((p>>16)&255)+.7152*((p>>8)&255)+.0722*(p&255))/255;pendingBrightness=sum/pixels.length;}finally{if(sample!=image)sample.recycle();}
                    revision++;lock.notifyAll();
                }
            } catch (Exception | OutOfMemoryError e) { Log.e("MusicScapes", "Photo could not be loaded: " + name, e);failed(name,ticket); }
        });
    }
    public void setActive(boolean value) { synchronized(lock){active=value;revision++;lock.notifyAll();} }
    public void setEvening(boolean value){synchronized(lock){if(evening!=value){evening=value;revision++;lock.notifyAll();}}}
    @Override public void surfaceCreated(SurfaceHolder holder) {
        surfaceAlive = true;
        renderer = new Thread(() -> {
            boolean reported = false;
            long lastFrame=android.os.SystemClock.uptimeMillis();
            while (surfaceAlive && !disposed) {
                long seen;
                synchronized(lock){
                    while(!active&&surfaceAlive&&!disposed)try{lock.wait();}catch(InterruptedException e){Thread.currentThread().interrupt();return;}
                    if(!surfaceAlive||disposed)break;
                    seen=revision;
                }
                long frameStart = android.os.SystemClock.uptimeMillis();
                long frameDelta=Math.min(1000,Math.max(1,frameStart-lastFrame));lastFrame=frameStart;
                if (active) {
                    synchronized (lock) {
                        if (pending != null) {
                            if (previous != null) previous.recycle();
                            previous = current; previousStarted = started;
                            current = pending; pending = null; started = frameStart;
                            previousBrightness=currentBrightness;currentBrightness=pendingBrightness;fadeMillis=(evening?5000:3500)+(long)(Math.abs(currentBrightness-previousBrightness)*2000);renderedName=pendingName;renderedTicket=request;pendingName=null;
                        }
                    }
                    Canvas canvas = null;
                    try {
                        canvas = holder.lockHardwareCanvas();
                        if (canvas != null) {
                            if (!reported) { Log.i("MusicScapes", "Photo canvas: " + canvas.getWidth() + "x" + canvas.getHeight()); reported = true; }
                            canvas.drawColor(Color.rgb(16,26,24));
                            float fade = Math.min(1f, (frameStart - started) / (float)fadeMillis);
                            if (previous != null && fade < 1f) draw(canvas, previous, previousStarted, frameStart, 255);
                            if (current != null) draw(canvas, current, started, frameStart, previous == null ? 255 : (int)(255 * fade));
                            if (fade >= 1f && previous != null) { previous.recycle(); previous = null; }
                            float targetShade=evening?(float)(.16+Math.max(0,currentBrightness-.65)*.35):0;shade+=(targetShade-shade)*(1-(float)Math.exp(-frameDelta/1600d));if(Math.abs(targetShade-shade)<.001f)shade=targetShade;if(shade>.001f){paint.setAlpha((int)(shade*255));canvas.drawColor(Color.argb((int)(shade*255),0,0,0));}
                        }
                    } catch (Exception e) { if (surfaceAlive) Log.w("MusicScapes", "Photo surface interrupted", e); }
                    finally { if (canvas != null) try{holder.unlockCanvasAndPost(canvas);if(renderedName!=null&&renderedTicket==request&&(previous==null||frameStart-started>160)){String key=renderedName;renderedName=null;ImageHistory.get(getContext()).record(key);LoadListener listener=loadListener;if(listener!=null)listener.result(key,true);}}catch(RuntimeException e){if(surfaceAlive)Log.w("MusicScapes","Photo surface closed during a frame");} }
                }
                float targetShade=evening?(float)(.16+Math.max(0,currentBrightness-.65)*.35):0;
                long period=RenderPacing.delay(current!=null,previous!=null,frameStart-started,Math.abs(targetShade-shade)>.001f);
                synchronized(lock){if(revision==seen&&pending==null&&surfaceAlive&&!disposed)try{if(period==0)lock.wait();else lock.wait(Math.max(1,period-(android.os.SystemClock.uptimeMillis()-frameStart)));}catch(InterruptedException e){Thread.currentThread().interrupt();break;}}
            }
        }, "MusicScapes-4K");
        renderer.start();
    }
    private void draw(Canvas canvas, Bitmap image, long since, long now, int alpha) {
        float t = Math.min(1f, Math.max(0, now - since) / (float)RenderPacing.MOTION_MILLIS);
        float scale = Math.max((float)canvas.getWidth()/image.getWidth(), (float)canvas.getHeight()/image.getHeight()) * (1.002f + .015f*t);
        float w=image.getWidth()*scale, h=image.getHeight()*scale;
        float x=(canvas.getWidth()-w)/2f, y=(canvas.getHeight()-h)/2f;
        rect.set(x,y,x+w,y+h); paint.setAlpha(alpha); canvas.drawBitmap(image,null,rect,paint);
    }
    @Override public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {}
    @Override public void surfaceDestroyed(SurfaceHolder holder) {
        surfaceAlive = false;
        if (renderer != null) {
            renderer.interrupt();
            try { renderer.join(1500); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
    }
    public void dispose() {
        disposed = true; request++; surfaceAlive = false; decoder.shutdownNow();
        if (renderer != null) {
            renderer.interrupt();
            try { renderer.join(1500); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
        synchronized (lock) {
            if (pending != null) { pending.recycle(); pending=null; }
            if (current != null) { current.recycle(); current=null; }
            if (previous != null) { previous.recycle(); previous=null; }
        }
    }
}
