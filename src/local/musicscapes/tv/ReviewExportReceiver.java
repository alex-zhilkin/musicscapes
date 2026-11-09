package local.musicscapes.tv;

import android.content.*;

/** Explicit shell-only export; no network listener and no public history provider. */
public final class ReviewExportReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context,Intent intent){
        if(!"local.musicscapes.tv.EXPORT_REVIEW".equals(intent.getAction()))return;
        final boolean library=intent.getBooleanExtra("includeLibrary",false);
        final PendingResult pending=goAsync();ReviewLog.get(context).request("export",value->{try{
            if(value.has("error"))throw new Exception(value.optString("error"));
            if(library){java.io.File directory=context.getExternalFilesDir("review");if(directory==null)throw new Exception("Review storage unavailable");OnlineLibrary.get(context).exportReview(new java.io.File(directory,"MusicScapes-library.jsonl"));}
            pending.setResultCode(0);pending.setResultData(library?"Review and image library exported":"Review exported");
        }catch(Exception e){pending.setResultCode(1);pending.setResultData(e.getMessage());}finally{pending.finish();}});
    }
}
