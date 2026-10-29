package com.wildforge.world.light;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Bounded light-volume cache owned by one world session. Mesh workers receive
 * immutable byte snapshots; no worker ever reads live mutable chunk storage.
 * Unchanged block/seam snapshots share the same baked light instead of paying
 * for a full-height flood fill on every remesh. Single emission-only edits can
 * use a bounded local relight; opacity changes conservatively full-rebake.
 */
public final class ColumnLightCache {
    private record Entry(byte[] blocks, byte[] west, byte[] east, byte[] north, byte[] south,
                         LightEngine light) { }
    public record Stats(int residents, long hits, long fullBakes, long localRelights) { }
    private final int capacity;
    private final LinkedHashMap<Long,Entry> entries = new LinkedHashMap<>(16,.75f,true);
    private long hits, fullBakes, localRelights;
    private int generation;

    public ColumnLightCache(int capacity) {
        if(capacity<1)throw new IllegalArgumentException("Light cache capacity must be positive");
        this.capacity=capacity;
    }
    private static long key(int cx,int cz){return ((long)cx<<32)|(cz&0xffff_ffffL);}
    private static boolean equal(byte[] a,byte[] b){return Arrays.equals(a,b);}
    public synchronized int generation(){return generation;}
    public synchronized LightEngine get(int cx,int cz,byte[] blocks,byte[] west,byte[] east,
                                        byte[] north,byte[] south){
        return get(cx,cz,blocks,west,east,north,south,generation);
    }
    /** An obsolete worker may finish after reset, but cannot populate a new world. */
    public synchronized LightEngine get(int cx,int cz,byte[] blocks,byte[] west,byte[] east,
                                        byte[] north,byte[] south,int requestedGeneration){
        if(requestedGeneration!=generation)
            return LightEngine.bake(blocks,west,east,north,south);
        long key=key(cx,cz);
        Entry old=entries.get(key);
        if(old!=null && equal(old.west,west) && equal(old.east,east) &&
                equal(old.north,north) && equal(old.south,south)){
            if(equal(old.blocks,blocks)){
                hits++;
                return old.light;
            }
            int difference=-1;
            for(int i=0;i<blocks.length;i++)if(blocks[i]!=old.blocks[i]){
                if(difference>=0){difference=-2;break;}
                difference=i;
            }
            // An emission change near an external seam needs a full bake;
            // no stale neighbor-edge light values are imported by local relighting.
            if(difference>=0 && west==null && east==null && north==null && south==null){
                LightEngine local=old.light.withEmissionEdit(blocks,difference);
                if(local!=null){
                    entries.put(key,new Entry(blocks,west,east,north,south,local));
                    localRelights++;
                    return local;
                }
            }
        }
        LightEngine fresh=LightEngine.bake(blocks,west,east,north,south);
        entries.put(key,new Entry(blocks,west,east,north,south,fresh));
        fullBakes++;
        if(entries.size()>capacity){
            var it=entries.entrySet().iterator();it.next();it.remove();
        }
        return fresh;
    }
    public synchronized void evict(int cx,int cz){entries.remove(key(cx,cz));}
    public synchronized void reset(){generation++;entries.clear();hits=fullBakes=localRelights=0;}
    public synchronized Stats stats(){return new Stats(entries.size(),hits,fullBakes,localRelights);}
}
