package local.musicscapes.tv;
import android.content.*;
import java.io.*;
import org.json.*;

/** DUMP-protected local setup; keys never pass through Javascript, logs or exports. */
public final class ArtistSourcesReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context c,Intent i){
        if(!"local.musicscapes.tv.ARTIST_SOURCES".equals(i.getAction()))return;
        if(BuildProfile.STORE){setResultCode(1);setResultData("Personal builds only");return;}
        File folder=c.getExternalFilesDir("setup"),file=folder==null?null:new File(folder,"artist-source-keys.json");
        try{
            if(i.getBooleanExtra("import",false)){
                if(file==null||!file.isFile()||file.length()>4096)throw new IOException("Setup file missing or too large");
                try(InputStream in=new FileInputStream(file);ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] b=new byte[1024];int n;while((n=in.read(b))!=-1){if(out.size()+n>4096)throw new IOException("Setup file too large");out.write(b,0,n);}ArtistPhotoSources.configure(c,new JSONObject(out.toString("UTF-8")));}
            }
            setResultCode(0);setResultData(ArtistPhotoSources.status(c).toString());
        }catch(Exception e){setResultCode(1);setResultData("Artist source configuration unavailable");}
        finally{if(i.getBooleanExtra("import",false)&&file!=null)file.delete();}
    }
}
