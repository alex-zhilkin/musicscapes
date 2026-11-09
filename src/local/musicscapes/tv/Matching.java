package local.musicscapes.tv;

import java.text.Normalizer;
import java.util.Locale;

final class Matching {
    static String normalized(String s) {
        return Normalizer.normalize(s.toLowerCase(Locale.ROOT), Normalizer.Form.NFD).replaceAll("\\p{M}", "").replaceAll("\\([^)]*\\)|\\[[^]]*\\]", " ").replaceAll("[^\\p{L}\\p{N}]", "");
    }
    static double similarity(String x, String y) {
        String a=normalized(x), b=normalized(y);
        if(a.isEmpty()||b.isEmpty())return 0;
        if(a.equals(b))return 1;
        if(a.contains(b)||b.contains(a))return (double)Math.min(a.length(),b.length())/Math.max(a.length(),b.length());
        return 0;
    }
}
