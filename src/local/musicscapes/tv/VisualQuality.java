package local.musicscapes.tv;
import org.json.*;

/** Small pixel measurements, not a learned beauty or text-recognition model. */
public final class VisualQuality {
    private static double clamp(double x){return Math.max(0,Math.min(1,x));}
    /** Keep high-contrast detail inside a 16:9 crop; a centred crop wins ties. */
    public static double cropOrigin(int[] pixels,int w,int h){boolean vertical=w/(double)h<16d/9;int window=vertical?(int)Math.round(w*9d/16):(int)Math.round(h*16d/9),axis=vertical?h:w,range=axis-window;if(range<=0)return .5;double[] energy=new double[axis];for(int y=1;y<h-1;y++)for(int x=1;x<w-1;x++){int p=pixels[y*w+x],q=pixels[y*w+x+1],r=pixels[(y+1)*w+x];double a=((p>>16)&255)+((p>>8)&255)+(p&255),b=((q>>16)&255)+((q>>8)&255)+(q&255),c=((r>>16)&255)+((r>>8)&255)+(r&255);energy[vertical?y:x]+=(Math.abs(a-b)+Math.abs(a-c))/1530d;}double total=0;for(double e:energy)total+=e;double best=Double.POSITIVE_INFINITY,origin=.5;for(int step=0;step<=4;step++){double pos=step/4d;int start=(int)Math.round(range*pos),end=start+window;double cut=0;for(int i=0;i<axis;i++)if(i<start||i>=end)cut+=energy[i];double cost=cut/Math.max(1,total)+Math.abs(pos-.5)*.025;if(cost<best){best=cost;origin=pos;}}return origin;}
    public static JSONObject measure(int[] pixels,int width,int height)throws Exception{
        if(width<8||height<8||pixels.length!=width*height)throw new IllegalArgumentException("Invalid visual sample");
        double[] y=new double[pixels.length],palette=new double[8];double sum=0,square=0,red=0,green=0,blue=0,sat=0,min=1,max=0;int black=0,white=0;
        for(int i=0;i<pixels.length;i++){int p=pixels[i];double r=((p>>16)&255)/255d,g=((p>>8)&255)/255d,b=(p&255)/255d,l=.2126*r+.7152*g+.0722*b;y[i]=l;sum+=l;square+=l*l;red+=r;green+=g;blue+=b;min=Math.min(min,l);max=Math.max(max,l);if(l<.012)black++;if(l>.985)white++;double hi=Math.max(r,Math.max(g,b)),lo=Math.min(r,Math.min(g,b)),s=hi==0?0:(hi-lo)/hi;sat+=s;double hue=0;if(hi>lo){hue=hi==r?((g-b)/(hi-lo)+6)%6:hi==g?(b-r)/(hi-lo)+2:(r-g)/(hi-lo)+4;hue/=6;}palette[Math.min(7,(int)(hue*8))]+=s;}
        int n=pixels.length;double mean=sum/n,sd=Math.sqrt(Math.max(0,square/n-mean*mean)),edges=0;int busy=0,samples=0;double left=0,right=0;
        for(int row=1;row<height-1;row++)for(int col=1;col<width-1;col++){int i=row*width+col;double e=(Math.abs(y[i+1]-y[i-1])+Math.abs(y[i+width]-y[i-width]))/2;edges+=e;samples++;if(e>.16)busy++;if(col<width/2)left+=e;else right+=e;}
        double detail=clamp(edges/samples*18),clutter=busy/(double)samples,balance=1-Math.abs(left-right)/Math.max(.01,left+right),clip=white/(double)n;
        boolean blank=(max-min<.025&&sd<.004)||(white>n*.997)||(black>n*.997);
        double score=blank?0:clamp(.56+.13*detail+.1*clamp(sd*4)+.08*balance-.18*clip-.16*Math.max(0,clutter-.3));
        JSONArray bins=new JSONArray();for(double v:palette)bins.put(sat>0?v/sat:0);
        return new JSONObject().put("schema",1).put("accepted",!blank).put("quality",score).put("brightness",mean).put("contrast",sd).put("detail",detail).put("clutter",clutter).put("whiteClip",clip).put("darkFraction",black/(double)n).put("temperature",(red-blue)/n).put("saturation",sat/n).put("palette",bins).put("energy",clamp(.15+.45*detail+.4*clutter+.25*sd)).put("evidence","Pixel exposure, detail, edge density and colour; not a beauty judgement or OCR");
    }
}
