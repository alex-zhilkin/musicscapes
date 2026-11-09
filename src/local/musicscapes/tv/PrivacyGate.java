package local.musicscapes.tv;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.provider.Settings;
import android.webkit.WebView;
import android.widget.ScrollView;
import android.widget.TextView;

/** Store consent is checked before accessing another app's media session. */
public final class PrivacyGate {
    public static boolean allowed(Context c){boolean value=!BuildProfile.STORE||c.getSharedPreferences("privacy",0).getBoolean("music",false);SourceConsent.update(value);return value;}
    public static void first(Activity activity,Runnable changed){if(BuildProfile.STORE&&!activity.getSharedPreferences("privacy",0).contains("music"))request(activity,changed);}
    public static void request(Activity activity,Runnable changed){
        show(new AlertDialog.Builder(activity).setTitle("Let pictures follow your music?")
          .setMessage("MusicScapes uses Android notification access to read the current song title, artist, album, duration and playback state while this app or its screensaver is visible. It does not read notification messages, record sound or control playback.\n\nTo find musical styles, it sends those song details over HTTPS to Apple's iTunes catalog, MusicBrainz and Wikidata. Artist-photo discovery sends the artist's identity to Wikimedia Commons. These services also receive your IP address. Other image searches send visual phrases to Wikimedia Commons.\n\nSong details are kept in memory. Optional listening history is off until you enable it in Review. There are no ads or analytics. You can withdraw music access in Privacy and continue choosing pictures manually. Lyrics lookup is unavailable in this store beta.")
          .setPositiveButton("Agree and enable",(d,w)->{activity.getSharedPreferences("privacy",0).edit().putBoolean("music",true).apply();changed.run();try{activity.startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));}catch(Exception ignored){}})
          .setNegativeButton("Use without music",(d,w)->{activity.getSharedPreferences("privacy",0).edit().putBoolean("music",false).apply();changed.run();})
          .setNeutralButton("Privacy policy",(d,w)->policy(activity,()->request(activity,changed)))
          .setOnCancelListener(d->{/* Back never grants permission. */}).create(),false);
    }
    public static void settings(Activity activity,Runnable changed){
        if(!BuildProfile.STORE){policy(activity,null);return;}
        String[] choices={allowed(activity)?"Turn off music matching":"Enable music matching","Privacy policy","Android notification access","Return"};
        show(new AlertDialog.Builder(activity).setTitle("Privacy · MusicScapes").setItems(choices,(d,w)->{
            if(w==0){if(allowed(activity)){activity.getSharedPreferences("privacy",0).edit().putBoolean("music",false).apply();changed.run();}else request(activity,changed);}
            if(w==1)policy(activity,null);
            if(w==2)try{activity.startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));}catch(Exception ignored){}
        }).create(),false);
    }
    private static void policy(Activity activity,Runnable after){
        // A local text view is readable with a TV remote and never loads remote code.
        String text="";try(java.io.InputStream in=activity.getAssets().open("privacy.txt");java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream()){byte[] bytes=new byte[4096];int n;while((n=in.read(bytes))!=-1)out.write(bytes,0,n);text=out.toString("UTF-8");}catch(Exception e){text="MusicScapes uses song details to match pictures. The store privacy policy is included with store builds. Contact Plum Productions at plumprods@gmail.com.";}
        float density=activity.getResources().getDisplayMetrics().density;
        TextView view=new TextView(activity);view.setText(text);view.setTextSize(16);view.setTextColor(android.graphics.Color.rgb(216,225,227));view.setLineSpacing(0,1.2f);view.setPadding((int)(24*density),(int)(12*density),(int)(24*density),(int)(12*density));ScrollView scroll=new ScrollView(activity);scroll.setFocusable(true);scroll.addView(view);
        show(new AlertDialog.Builder(activity).setTitle("Privacy policy").setView(scroll).setPositiveButton("Return",(d,w)->{if(after!=null)after.run();}).setOnCancelListener(d->{if(after!=null)after.run();}).create(),text.length()>1200);
    }
    private static void show(AlertDialog dialog,boolean tall){
        dialog.show();android.view.Window window=dialog.getWindow();if(window==null)return;
        android.util.DisplayMetrics metrics=dialog.getContext().getResources().getDisplayMetrics();
        android.graphics.drawable.GradientDrawable background=new android.graphics.drawable.GradientDrawable();background.setColor(android.graphics.Color.rgb(16,43,62));background.setCornerRadius(12*metrics.density);window.setBackgroundDrawable(background);window.setDimAmount(.2f);window.setLayout((int)(metrics.widthPixels*.72f),tall?(int)(metrics.heightPixels*.8f):android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
        for(int which:new int[]{AlertDialog.BUTTON_POSITIVE,AlertDialog.BUTTON_NEGATIVE,AlertDialog.BUTTON_NEUTRAL}){android.widget.Button button=dialog.getButton(which);if(button!=null){button.setAllCaps(false);button.setTextSize(14);button.setTextColor(android.graphics.Color.rgb(242,238,227));}}
    }
}
