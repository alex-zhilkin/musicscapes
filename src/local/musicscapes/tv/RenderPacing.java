package local.musicscapes.tv;

/** Render fades smoothly, slow photographic drift economically, and park still frames. */
public final class RenderPacing {
    static final long MOTION_MILLIS=90000;
    private RenderPacing(){}
    static long delay(boolean hasImage,boolean fading,long age,boolean shading){
        if(fading||shading)return 41;
        if(hasImage&&age<MOTION_MILLIS)return 83;
        return 0; // Wait for a picture, lifecycle or room-light change, without polling.
    }
}
