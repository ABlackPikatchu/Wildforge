package com.wildforge.worldgen;

import com.wildforge.world.BlockType;

/** Post-carving cave-wall and floor palette. Does not create water or new block IDs.
 * Samples biome identity at exposed solid positions only, not in all underground voxels.
 */
public final class CaveBiomePainter {
    private static final int SIDE=16;
    private CaveBiomePainter() {}

    public static void paint(byte[] blocks,int chunkX,int chunkZ,WorldGenerator generator,int height) {
        byte air=BlockType.AIR.getId(),bedrock=BlockType.BEDROCK.getId();
        for(int x=0;x<SIDE;x++)for(int z=0;z<SIDE;z++) {
            int wx=chunkX*SIDE+x,wz=chunkZ*SIDE+z;
            WorldGenerator.Surface land=generator.surface(wx,wz);
            int max=Math.min(height-3,land.surfaceY()-5);
            int base=(x*SIDE+z)*height;
            for(int y=5;y<=max;y++) {
                int at=base+y;
                byte old=blocks[at];
                if(old==air||old==bedrock)continue;
                boolean floor=blocks[at+1]==air, ceiling=blocks[at-1]==air;
                boolean wall=(x>0 && blocks[at-SIDE*height]==air)
                        ||(x<SIDE-1 && blocks[at+SIDE*height]==air)
                        ||(z>0 && blocks[at-height]==air)
                        ||(z<SIDE-1 && blocks[at+height]==air);
                if(!floor&&!ceiling&&!wall)continue;
                // Avoid repaints of explicit ore veins, and retain original host rock extensively.
                if((old&255)>=BlockType.COAL_ORE.getId() && (old&255)<=BlockType.DIAMOND_ORE.getId())continue;
                UndergroundBiomeSampler.Context ctx=generator.caveSampler().contextAt(wx,y,wz,land);
                UndergroundBiome biome=generator.caveSampler().choose(wx,y,wz,ctx);
                if(biome==null)continue;
                long h=mix(((long)wx*0x9E3779B97F4A7C15L)^((long)wz*0xC2B2AE3D27D4EB4FL)
                        ^((long)y*0x165667B19E3779F9L));
                // Small stable patches, not complete recoloring or per-pixel random speckles.
                int chance=(int)((h>>>59)&31);
                if(chance>4)continue; // Fewer isolated accent blocks; host stone should dominate.
                BlockType replacement=material(biome,floor,ctx,choice(h));
                if(replacement!=null)blocks[at]=replacement.getId();
            }
        }
    }
    private static int choice(long h) {return (int)((h>>>48)&3);}
    private static BlockType material(UndergroundBiome b,boolean floor,UndergroundBiomeSampler.Context ctx,int n) {
        return switch(b) {
            case ROOTLACE_HOLLOWS -> floor? (n==0?BlockType.MOSS_BLOCK:BlockType.RICH_HUMUS)
                    : BlockType.EXPOSED_ROOT_STONE;
            case SEEPSTONE_GROTTOS -> floor? (n==0?BlockType.MOSS_BLOCK:BlockType.WET_ROCK):BlockType.WET_ROCK;
            case UNDERFLOW_CHANNELS -> floor? (n==0?BlockType.SILT:BlockType.RIVERBED_SEDIMENT)
                    :BlockType.WATERWORN_STONE;
            case HOLLOWSTONE_VAULTS -> floor?BlockType.LIMESTONE_RUBBLE
                    :(n==0?BlockType.CALCITE:BlockType.FLOWSTONE);
            case COALSHADE_GALLERIES -> floor?BlockType.COMPACTED_SEDIMENT:BlockType.FOSSIL_SHALE;
            case SALTVEIL_CHAMBERS -> floor?BlockType.SALT_CRUST:BlockType.GYPSUM;
            case FROSTVAULT_CREVASSES -> floor?BlockType.PACKED_SNOW:BlockType.GLACIAL_ICE;
            case IRONFAULT_CHASMS -> floor?BlockType.FAULT_BRECCIA:BlockType.FRACTURED_SLATE;
            case CRYSTAL_SEAM_CHAMBERS -> BlockType.MINERAL_MATRIX;
            case SPORELIGHT_HOLLOWS -> floor?BlockType.MYCELIAL_MAT:(n==0?BlockType.MOSSY_ROCK:BlockType.WET_ROCK);
            case CINDERVEIN_TUBES -> floor?BlockType.ROPE_LAVA_STONE:BlockType.CRACKED_BASALT;
            case STILLWATER_ABYSS -> floor?BlockType.SUBMERGED_SCREE:BlockType.WET_SLATE;
        };
    }
    private static long mix(long v) {
        v=(v^(v>>>30))*0xbf58476d1ce4e5b9L;
        v=(v^(v>>>27))*0x94d049bb133111ebL;
        return v^(v>>>31);
    }
}
