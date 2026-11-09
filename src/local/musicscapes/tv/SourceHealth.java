package local.musicscapes.tv;
import org.json.JSONObject;

/** Safe service diagnostics: no response body, request URL or exception message. */
final class SourceHealth {
    static JSONObject failure(String provider,int code,String kind,long retry,long started){JSONObject value=new JSONObject();try{value.put("provider",provider).put("httpStatus",code).put("errorKind",kind).put("retryAfterMs",Math.max(0,retry)).put("latencyMs",Math.max(0,System.currentTimeMillis()-started));}catch(Exception ignored){}return value;}
}
