package com.wildforge.world;

import java.util.BitSet;
import java.util.Map;

/** Neighbor edge slabs must occlude the same faces as full neighboring chunks. */
public final class SectionMeshBuilderPhase4SmokeTest {
    public static void main(String[] args) {
        byte[] center=new byte[16*16*512];
        byte[] west=new byte[16*512];
        int y=20,z=4;
        center[ChunkCoordinates.localIndex(0,y,z)]=BlockType.STONE.id;
        west[z*512+y]=BlockType.STONE.id;
        var snap=new SectionMeshBuilder.Snapshot(0,0,center,west,null,null,null,
                Map.of(),Map.of(),Map.of(),Map.of(),Map.of(),null);
        BitSet section=new BitSet(32);section.set(y/16);
        float[][] occluded=SectionMeshBuilder.buildSelectedMeshes(snap,section);
        var missing=new SectionMeshBuilder.Snapshot(0,0,center,null,null,null,null,
                Map.of(),Map.of(),Map.of(),Map.of(),Map.of(),null);
        float[][] visible=SectionMeshBuilder.buildSelectedMeshes(missing,section);
        int a=occluded[y/16].length,b=visible[y/16].length;
        if(a!=5*6*7||b!=6*6*7)
            throw new AssertionError("Invalid 5-face/6-face geometry "+a+"/"+b);
        System.out.println("Phase 4 reduced boundary snapshot and section meshing passed");
    }
}
