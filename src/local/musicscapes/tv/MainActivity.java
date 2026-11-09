package local.musicscapes.tv;

import android.app.Activity;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;

public final class MainActivity extends Activity {
    private ScapesView scapes;
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        scapes = new ScapesView(this, playing -> {
            if (playing) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        });
        setContentView(scapes);
    }
    @Override protected void onResume() { super.onResume(); if (scapes != null) scapes.start(); PrivacyGate.first(this,()->{if(scapes!=null){scapes.stop();scapes.start();}}); }
    @Override protected void onPause() { if (scapes != null) scapes.stop(); super.onPause(); }
    @Override protected void onDestroy() { if (scapes != null) scapes.dispose(); super.onDestroy(); }
    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        int code = event.getKeyCode();
        if (scapes != null && (code == 4 || code == 19 || code == 20 || code == 21 || code == 22 || code == 23 || code == 66 || code == 82)) {
            if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) scapes.remote(code);
            return true;
        }
        return super.dispatchKeyEvent(event);
    }
}
