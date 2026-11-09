package local.musicscapes.tv;

/** A small pixel comparison, not an image-understanding model. */
public final class VisualFingerprint {
    public final long hash;
    public final int red,green,blue;
    public VisualFingerprint(long h,int r,int g,int b){hash=h;red=r;green=g;blue=b;}
    public static VisualFingerprint of(int[] pixels){
        if(pixels.length!=72)throw new IllegalArgumentException("Expected a 9x8 sample");
        long hash=0;int red=0,green=0,blue=0;
        for(int y=0;y<8;y++)for(int x=0;x<9;x++){
            int p=pixels[y*9+x];red+=(p>>16)&255;green+=(p>>8)&255;blue+=p&255;
            if(x<8){int q=pixels[y*9+x+1];int a=299*((p>>16)&255)+587*((p>>8)&255)+114*(p&255),b=299*((q>>16)&255)+587*((q>>8)&255)+114*(q&255);hash=(hash<<1)|(a>b?1:0);}
        }
        return new VisualFingerprint(hash,red/72,green/72,blue/72);
    }
    public boolean similar(VisualFingerprint other){int r=red-other.red,g=green-other.green,b=blue-other.blue,bits=Long.bitCount(hash^other.hash),shape=Long.bitCount(hash),otherShape=Long.bitCount(other.hash);return bits<=2&&shape>=8&&shape<=56&&otherShape>=8&&otherShape<=56||bits<=6&&r*r+g*g+b*b<=36*36;}
    public String encode(){return Long.toHexString(hash)+":"+red+":"+green+":"+blue;}
    public static VisualFingerprint decode(String value){try{String[] p=value.split(":");return new VisualFingerprint(Long.parseUnsignedLong(p[0],16),Integer.parseInt(p[1]),Integer.parseInt(p[2]),Integer.parseInt(p[3]));}catch(Exception e){return null;}}
}
