package local.musicscapes.tv;

import org.json.*;
import java.util.*;
import java.util.regex.Pattern;

/** Caption/category evidence is token-boundary matched. Unmentioned moods are not invented. */
public final class VisualTags {
    private static final LinkedHashMap<String,Pattern> RULES=new LinkedHashMap<>();
    static{
        add("rural","rural|countryside|farmland|farm|farms|farmhouse|farmhouses|agricultural|barn|pasture|prairie|ranch");add("pastoral","pastoral|pasture|farmland|meadow");add("road","road|roads|highway|lane|motorway|path|paths|trail|trails");add("home","home|house|houses|cabin|cabins|cottage|cottages|village|villages|farmhouse|farmhouses");
        add("forest","forest|forests|woodland|wooded|woods|pine|pines|trees");add("wood","wooden|timber|woodland|woodwork");add("mountain","mountain|mountains|mountainous|foothills|peak|peaks|alpine|glacier|glaciers");add("desert","desert|dunes|dune|badlands|sandstone|canyon");
        add("water","water|river|rivers|lake|lakes|ocean|sea|waves|waterfall|pond|creek|stream|reservoir");add("coast","coast|coastal|shore|shoreline|seascape|beach|cliffs");add("city","city|urban|downtown|skyline|metropolis|street");add("industrial","industrial|factory|concrete|warehouse|brutalist|steelworks");
        add("architecture","architecture|architectural|building|buildings|bridge|bridges");add("garden","garden|gardens|flowers|flower|botanical|blossom");add("night","night|nocturnal|midnight|moonlight|starlight");add("rain","rain|rainy|rainfall|storm|storms|thunderstorm");
        add("mist","mist|misty|fog|foggy|haze|hazy");add("sunrise","sunrise|dawn|daybreak");add("sunset","sunset|dusk|twilight|sundown");add("cold","snow|snowy|winter|ice|glacier|icy|frost");add("warm","warm|golden|sunlit|sunlight|summer|amber|ochre");
        add("blue","blue|cyan|indigo|azure");add("green","green|emerald|verdant");add("magenta","magenta|purple|violet|pink");add("earth","earthy|ochre|brown|sandstone|terracotta");add("dark","dark|black|shadow|shadows|nocturnal");
        add("bright","bright|sunlit|luminous|radiant");add("soft","soft|diffuse|diffused|hazy|muted");add("neon","neon|fluorescent|glowing");add("contrast","contrast|silhouette|stark");add("minimal","minimal|minimalist|sparse|negative space");add("geometric","geometric|geometry|angular|symmetry|symmetric|rings");
        add("abstract","abstract|abstraction|nonfigurative");add("painting","painting|oil on canvas|watercolour|watercolor|woodblock|lithograph");add("impressionism","impressionism|impressionist");add("romanticism","romanticism|romantic landscape");
        add("space","spacecraft|nebula|galaxy|saturn|lunar|astronomy|cosmic|starfield");add("stars","stars|starfield|milky way|galaxy");add("nebula","nebula|nebulae|interstellar");add("planet","planet|saturn|jupiter|mars|lunar");
        add("calm","calm|tranquil|peaceful|still water|still lake|quiet water|gentle waves|serene");add("dreamy","dreamlike|dreamy|ethereal|hazy");add("lonely","solitary|solitude|lonely|lonesome");add("dramatic","dramatic|stormy|volcanic|erupting");add("weathered","weathered|worn|rusted|patina|aged");
        add("performance","concert|performing|performance|live music|on stage|festival");add("photography","photograph|photography|photo");
    }
    private static void add(String name,String terms){RULES.put(name,Pattern.compile("(?<![\\p{L}\\p{N}])(?:"+terms+")(?![\\p{L}\\p{N}])"));}
    public static JSONArray extract(String caption){
        String t=caption.replaceAll("(?<=[\\p{Ll}0-9])(?=\\p{Lu})"," ").toLowerCase(Locale.ROOT).replace('-',' ').replace('_',' ');JSONArray tags=new JSONArray();
        for(Map.Entry<String,Pattern> r:RULES.entrySet())if(r.getValue().matcher(t).find())tags.put(r.getKey());
        return tags;
    }
    public static JSONArray merge(JSONArray prior,String text){
        LinkedHashSet<String> values=new LinkedHashSet<>();if(prior!=null)for(int i=0;i<prior.length();i++)values.add(prior.optString(i));
        JSONArray added=extract(text);for(int i=0;i<added.length();i++)values.add(added.optString(i));return new JSONArray(values);
    }
    public static boolean namedArtist(String text,String name){
        String normalized=words(text),artist=words(name);if(normalized.matches(".*\\b(tribute|impersonator|lookalike|statue|sculpture|mural|memorial|gravestone|museum|waxwork|monument|cover band|autograph|signature)\\b.*"))return false;
        return artist.length()>=3&&(" "+normalized+" ").contains(" "+artist+" ");
    }
    public static boolean artistSubject(String title,String description,String name,String type){
        if(!namedArtist(title+" "+description,name))return false;
        String full=words(title+" "+description),who=words(name);
        if(full.contains("owned by "+who)||full.contains("belonging to "+who)||full.contains("birthplace of "+who)||full.contains("home of "+who)||full.contains("house of "+who))return false;
        if(full.matches(".*"+Pattern.quote(who)+" s (cat|dog|house|home|car|pet|grave)\\b.*"))return false;
        if(!type.equalsIgnoreCase("Group"))return true;
        String text=words(title+" "+description),artist=words(name);
        if(text.matches(".*\\b(tribute|solo project|side project|former member)\\b.*"))return false;
        return namedArtist(title,name)||text.contains(artist+" performing")||text.contains(artist+" live")||text.contains(artist+" at ")||text.contains(artist+" concert");
    }
    private static String words(String text){return java.text.Normalizer.normalize(text.toLowerCase(Locale.ROOT),java.text.Normalizer.Form.NFD).replaceAll("\\p{M}","").replaceAll("[^\\p{L}\\p{N}]+"," ").trim();}
}
