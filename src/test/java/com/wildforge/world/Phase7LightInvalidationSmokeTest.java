package com.wildforge.world;
import java.util.BitSet;
/** Vertical rebuild envelope and 16-block section seam regression. */
public final class Phase7LightInvalidationSmokeTest {
    public static void main(String[] args){
        BitSet emission=TerrainMesh.affectedLightSections(32,false);
        if(!emission.get(1)||!emission.get(2)||!emission.isEmpty()&&emission.cardinality()!=2)
            throw new AssertionError("Emission updates must touch 15 blocks on both sides");
        BitSet roof=TerrainMesh.affectedLightSections(250,true);
        if(!roof.get(0)||!roof.get(15)||roof.get(18))
            throw new AssertionError("Skylight update should reach lower sections, not far above roof");
        BitSet bottom=TerrainMesh.affectedLightSections(0,false);
        BitSet top=TerrainMesh.affectedLightSections(511,false);
        if(bottom.get(TerrainMesh.SECTION_COUNT)||top.cardinality()!=1||!top.get(31))
            throw new AssertionError("World border invalidation overflowed");
        System.out.println("Phase 7 lighting section invalidation passed");
    }
}
