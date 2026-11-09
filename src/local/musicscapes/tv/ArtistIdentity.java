package local.musicscapes.tv;

import org.json.*;
import java.io.*;
import java.net.*;
import java.util.Iterator;

/** Artist identity links come from MusicBrainz and must match Wikidata's reverse MBID. */
public final class ArtistIdentity {
    public static JSONObject find(String name){
        try{
            JSONObject result=read("https://www.wikidata.org/w/api.php?action=wbsearchentities&language=en&limit=8&format=json&search="+URLEncoder.encode(name,"UTF-8"));JSONArray search=result.optJSONArray("search");StringBuilder ids=new StringBuilder();
            if(search!=null)for(int i=0;i<search.length();i++){JSONObject item=search.getJSONObject(i);if(Matching.normalized(name).equals(Matching.normalized(item.optString("label")))&&item.optString("id").matches("Q[1-9][0-9]*")){if(ids.length()>0)ids.append('|');ids.append(item.getString("id"));}}
            if(ids.length()==0)return new JSONObject();
            JSONObject entities=read("https://www.wikidata.org/w/api.php?action=wbgetentities&ids="+URLEncoder.encode(ids.toString(),"UTF-8")+"&props=claims%7Clabels%7Csitelinks&languages=en%7Cmul&format=json").getJSONObject("entities");
            JSONObject selected=unique(entities,name);if(selected.length()==0)return selected;
            JSONObject entity=entities.getJSONObject(selected.getString("wikidata")),claims=entity.optJSONObject("claims");JSONArray genres=claims==null?null:claims.optJSONArray("P136");StringBuilder genreIds=new StringBuilder();
            if(genres!=null)for(int i=0;i<Math.min(8,genres.length());i++){JSONObject snak=genres.getJSONObject(i).optJSONObject("mainsnak"),v=snak==null?null:snak.optJSONObject("datavalue"),value=v==null?null:v.optJSONObject("value");if(value!=null&&value.optString("id").matches("Q[1-9][0-9]*")){if(genreIds.length()>0)genreIds.append('|');genreIds.append(value.optString("id"));}}
            if(genreIds.length()>0){JSONObject labels=read("https://www.wikidata.org/w/api.php?action=wbgetentities&ids="+URLEncoder.encode(genreIds.toString(),"UTF-8")+"&props=labels&languages=en%7Cmul&format=json").getJSONObject("entities");JSONArray tags=new JSONArray();Iterator<String> keys=labels.keys();while(keys.hasNext()){JSONObject item=labels.getJSONObject(keys.next()),l=item.optJSONObject("labels"),en=l==null?null:(l.optJSONObject("en")!=null?l.optJSONObject("en"):l.optJSONObject("mul"));if(en!=null)tags.put(new JSONObject().put("name",en.optString("value")).put("count",1));}selected.put("genres",tags);}
            return selected;
        }catch(Exception ignored){return new JSONObject();}
    }
    static JSONObject unique(JSONObject entities,String name)throws Exception{
        JSONObject chosen=null;String mbid="";int matches=0;Iterator<String> keys=entities.keys();
        while(keys.hasNext()){JSONObject item=entities.getJSONObject(keys.next()),labels=item.optJSONObject("labels"),label=labels==null?null:(labels.optJSONObject("en")!=null?labels.optJSONObject("en"):labels.optJSONObject("mul"));if(label==null||!Matching.normalized(name).equals(Matching.normalized(label.optString("value"))))continue;
            JSONObject claims=item.optJSONObject("claims");JSONArray ids=claims==null?null:claims.optJSONArray("P434");if(ids==null)continue;for(int i=0;i<ids.length();i++){String id=value(ids.getJSONObject(i));if(id.matches("[a-f0-9-]{36}")){chosen=item;mbid=id;matches++;break;}}
        }
        if(matches!=1)return new JSONObject();boolean person=false;JSONArray types=chosen.getJSONObject("claims").optJSONArray("P31");if(types!=null)for(int i=0;i<types.length();i++){JSONObject snak=types.getJSONObject(i).optJSONObject("mainsnak"),v=snak==null?null:snak.optJSONObject("datavalue"),item=v==null?null:v.optJSONObject("value");if(item!=null&&item.optString("id").equals("Q5"))person=true;}
        JSONObject out=new JSONObject().put("mbid",mbid).put("name",name).put("wikidata",chosen.getString("id")).put("type",person?"Person":"Group");merge(out,chosen,mbid);out.put("photoIdentity","Unique Wikidata artist with MusicBrainz identifier");return out;
    }
    public static JSONObject lookup(String mbid,String name){
        JSONObject out=new JSONObject();
        try{
            if(!mbid.matches("[a-f0-9-]{36}"))return out;
            out.put("mbid",mbid);out.put("name",name);
            JSONObject artist=read("https://musicbrainz.org/ws/2/artist/"+mbid+"?fmt=json&inc=url-rels");
            if(!mbid.equals(artist.optString("id")))return out;
            out.put("mbid",mbid);out.put("name",artist.optString("name",name));out.put("country",artist.optString("country"));out.put("type",artist.optString("type"));
            JSONObject life=artist.optJSONObject("life-span");if(life!=null)out.put("begin",life.optString("begin"));
            JSONArray relations=artist.optJSONArray("relations");String qid="";
            if(relations!=null)for(int i=0;i<relations.length();i++){JSONObject r=relations.getJSONObject(i),url=r.optJSONObject("url");if(r.optString("type").equals("wikidata")&&url!=null){String target=url.optString("resource");if(target.matches("https?://www\\.wikidata\\.org/wiki/Q[1-9][0-9]*"))qid=target.substring(target.lastIndexOf('/')+1);}}
            if(qid.isEmpty())return recover(out,mbid,name);
            JSONObject entity=read("https://www.wikidata.org/w/api.php?action=wbgetentities&ids="+qid+"&props=claims%7Csitelinks&format=json").getJSONObject("entities").getJSONObject(qid);
            return merge(out,entity,mbid);
        }catch(Exception ignored){return recover(out,mbid,name);}
    }
    private static JSONObject recover(JSONObject out,String mbid,String name){
        if(!mbid.matches("[a-f0-9-]{36}"))return out;
        JSONObject found=find(name);if(!mbid.equalsIgnoreCase(found.optString("mbid")))return out;
        try{Iterator<String> keys=found.keys();while(keys.hasNext()){String key=keys.next();out.put(key,found.get(key));}}catch(Exception ignored){}return out;
    }
    static JSONObject merge(JSONObject out,JSONObject entity,String mbid)throws Exception{
        JSONObject claims=entity.optJSONObject("claims");if(claims==null)return out;
        JSONArray ids=claims.optJSONArray("P434");boolean matches=false;
        if(ids!=null)for(int i=0;i<ids.length();i++)if(mbid.equalsIgnoreCase(value(ids.getJSONObject(i))))matches=true;
        if(!matches)return out;
        String category="";JSONObject links=entity.optJSONObject("sitelinks"),commons=links==null?null:links.optJSONObject("commonswiki");
        if(commons!=null&&commons.optString("title").startsWith("Category:"))category=commons.getString("title").substring(9);
        JSONArray categories=claims.optJSONArray("P373");if(category.isEmpty()&&categories!=null&&categories.length()>0)category=value(categories.getJSONObject(0));
        String qid=entity.optString("id");if(!qid.matches("Q[1-9][0-9]*"))return out;
        out.put("wikidata",qid);out.put("photoIdentity","MusicBrainz identity confirmed by Wikidata");
        JSONArray images=claims.optJSONArray("P18");if(images!=null)for(int i=0;i<images.length();i++){JSONObject image=images.getJSONObject(i);if(image.optString("rank").equals("deprecated"))continue;String file=value(image);if(file.length()>0&&file.length()<=240&&!file.matches(".*[\\r\\n|#].*")){out.put("primaryImage",file);break;}}
        if(!category.isEmpty()&&category.length()<=160&&!category.matches(".*[\\r\\n\\\"\\\\].*"))out.put("commonsCategory",category);
        return out;
    }
    private static String value(JSONObject claim){JSONObject snak=claim.optJSONObject("mainsnak"),value=snak==null?null:snak.optJSONObject("datavalue");return value==null?"":value.optString("value");}
    private static JSONObject read(String address)throws Exception{
        URL url=new URL(address);String host=url.getHost();
        for(int redirect=0;redirect<3;redirect++){
            if(!url.getProtocol().equals("https")||!url.getHost().equals(host))throw new IOException("Untrusted redirect");
            HttpURLConnection c=(HttpURLConnection)url.openConnection();c.setInstanceFollowRedirects(false);c.setConnectTimeout(6000);c.setReadTimeout(7000);c.setRequestProperty("User-Agent","MusicScapes/4.0 (android-app://local.musicscapes.tv)");
            try{int code=c.getResponseCode();if(code>=300&&code<400){url=new URL(url,c.getHeaderField("Location"));continue;}if(code!=200)throw new IOException("Identity source unavailable");try(InputStream in=c.getInputStream();ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] b=new byte[16384];int n;while((n=in.read(b))!=-1){if(out.size()+n>2000000)throw new IOException("Large identity response");out.write(b,0,n);}return new JSONObject(out.toString("UTF-8"));}}
            finally{c.disconnect();}
        }throw new IOException("Too many redirects");
    }
}
