package com.wildforge.world;

import java.util.BitSet;

/** Headless regression for per-section invalidation at world and section boundaries. */
public final class SectionMeshingSmokeTest {
    private SectionMeshingSmokeTest() {}
    private static void expect(int y,int... expected) {
        BitSet actual=TerrainMesh.affectedSections(y);
        BitSet wanted=new BitSet(TerrainMesh.SECTION_COUNT);
        for(int section:expected)wanted.set(section);
        if(!actual.equals(wanted))
            throw new AssertionError("Y="+y+" expected "+wanted+", found "+actual);
    }
    public static void main(String[] args) {
        expect(0,0);
        expect(1,0);
        expect(14,0);
        expect(15,0,1);
        expect(16,0,1);
        expect(17,1);
        expect(30,1);
        expect(31,1,2);
        expect(32,1,2);
        expect(33,2);
        expect(255,15,16);
        expect(256,15,16);
        expect(510,31);
        expect(511,31);
        boolean rejected=false;
        try {TerrainMesh.affectedSections(-1);} catch(IllegalArgumentException expected) {rejected=true;}
        if(!rejected)throw new AssertionError("Negative Y was accepted");
        rejected=false;
        try {TerrainMesh.affectedSections(TerrainMesh.WORLD_HEIGHT);} catch(IllegalArgumentException expected) {rejected=true;}
        if(!rejected)throw new AssertionError("Out-of-range Y was accepted");
        System.out.println("SectionMeshingSmokeTest PASS: 14 edge/interior cases plus invalid Y");
    }
}
