package local.musicscapes.tv;

import org.json.JSONObject;
import java.net.URL;
import java.util.Locale;

/** Pure rules shared by online discovery and its fixture checks. */
public final class DiscoveryRules {
    public static String artistSearch(JSONObject query){
        String category=query.optString("category"),qid=query.optString("artistQid");
        if(!query.optString("artistId").matches("[a-f0-9-]{36}")||!qid.matches("Q[1-9][0-9]*"))return "";
        if(query.optString("artistSearch").equals("depicts"))return "haswbstatement:P180="+qid;
        if(category.isEmpty()||category.length()>160||category.matches(".*[\\r\\n\\\"\\\\].*"))return "";
        return "deepcat:\""+category+"\"";
    }
    public static boolean qualitySearch(String kind,int photoIndex,int page){return kind.equals("photo")&&photoIndex==0&&page%3==0;}
    public static boolean wide(int w,int h){double ratio=h>0?w/(double)h:0;return ratio>=1.5&&ratio<=2.15;}
    public static boolean full4k(int w,int h){return wide(w,h)&&w>=3840&&h>=2160;}
    public static boolean artistHd(int w,int h){return wide(w,h)&&w>=1920&&h>=1080;}
    public static boolean librarySize(String kind,int w,int h){return kind.equals("artist")?artistHd(w,h):full4k(w,h);}
    public static String artistFile(JSONObject query){
        if(!query.optString("artistId").matches("[a-f0-9-]{36}")||!query.optString("artistQid").matches("Q[1-9][0-9]*")||!query.optString("artistSearch").equals("primary"))return "";
        String file=query.optString("file");return file.length()>0&&file.length()<=240&&!file.matches(".*[\\r\\n|#].*")&&file.toLowerCase(Locale.ROOT).matches(".*\\.(jpg|jpeg|png)")?"File:"+file:"";
    }
    public static boolean allowedImage(String value){try{URL u=new URL(value);return u.getProtocol().equals("https")&&(u.getHost().equals("upload.wikimedia.org")||u.getHost().equals("thumb.wikimedia.org"))&&u.getPort()==-1&&u.getUserInfo()==null;}catch(Exception e){return false;}}
    public static String downloadUrl(JSONObject info){
        return downloadUrl(info,false);
    }
    public static String downloadUrl(JSONObject info,boolean artist){
        String original=info.optString("url").split("\\?",2)[0];if(!allowedImage(original))return "";
        String thumb=info.optString("thumburl");
        if(allowedImage(thumb)&&(full4k(info.optInt("thumbwidth"),info.optInt("thumbheight"))||artist&&!full4k(info.optInt("width"),info.optInt("height"))&&artistHd(info.optInt("thumbwidth"),info.optInt("thumbheight"))))return thumb;
        return info.optLong("size")<=24000000&&info.optString("mime").matches("image/(jpeg|png)")?original:"";
    }
    public static String license(JSONObject meta){
        String value=plain(meta.optJSONObject("LicenseShortName")),url=plain(meta.optJSONObject("LicenseUrl"));
        String name=value.toLowerCase(Locale.ROOT).trim();
        if(name.equals("public domain"))return value;
        if(name.equals("cc0")&&url.matches("https?://creativecommons\\.org/publicdomain/zero/1\\.0(/.*)?"))return value;
        if(name.matches("cc by(\\-sa)? [234]\\.0")&&url.matches("https?://creativecommons\\.org/licenses/by(\\-sa)?/[234]\\.0(/.*)?"))return value;
        return "";
    }
    public static String plain(JSONObject field){return field==null?"":clean(field.optString("value"));}
    public static String clean(String text){return text.replaceAll("(?is)<(script|style)[^>]*>.*?</\\1>"," ").replaceAll("<[^>]+>"," ").replace("&amp;","&").replace("&quot;","\"").replace("&#39;","'").replace("&nbsp;"," ").replaceAll("\\s+"," ").trim();}
    public static boolean unwanted(String title){return title.toLowerCase(Locale.ROOT).matches(".*(screenshot|logo|poster|album cover|collage|diagram|map of|coat of arms|flag of|contact sheet|restoration comparison).* ".trim());}
    public static String kind(String title,String description){
        String t=(title+" "+description).toLowerCase(Locale.ROOT);
        if(!ImageDiversity.artworkSubject(title,title+" "+description))return "documentary";
        if(t.matches(".*(painting|oil on canvas|woodblock|watercolour|watercolor|lithograph|impressionis[m|t]).*"))return "art";
        if(t.matches(".*(nebula|galaxy|saturn|jupiter|hubble|nasa|spacecraft|lunar|cosmic|andromeda|webb telescope).*"))return "space";
        return "photo";
    }
    public static double cosine(org.json.JSONArray a,org.json.JSONArray b){if(a.length()!=384||b.length()!=384)return -1;double v=0;for(int i=0;i<384;i++)v+=a.optDouble(i)*b.optDouble(i);return v;}
}
