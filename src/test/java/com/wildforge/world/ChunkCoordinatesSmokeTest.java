package com.wildforge.world;

import com.wildforge.world.mesh.FloatArrayBuilder;

/** Ensure unpacking and local indexing remain save-compatible after extraction. */
public final class ChunkCoordinatesSmokeTest {
    private ChunkCoordinatesSmokeTest() { }
    public static void main(String[] args) {
        for (int x : new int[]{Integer.MIN_VALUE,-512,-1,0,1,512,Integer.MAX_VALUE})
            for (int z : new int[]{Integer.MIN_VALUE,-512,-1,0,1,512,Integer.MAX_VALUE}) {
                long packed=ChunkCoordinates.pack(x,z);
                if (ChunkCoordinates.chunkX(packed)!=x || ChunkCoordinates.chunkZ(packed)!=z)
                    throw new AssertionError("Chunk position round-trip failed");
            }
        int index=ChunkCoordinates.localIndex(15,511,15);
        if (index != TerrainMesh.CHUNK_SIZE*TerrainMesh.CHUNK_SIZE*TerrainMesh.WORLD_HEIGHT-1)
            throw new AssertionError("Legacy column-major indexing changed");
        FloatArrayBuilder vertices=new FloatArrayBuilder();
        for (int i=0;i<3000;i++)vertices.add(i);
        if(vertices.toArray().length!=3000 || vertices.toArray()[2999]!=2999)
            throw new AssertionError("Vertex builder failed to grow");
        System.out.println("Chunk coordinate and mesh buffer checks passed");
    }
}
