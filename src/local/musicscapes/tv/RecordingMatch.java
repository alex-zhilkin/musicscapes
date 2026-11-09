package local.musicscapes.tv;

import java.text.Normalizer;
import java.util.*;
import java.util.regex.*;

/** Recording identity: remaster labels are cosmetic; live/remix/acoustic versions are not. */
public final class RecordingMatch {
    public static final class Fit {
        public final double score;public final String confidence;public final boolean album,duration;
        Fit(double score,String confidence,boolean album,boolean duration){this.score=score;this.confidence=confidence;this.album=album;this.duration=duration;}
    }
    private static final Pattern GROUP=Pattern.compile("\\([^)]*\\)|\\[[^]]*\\]");
    static String text(String value){return Normalizer.normalize(value==null?"":value.toLowerCase(Locale.ROOT),Normalizer.Form.NFD).replaceAll("\\p{M}","").replace('’','\'').replaceAll("['`]","").replaceAll("[^\\p{L}\\p{N}]+"," ").trim();}
    private static boolean cosmetic(String group){String t=text(group);return t.matches("(?:(?:[0-9]{4} )?remaster(?:ed)?(?: [0-9]{4})?|(?:deluxe|expanded|anniversary|special|bonus track)(?: edition| version)?|original mix)");}
    private static boolean version(String group){return text(group).matches(".*\\b(live|acoustic|unplugged|remix|instrumental|karaoke|demo|radio edit|sped up|slowed|rerecorded|re recorded|taylors version|clean version|explicit version)\\b.*");}
    public static String title(String value){
        String v=value==null?"":value;Matcher m=GROUP.matcher(v);StringBuffer out=new StringBuffer();
        while(m.find()){String group=m.group().substring(1,m.group().length()-1);m.appendReplacement(out,cosmetic(group)||version(group)||text(group).matches("^(feat|ft|featuring) .*")?" ":Matcher.quoteReplacement(m.group()));}m.appendTail(out);
        String result=out.toString();int dash;while((dash=result.lastIndexOf(" - "))>=0){String suffix=result.substring(dash+3);if(!cosmetic(suffix)&&!version(suffix))break;result=result.substring(0,dash);}
        return text(result);
    }
    public static String album(String value){String v=value==null?"":value;Matcher m=GROUP.matcher(v);StringBuffer out=new StringBuffer();while(m.find()){String group=m.group().substring(1,m.group().length()-1);m.appendReplacement(out,cosmetic(group)?" ":Matcher.quoteReplacement(m.group()));}m.appendTail(out);return text(out.toString());}
    static Set<String> versions(String title,String album){
        StringBuilder labels=new StringBuilder();Matcher m=GROUP.matcher(title==null?"":title);while(m.find())if(version(m.group()))labels.append(' ').append(m.group());
        for(String suffix:qualifiers(title))if(version(suffix))labels.append(' ').append(suffix);
        String a=text(album);if(a.matches("^live(?: at| in| from|$).*"))labels.append(" live ");if(a.contains("unplugged"))labels.append(" acoustic ");if(a.contains("taylors version")||a.contains("re recorded"))labels.append(" rerecorded ");
        String l=text(labels.toString());Set<String> result=new TreeSet<>();
        for(String flag:new String[]{"live","acoustic","remix","instrumental","karaoke","demo","radio edit","sped up","slowed","rerecorded","clean version","explicit version"})if(Pattern.compile("\\b"+flag+"\\b").matcher(l).find())result.add(flag);
        if(l.contains("unplugged"))result.add("acoustic");if(l.contains("taylors version")||l.contains("re recorded"))result.add("rerecorded");return result;
    }
    private static Set<String> credits(String artist){Set<String> result=new TreeSet<>();for(String part:artist.split("(?i)\\s+(?:feat\\.?|ft\\.?|featuring|with|and)\\s+|[,;&+]")){String t=text(part);if(!t.isEmpty())result.add(t);}return result;}
    private static String artistName(String value){return text(value).replaceAll("\\band\\b","").replaceAll("\\s+","");}
    private static List<String> qualifiers(String value){List<String> result=new ArrayList<>();String v=value==null?"":value;Matcher m=GROUP.matcher(v);while(m.find())result.add(m.group().substring(1,m.group().length()-1));v=GROUP.matcher(v).replaceAll(" ");int dash;while((dash=v.lastIndexOf(" - "))>=0){String suffix=v.substring(dash+3);if(!cosmetic(suffix)&&!version(suffix))break;result.add(suffix);v=v.substring(0,dash);}return result;}
    private static String detailedVersion(String value){Set<String> detail=new TreeSet<>();for(String label:qualifiers(value)){String t=text(label).replaceAll("\\bversion\\b","").trim();if(version(t)&&!t.matches("live|acoustic|unplugged|remix|instrumental|karaoke|demo|radio edit|sped up|slowed"))detail.add(t);}return String.join(" / ",detail);}
    public static Fit fit(String expectedTitle,String expectedArtist,String expectedAlbum,long expectedMs,String candidateTitle,String candidateArtist,String candidateAlbum,double candidateSeconds){
        String title=title(expectedTitle),artist=text(expectedArtist);if(title.isEmpty()||artist.isEmpty()||!title.equals(title(candidateTitle)))return null;
        if(!versions(expectedTitle,expectedAlbum).equals(versions(candidateTitle,candidateAlbum)))return null;
        String leftVersion=detailedVersion(expectedTitle),rightVersion=detailedVersion(candidateTitle);if(!leftVersion.isEmpty()&&!rightVersion.isEmpty()&&!leftVersion.equals(rightVersion))return null;
        Set<String> a=credits(expectedArtist),b=credits(candidateArtist);boolean sameArtist=artistName(expectedArtist).equals(artistName(candidateArtist))||a.equals(b);
        boolean albumKnown=!album(expectedAlbum).isEmpty(),sameAlbum=albumKnown&&album(expectedAlbum).equals(album(candidateAlbum));
        boolean hasDuration=expectedMs>0&&candidateSeconds>0;double delta=hasDuration?Math.abs(expectedMs/1000.0-candidateSeconds):0;
        if(leftVersion.isEmpty()!=rightVersion.isEmpty()&&!(sameAlbum&&hasDuration))return null;
        if(hasDuration&&delta>2.0)return null;
        if(expectedMs>0&&candidateSeconds<=0)return null;
        // Incomplete collaboration credits need two independent recording confirmations.
        if(!sameArtist&&!(sameAlbum&&hasDuration&&a.size()==1&&b.containsAll(a)))return null;
        String confidence=hasDuration?(sameAlbum?"high":"good"):(sameAlbum?"good":"limited");
        return new Fit(2+(sameAlbum?.3:0)+(hasDuration?.2-.025*delta:0),confidence,sameAlbum,hasDuration);
    }
}
