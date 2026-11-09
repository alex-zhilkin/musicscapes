package local.musicscapes.tv;

import android.content.*;
import org.json.*;
import java.io.*;
import java.net.*;
import java.security.*;
import java.security.cert.*;
import java.util.concurrent.*;
import javax.net.ssl.*;

/** Opt-in HTTPS through adb reverse. The only destination allowed is loopback. */
public final class DevLogClient {
    private static DevLogClient instance;
    public static synchronized DevLogClient get(Context c){if(instance==null)instance=new DevLogClient(c.getApplicationContext());return instance;}
    private final Context context;private final SharedPreferences prefs;
    private final ScheduledExecutorService network=Executors.newSingleThreadScheduledExecutor();
    private ScheduledFuture<?> pending;private volatile String status="Off";private int failures,busyGeneration=-1;private volatile int generation;
    private DevLogClient(Context c){context=c;prefs=c.getSharedPreferences("dev-logs",Context.MODE_PRIVATE);}
    public boolean configured(){return prefs.getString("token","").matches("[a-f0-9]{64}")&&prefs.getString("pin","").matches("[a-f0-9]{64}");}
    public boolean enabled(){return configured()&&prefs.getBoolean("enabled",false);}
    private boolean active(){return enabled()&&context.getSharedPreferences("review",Context.MODE_PRIVATE).getBoolean("enabled",true);}
    public synchronized boolean configure(int port,String token,String pin,boolean enabled,boolean replay){
        if(port<1024||port>65535||token==null||pin==null||!token.matches("[a-f0-9]{64}")||!pin.matches("[a-f0-9]{64}"))return false;
        boolean changed=!token.equals(prefs.getString("token",""))||!pin.equals(prefs.getString("pin",""));SharedPreferences.Editor e=prefs.edit().putInt("port",port).putString("token",token).putString("pin",pin).putBoolean("enabled",enabled);if(changed||replay)e.remove("ack").remove("lastAckAt");e.apply();reset();return true;
    }
    public synchronized void toggle(){if(!configured())return;prefs.edit().putBoolean("enabled",!enabled()).apply();reset();}
    public synchronized void reset(){generation++;busyGeneration=-1;failures=0;if(pending!=null)pending.cancel(false);pending=null;status=active()?"Waiting for computer":enabled()?"Logging paused":"Off";available();}
    public synchronized void available(){if(active()&&pending==null&&busyGeneration!=generation){final int ticket=generation;pending=network.schedule(()->flush(ticket),1500,TimeUnit.MILLISECONDS);}}
    public synchronized JSONObject state(){JSONObject out=new JSONObject();try{out.put("devConfigured",configured()).put("devEnabled",enabled()).put("devStatus",active()?status:enabled()?"Logging paused":"Off").put("devLastAckAt",prefs.getLong("lastAckAt",0));}catch(Exception ignored){}return out;}
    private void flush(int ticket){synchronized(this){if(ticket!=generation||!active())return;pending=null;busyGeneration=ticket;}
        ReviewLog.get(context).batch(prefs.getString("ack",""),events->network.execute(()->send(ticket,events)));
    }
    private void send(int ticket,JSONArray events){if(ticket!=generation||!active())return;if(events==null||events.length()==0){synchronized(this){if(busyGeneration==ticket)busyGeneration=-1;}status=events==null?"Review storage unavailable":"Up to date · no pending records";return;}HttpsURLConnection c=null;boolean again=false;
        try{
            String pin=prefs.getString("pin","");SSLContext tls=SSLContext.getInstance("TLS");tls.init(null,new TrustManager[]{new X509TrustManager(){public X509Certificate[] getAcceptedIssuers(){return new X509Certificate[0];}public void checkClientTrusted(X509Certificate[] chain,String auth)throws CertificateException{throw new CertificateException("Unexpected client certificate");}public void checkServerTrusted(X509Certificate[] chain,String auth)throws CertificateException{try{if(chain==null||chain.length==0)throw new CertificateException("Missing computer certificate");chain[0].checkValidity();StringBuilder hash=new StringBuilder();for(byte b:MessageDigest.getInstance("SHA-256").digest(chain[0].getEncoded()))hash.append(String.format(java.util.Locale.ROOT,"%02x",b&255));if(!MessageDigest.isEqual(pin.getBytes("US-ASCII"),hash.toString().getBytes("US-ASCII")))throw new CertificateException("Computer certificate changed");}catch(CertificateException e){throw e;}catch(Exception e){throw new CertificateException("Computer certificate invalid");}}}},new SecureRandom());
            c=(HttpsURLConnection)new URL("https://127.0.0.1:"+prefs.getInt("port",8766)+"/v1/events").openConnection();c.setSSLSocketFactory(tls.getSocketFactory());c.setInstanceFollowRedirects(false);c.setConnectTimeout(3000);c.setReadTimeout(4000);c.setRequestMethod("POST");c.setDoOutput(true);c.setRequestProperty("Authorization","Bearer "+prefs.getString("token",""));c.setRequestProperty("Content-Type","application/json");
            byte[] body=new JSONObject().put("schema",1).put("events",events).toString().getBytes("UTF-8");c.setFixedLengthStreamingMode(body.length);try(OutputStream out=c.getOutputStream()){out.write(body);}int code=c.getResponseCode();if(code!=200)throw new IOException("HTTP "+code);
            byte[] raw;try(InputStream in=c.getInputStream();ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] b=new byte[1024];int n;while((n=in.read(b))!=-1){if(out.size()+n>4096)throw new IOException("Large acknowledgement");out.write(b,0,n);}raw=out.toByteArray();}
            String expected=events.getJSONObject(events.length()-1).getString("eventId"),ack=new JSONObject(new String(raw,"UTF-8")).optString("ack");if(!expected.equals(ack))throw new IOException("Incomplete acknowledgement");if(ticket!=generation||!active())return;
            prefs.edit().putString("ack",ack).putLong("lastAckAt",System.currentTimeMillis()).apply();failures=0;status="Connected · sending locally";again=true;
        }catch(Exception e){if(ticket!=generation)return;status="Computer unavailable · history kept on TV";int delay=Math.min(120,5*(1<<Math.min(5,failures++)));synchronized(this){if(active()&&pending==null)pending=network.schedule(()->flush(ticket),delay,TimeUnit.SECONDS);}}
        finally{if(c!=null)c.disconnect();synchronized(this){if(busyGeneration==ticket)busyGeneration=-1;if(again&&ticket==generation)available();}}
    }
}
