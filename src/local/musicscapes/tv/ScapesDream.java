package local.musicscapes.tv;

import android.service.dreams.DreamService;

public final class ScapesDream extends DreamService {
    private ScapesView scapes;
    @Override public void onAttachedToWindow() {
        super.onAttachedToWindow();
        setInteractive(false); setFullscreen(true); setScreenBright(true);
        scapes = new ScapesView(this, playing -> {});
        setContentView(scapes);
    }
    @Override public void onDreamingStarted() { super.onDreamingStarted(); if (scapes != null) scapes.start(); }
    @Override public void onDreamingStopped() { if (scapes != null) scapes.stop(); super.onDreamingStopped(); }
    @Override public void onDetachedFromWindow() { if (scapes != null) scapes.dispose(); super.onDetachedFromWindow(); }
}
