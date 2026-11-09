package local.musicscapes.tv;
import org.json.*;
import java.text.Normalizer;
import java.util.*;
import java.util.regex.*;

/** Source identity and explicitly named places, never inferred from photographer names. */
public final class ImageDiversity {
    public static String creator(String name){
        String value=Normalizer.normalize(DiscoveryRules.clean(name),Normalizer.Form.NFKD).replaceAll("\\p{M}","").toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+"," ").trim();
        if(value.length()<3||value.matches("own work|unknown|anonymous|public domain|various|not provided"))return "";
        return value.length()>120?value.substring(0,120):value;
    }
    public static JSONArray places(String evidence){
        LinkedHashSet<String> out=new LinkedHashSet<>();
        Matcher m=Pattern.compile("\\b(?:in|of|over|near|from|at)\\s+([A-Z][\\p{L}'-]+(?:\\s+[A-Z][\\p{L}'-]+){0,3})").matcher(evidence);
        while(m.find()&&out.size()<6){String place=creator(m.group(1));if(place.length()>=4&&!place.matches(".*\\b(the|a|an|own|national|park|mountain|mountains|sea|water|night|sunset|sunrise|landscape|city|flickr|nasa|wikimedia|commons|earth|jupiter|saturn)\\b.*"))out.add(place);}
        return new JSONArray(out);
    }
    public static boolean artworkSubject(String evidence){
        return artworkSubject(evidence,evidence);
    }
    public static boolean artworkSubject(String title,String evidence){
        String text=evidence.toLowerCase(Locale.ROOT);
        if(text.matches("(?s).*\\b(artist at work|painter at work|painting class|people painting|art studio|gallery interior|gallery opening|exhibition opening|vernissage|artist interview|painter portrait|artist portrait|surrounded by (?:colorful )?paintings|adds finishing touches|artist working)\\b.*"))return false;
        Matcher named=Pattern.compile("^([\\p{Lu}][\\p{L}'’.-]+(?:\\s+[\\p{Lu}][\\p{L}'’.-]+){1,5})\\s+(?:at|during|visiting|presenting)\\s").matcher(title);
        return !(named.find()&&Pattern.compile(Pattern.quote(named.group(1))+"\\s+(?:during|visiting|presenting)\\b.{0,120}\\b(?:exhibition|solo show|gallery)\\b",Pattern.CASE_INSENSITIVE|Pattern.UNICODE_CASE|Pattern.DOTALL).matcher(evidence).find());
    }
    public static String series(String title,String identity){
        String clean=title.replaceFirst("(?i)\\.(jpe?g|png|webp)$","").replace('_',' '),base=clean.replaceFirst("(?:\\s*[-–—]\\s*|\\s+)\\d{1,3}\\s*$","").trim();
        if(base.equals(clean)||base.length()<12)return "";
        return creator(identity)+"|"+creator(base);
    }
    public static int creatorLimit(int photographs){return Math.max(3,Math.min(12,(int)Math.ceil(photographs*.025)));}
}
