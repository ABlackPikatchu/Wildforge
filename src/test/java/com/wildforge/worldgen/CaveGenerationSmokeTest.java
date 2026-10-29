package com.wildforge.worldgen;

import com.wildforge.world.BlockType;
import com.wildforge.world.TerrainMesh;
import java.util.Arrays;

/** Headless determinism, roof safety and block preservation for Phase 2 caves. */
public final class CaveGenerationSmokeTest {
    private CaveGenerationSmokeTest() {}

    public static void main(String[] args) throws Exception {
        WorldGenRegistry registry = WorldGenRegistry.loadBuiltIn();
        int tested = 0;
        long carvedCells = 0;
        for (long seed : new long[]{20261029L, 8675309L, -781234567L}) {
            WorldGenerator generator = registry.generator(seed);
            CaveCarvers carvers = new CaveCarvers(seed);
            for (int[] chunk : new int[][]{{0,0},{-2,3},{4,-5}}) {
                byte[] first = blank(), second = blank();
                carvers.carve(first,chunk[0],chunk[1],generator);
                carvers.carve(second,chunk[0],chunk[1],generator);
                if (!Arrays.equals(first,second))
                    throw new AssertionError("Chunk-order dependence: seed " + seed);
                for (int lx=0;lx<16;lx++)for (int lz=0;lz<16;lz++) {
                    int base=(lx*16+lz)*TerrainMesh.WORLD_HEIGHT;
                    int wx=chunk[0]*16+lx,wz=chunk[1]*16+lz;
                    int top=generator.surface(wx,wz).surfaceY();
                    for(int y=0;y<TerrainMesh.WORLD_HEIGHT;y++) {
                        if (y==0 && first[base+y] != BlockType.BEDROCK.getId())
                            throw new AssertionError("Bedrock removed");
                        if (y>top+1 && first[base+y]==BlockType.AIR.getId())
                            throw new AssertionError("Carved above surface at " + wx+","+y+","+wz);
                        if (y>=5 && first[base+y]==BlockType.AIR.getId()) carvedCells++;
                    }
                    // Noise caves alone must never puncture the surface roof.
                    for(int y=Math.max(1,top-13);y<=top;y++) {
                        if(generator.isCave(wx,y,wz,top))
                            throw new AssertionError("Noise cave broke roof at " + wx+","+y+","+wz);
                    }
                }
                tested++;
            }
        }
        if(carvedCells==0)throw new AssertionError("Carvers produced no underground passages");
        System.out.println("Cave generation passed: " + tested + " deterministic 512-high chunks, "
                + carvedCells + " carved cells; roofs and bedrock preserved");
    }

    private static byte[] blank() {
        byte[] blocks=new byte[16*16*TerrainMesh.WORLD_HEIGHT];
        Arrays.fill(blocks,BlockType.STONE.getId());
        for(int i=0;i<16*16;i++)blocks[i*TerrainMesh.WORLD_HEIGHT]=BlockType.BEDROCK.getId();
        return blocks;
    }
}
