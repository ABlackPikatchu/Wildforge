package com.wildforge.world;

/** Geometry policy for adaptive, non-flattened distant water tiles. */
public final class DistantWaterLod {
    private DistantWaterLod() {}

    /**
     * A uniform ocean patch can use one 2x2 quad; river banks, mixed coastlines
     * and uneven or differently filled water require four 1x1 quads instead.
     */
    public static boolean needsFineTiles(boolean[] wet,int[] height,int[] level) {
        if(wet.length!=4 || height.length!=4 || level.length!=4)
            throw new IllegalArgumentException("Expected four cells in a 2x2 tile");
        boolean containsWater=false;
        for(boolean value:wet)containsWater|=value;
        if(!containsWater)return false; // Keep ordinary distant land inexpensive.
        for(int i=1;i<4;i++)
            if(wet[i]!=wet[0] || height[i]!=height[0] || (wet[i] && level[i]!=level[0]))return true;
        return false;
    }
}
