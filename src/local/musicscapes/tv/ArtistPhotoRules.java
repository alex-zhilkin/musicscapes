package local.musicscapes.tv;

import org.json.*;
import java.net.URL;
import java.util.*;
import java.security.MessageDigest;

/** Pure provider admission rules. Artist identity is separate from aesthetic grading. */
public final class ArtistPhotoRules {
    public static boolean identity(JSONObject q){return q.optString("artistId").matches("[a-f0-9]{8}(-[a-f0-9]{4}){3}-[a-f0-9]{12}")&&q.optString("text").length()>=3;}
    public static boolean flickrName(JSONObject q){return identity(q)&&q.optString("text").replaceAll("[^\\p{L}\\p{N}]+"," ").trim().split(" +").length>=2;}
    public static boolean imageUrl(String value,String provider){
        if(provider.equals("commons"))return DiscoveryRules.allowedImage(value);
        try{URL u=new URL(value);if(!u.getProtocol().equals("https")||u.getPort()!=-1||u.getUserInfo()!=null||u.getQuery()!=null||u.getRef()!=null)return false;
            if(provider.equals("flickr"))return (u.getHost().equals("live.staticflickr.com")||u.getHost().matches("farm[0-9]+\\.staticflickr\\.com"))&&u.getPath().matches("/[0-9]+/[a-zA-Z0-9_-]+\\.(jpg|jpeg|png)");
            if(provider.equals("fanart"))return u.getHost().equals("assets.fanart.tv")&&u.getPath().matches("/fanart/music/[a-f0-9-]{36}/(artistbackground|4kartistbackground)/[a-zA-Z0-9_.-]+\\.(jpg|jpeg|png)");
        }catch(Exception ignored){}return false;
    }
    public static String licence(String url){
        if(url.matches("https?://creativecommons\\.org/licenses/by/[234]\\.0/?"))return "CC BY "+url.replaceAll(".*/by/([234]\\.0)/?","$1");
        if(url.matches("https?://creativecommons\\.org/licenses/by-sa/[234]\\.0/?"))return "CC BY-SA "+url.replaceAll(".*/by-sa/([234]\\.0)/?","$1");
        if(url.matches("https?://creativecommons\\.org/publicdomain/zero/1\\.0/?"))return "CC0";
        if(url.matches("https?://creativecommons\\.org/publicdomain/mark/1\\.0/?"))return "Public domain";
        return "";
    }
    public static Map<String,String> flickrLicences(JSONObject response)throws Exception{
        Map<String,String> result=new LinkedHashMap<>();JSONArray list=response.getJSONObject("licenses").getJSONArray("license");
        for(int i=0;i<list.length();i++){JSONObject r=list.getJSONObject(i);if(r.optString("id").matches("[0-9]{1,3}")&&!licence(r.optString("url")).isEmpty())result.put(r.getString("id"),r.getString("url"));}return result;
    }
    public static JSONObject flickrSize(JSONObject response)throws Exception{
        JSONObject sizes=response.getJSONObject("sizes");if(!sizes.optBoolean("candownload",false)&&sizes.optInt("candownload",0)!=1)return null;
        JSONArray list=sizes.getJSONArray("size");JSONObject best=null;long bestPixels=Long.MAX_VALUE;boolean found4k=false;
        for(int i=0;i<list.length();i++){JSONObject r=list.getJSONObject(i);int w=r.optInt("width"),h=r.optInt("height");if(!DiscoveryRules.artistHd(w,h)||!imageUrl(r.optString("source"),"flickr"))continue;
            boolean four=DiscoveryRules.full4k(w,h);long pixels=(long)w*h;if(best==null||four&&!found4k||four==found4k&&pixels<bestPixels){best=r;bestPixels=pixels;found4k=four;}}
        return best;
    }
    public static JSONObject flickr(JSONObject photo,JSONObject sizes,JSONObject query,Map<String,String> licences)throws Exception{
        if(!flickrName(query)||!photo.optString("id").matches("[0-9]+")||!photo.optString("owner").matches("[0-9]+@N[0-9]+"))return null;
        if(photo.has("ispublic")&&photo.optInt("ispublic")!=1)return null;
        String url=licences.get(photo.optString("license"));if(url==null)return null;
        String title=DiscoveryRules.clean(photo.optString("title")),description=DiscoveryRules.clean(photo.optJSONObject("description")==null?"":photo.getJSONObject("description").optString("_content"));
        if(DiscoveryRules.unwanted(title+" "+description)||!VisualTags.artistSubject(title,description,query.optString("text"),query.optString("artistType")))return null;
        JSONObject size=flickrSize(sizes);if(size==null)return null;
        String creator=DiscoveryRules.clean(photo.optString("ownername"));if(creator.isEmpty())creator=photo.getString("owner");
        JSONObject r=record(query,title,description,size.getString("source"),size.getInt("width"),size.getInt("height"));
        r.put("source","Flickr").put("imageProvider","flickr").put("sourcePhotoId",photo.getString("id")).put("sourceOwnerId",photo.getString("owner"));
        r.put("sourceUrl","https://www.flickr.com/photos/"+photo.getString("owner")+"/"+photo.getString("id")).put("artist",creator).put("credit",creator+" · "+licence(url)).put("license",licence(url)).put("licenseUrl",url.replace("http://","https://"));
        r.put("identityEvidence","Verified music artist identity and named Flickr photograph; ambiguous single-name artists excluded").put("expiresAt",System.currentTimeMillis()+24L*60*60*1000);
        return r;
    }
    public static List<JSONObject> fanart(JSONObject response,JSONObject query,boolean store)throws Exception{
        List<JSONObject> out=new ArrayList<>();if(store||!identity(query)||!query.optString("artistId").equalsIgnoreCase(response.optString("mbid_id")))return out;
        for(String type:new String[]{"4kartistbackground","artistbackground"}){
            JSONArray images=response.optJSONArray(type);if(images==null)continue;
            for(int i=0;i<Math.min(50,images.length());i++){
                JSONObject p=images.getJSONObject(i);String holder=DiscoveryRules.clean(p.optString("copyright")),link=p.optString("copyright_link"),url=p.optString("url");
                if(holder.length()<3||holder.equalsIgnoreCase("unknown")||!link.startsWith("https://")||!imageUrl(url,"fanart")||!url.contains("/"+query.getString("artistId")+"/")||!DiscoveryRules.artistHd(p.optInt("width"),p.optInt("height")))continue;
                JSONObject r=record(query,query.getString("text")+" · artist photograph","Artist background supplied for the exact MusicBrainz artist ID",url,p.getInt("width"),p.getInt("height"));
                r.put("source","Fanart.tv").put("imageProvider","fanart").put("sourcePhotoId",p.optString("id")).put("sourceUrl","https://fanart.tv/artist/"+query.getString("artistId")+"/");
                r.put("artist",holder).put("credit","© "+holder+" · Fanart.tv").put("copyrightSource",link).put("license","Copyright retained · personal media display").put("licenseUrl","https://fanart.tv/terms-and-conditions/").put("distributionPolicy","personal_only");
                r.put("identityEvidence","Fanart.tv exact MusicBrainz artist ID; credited source background");out.add(r);
            }
        }return out;
    }
    private static JSONObject record(JSONObject q,String title,String description,String url,int w,int h)throws Exception{
        String caption=title+". "+description;JSONObject r=new JSONObject().put("key","web"+sha(url).substring(0,24)).put("kind","artist").put("remote",true);
        r.put("artistId",q.getString("artistId")).put("artistName",q.getString("text")).put("artistQid",q.optString("artistQid")).put("title",title).put("caption",caption.substring(0,Math.min(900,caption.length())));
        r.put("imageUrl",url).put("downloadUrl",url).put("originalWidth",w).put("originalHeight",h).put("width",DiscoveryRules.full4k(w,h)?3840:1920).put("height",DiscoveryRules.full4k(w,h)?2160:1080);
        r.put("tags",VisualTags.extract(caption)).put("families",new JSONObject()).put("tagSchema",3).put("tagEvidence","Artist provider title and description").put("sourceQuality",0).put("energy",.4).put("aestheticPolicy","excluded_artist");
        r.put("cluster","artist:"+q.getString("artistId")).put("seriesKey",ImageDiversity.series(title,q.getString("artistId"))).put("placeKeys",new JSONArray());return r;
    }
    private static String sha(String value)throws Exception{StringBuilder out=new StringBuilder();for(byte b:MessageDigest.getInstance("SHA-256").digest(value.getBytes("UTF-8")))out.append(String.format(Locale.ROOT,"%02x",b&255));return out.toString();}
}
