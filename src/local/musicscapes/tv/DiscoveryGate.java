package local.musicscapes.tv;

import java.util.HashMap;
import java.util.Map;

/** Cancels obsolete work immediately; cooldown starts only after a completed batch. */
public final class DiscoveryGate {
    private final Map<String,Long> nextAllowed=new HashMap<>();
    private String current="";
    private int revision,inFlight;
    public synchronized int request(String key,long now,long backoff){
        if(!key.equals(current)){current=key;revision++;inFlight=0;}
        if(inFlight!=0||now<backoff||now<nextAllowed.getOrDefault(key,0L))return 0;
        inFlight=++revision;return inFlight;
    }
    public synchronized boolean current(int ticket){return ticket!=0&&ticket==inFlight&&ticket==revision;}
    public synchronized boolean working(){return inFlight!=0;}
    public synchronized void finish(int ticket,long now,boolean success){finish(ticket,now,success,180000);}
    public synchronized void finish(int ticket,long now,boolean success,long delay){if(current(ticket)){nextAllowed.put(current,now+(success?delay:45000));inFlight=0;}}
    public synchronized void cancel(){revision++;inFlight=0;current="";}
}
