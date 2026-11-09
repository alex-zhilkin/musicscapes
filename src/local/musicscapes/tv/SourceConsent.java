package local.musicscapes.tv;
import java.net.URL;import java.net.URLConnection;import java.io.IOException;
/** Checked at each metadata request boundary, including redirects and fallbacks. */
public final class SourceConsent {
    private static volatile boolean allowed=!BuildProfile.STORE;
    private static final Object musicBrainzLock=new Object();
    private static long nextMusicBrainzRequest;
    public static void update(boolean value){allowed=value;}
    public static URLConnection open(URL url)throws IOException{
        if(!allowed)throw new IOException("Music matching is disabled");
        if(url.getHost().equals("musicbrainz.org")||url.getHost().equals("www.musicbrainz.org"))synchronized(musicBrainzLock){
            long wait=nextMusicBrainzRequest-System.currentTimeMillis();if(wait>0)try{Thread.sleep(wait);}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IOException("Metadata request cancelled");}
            if(!allowed)throw new IOException("Music matching is disabled");
            nextMusicBrainzRequest=System.currentTimeMillis()+1200;
        }
        return url.openConnection();
    }
}
