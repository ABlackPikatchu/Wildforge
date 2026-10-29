package com.wildforge.phase7;

import com.wildforge.world.BlockType;
import com.wildforge.world.light.LightEngine;
import com.wildforge.world.light.ColumnLightCache;
import java.util.Arrays;

/** Headless cache and local-relight regression; no OpenGL or generated data. */
public final class Phase7LightingSmokeTest {
    private static void check(boolean value,String why){if(!value)throw new AssertionError(why);}
    private static int index(int x,int y,int z){return (x*16+z)*512+y;}
    private static void equivalent(LightEngine actual,LightEngine reference) {
        for(int x=0;x<16;x++)for(int z=0;z<16;z++)for(int y=0;y<512;y++) {
            if(actual.sky(x,y,z)!=reference.sky(x,y,z) || actual.block(x,y,z)!=reference.block(x,y,z))
                throw new AssertionError("Local relight differed from full bake at "+x+","+y+","+z);
        }
    }
    public static void main(String[] args){
        ColumnLightCache cache=new ColumnLightCache(2);
        byte[] original=new byte[LightEngine.LENGTH];
        // Solid roof keeps this section dark; lava and water have the same
        // transmission but only lava emits light.
        for(int x=0;x<16;x++)for(int z=0;z<16;z++)original[index(x,240,z)]=BlockType.STONE.getId();
        original[index(7,100,7)]=BlockType.WATER.getId();
        LightEngine first=cache.get(1,1,original,null,null,null,null);
        check(cache.get(1,1,original.clone(),null,null,null,null)==first,"Unchanged volume did not hit cache");
        check(cache.stats().hits()==1 && cache.stats().fullBakes()==1,"Unexpected initial cache counters");
        byte[] lava=original.clone();lava[index(7,100,7)]=BlockType.LAVA.getId();
        LightEngine lit=cache.get(1,1,lava,null,null,null,null);
        check(cache.stats().localRelights()==1,"Emission-only edit did not use local relight");
        check(lit.block(8,100,7)>0,"Lava failed to illuminate neighboring air");
        equivalent(lit,LightEngine.bake(lava));
        LightEngine dark=cache.get(1,1,original,null,null,null,null);
        check(cache.stats().localRelights()==2,"Emission removal was not incremental");
        check(dark.block(8,100,7)==0,"Removed light left a ghost source");
        equivalent(dark,first);
        // Two overlapping light sources: removing one must preserve the other
        // without a spurious persistent glow from the removed source.
        byte[] two=original.clone();
        two[index(6,100,6)]=BlockType.LAVA.getId();
        two[index(11,100,8)]=BlockType.LAVA.getId();
        cache.get(1,1,two,null,null,null,null); // multi-edit requires full bake
        byte[] one=two.clone();one[index(6,100,6)]=BlockType.WATER.getId();
        equivalent(cache.get(1,1,one,null,null,null,null),LightEngine.bake(one));
        // Changing solid-to-air opens a sky shaft, so a full rebuild is needed.
        byte[] roofOpening=original.clone();roofOpening[index(7,240,7)]=BlockType.AIR.getId();
        cache.get(1,1,roofOpening,null,null,null,null);
        check(cache.stats().fullBakes()==3,"Sky-transmission change was not fully rebaked");
        byte[] neighbor=new byte[16*512];
        cache.get(2,1,original,neighbor,null,null,null);
        cache.get(3,1,original,null,null,null,null);
        check(cache.stats().residents()==2,"Cache exceeded memory bound");
        cache.evict(2,1);
        check(cache.stats().residents()==1,"Chunk eviction did not release cached lighting");
        int generation=cache.generation();
        cache.reset();
        check(cache.stats().residents()==0 && cache.stats().fullBakes()==0,"World reset leaked cache");
        cache.get(7,7,original,null,null,null,null,generation);
        check(cache.stats().residents()==0,"Old background mesh poisoned a new world cache");
        System.out.println("Phase 7 light cache passed: reuse, emission placement/removal, skylight, LRU, eviction, reset");
    }
}
