package com.wildforge.worldgen;

import com.wildforge.world.BlockType;

/** Post-carving cave-wall and floor palette. Does not create water or new block IDs.
 * Samples biome identity at exposed solid positions only, not in all underground voxels.
 */
public final class CaveBiomePainter {
    private static final int SIDE=16;
    private CaveBiomePainter() {}

    public static void paint(byte[] blocks,int chunkX,int chunkZ,WorldGenerator generator,int height,CaveWallCatalog palettes) {
        byte air=BlockType.AIR.getId(),bedrock=BlockType.BEDROCK.getId();
        SeededNoise patchNoise = new SeededNoise(generator.seed());
        for(int x=0;x<SIDE;x++)for(int z=0;z<SIDE;z++) {
            int wx=chunkX*SIDE+x,wz=chunkZ*SIDE+z;
            WorldGenerator.Surface land=generator.surface(wx,wz);
            int max=Math.min(height-3,land.surfaceY()-5);
            int base=(x*SIDE+z)*height;
            for(int y=5;y<=max;y++) {
                int at=base+y;
                byte old=blocks[at];
                if(old==air||old==bedrock || !hostRock(old))continue;
                boolean floor=blocks[at+1]==air, ceiling=blocks[at-1]==air;
                boolean wall=(x>0 && blocks[at-SIDE*height]==air)
                        ||(x<SIDE-1 && blocks[at+SIDE*height]==air)
                        ||(z>0 && blocks[at-height]==air)
                        ||(z<SIDE-1 && blocks[at+height]==air);
                if(!floor&&!ceiling&&!wall)continue;
                // A continuous world-space mask groups accent material into small
                // deposits instead of scattering individual checkerboard blocks.
                // Cheap rejection happens before the expensive biome lookup.
                double cluster = patchNoise.noise3(wx/7.5, y/6.0, wz/7.5, 0x4c4159455253L);
                if (cluster < .28) continue;
                UndergroundBiomeSampler.Context ctx=generator.caveSampler().contextAt(wx,y,wz,land);
                UndergroundBiome biome=generator.caveSampler().choose(wx,y,wz,ctx);
                if(biome==null)continue;
                long h=mix(((long)wx*0x9E3779B97F4A7C15L)^((long)wz*0xC2B2AE3D27D4EB4FL)
                        ^((long)y*0x165667B19E3779F9L));
                BlockType replacement=palettes.get(biome).choose(floor,choice(h));
                if(replacement!=null)blocks[at]=replacement.getId();
            }
        }
    }
    /** Only replace natural rock; ores and other functional blocks stay intact. */
    private static final boolean[] HOST_ROCK = new boolean[256];
    static {
        for (BlockType block : new BlockType[]{BlockType.STONE,BlockType.SLATE,
                BlockType.SHALE,BlockType.LIMESTONE,BlockType.GRANITE,BlockType.GABBRO,
                BlockType.BASALT,BlockType.ANDESITE,BlockType.DIORITE,
                BlockType.SANDSTONE,BlockType.SILTSTONE,BlockType.SCORIA}) {
            HOST_ROCK[block.getId() & 255] = true;
        }
    }
    private static boolean hostRock(byte id) {return HOST_ROCK[id & 255];}
    private static int choice(long h) {return (int)((h>>>48)&3);}
    private static long mix(long v) {
        v=(v^(v>>>30))*0xbf58476d1ce4e5b9L;
        v=(v^(v>>>27))*0x94d049bb133111ebL;
        return v^(v>>>31);
    }
}
