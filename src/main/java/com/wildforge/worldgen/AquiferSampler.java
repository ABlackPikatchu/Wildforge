package com.wildforge.worldgen;

import com.wildforge.world.BlockType;

/** Seeded localized cave water tables and rare deep lava pockets.
 * Unlike full-column regional pools, this samples XYZ so dry and flooded caves
 * coexist beneath the same surface biome and agree across chunk boundaries.
 */
public final class AquiferSampler {
    private final SeededNoise noise;
    private final WorldGenSettings settings;
    public AquiferSampler(long seed,WorldGenSettings settings){this.noise=new SeededNoise(seed);this.settings=settings;}
    public BlockType fluidAt(int x,int y,int z,WorldGenerator.Surface surface){
        if(y<5||y>=surface.surfaceY()-16)return BlockType.AIR;
        double field=noise.fbm2(x/180.0,z/180.0,3,0xa911f01L);
        double localTable=settings.seaLevel()-27+field*26;
        // A true 3D basin mask prevents endless water columns in every wet region.
        double basin=noise.noise3(x/100.0,y/63.0,z/100.0,0xa91beefL);
        if(y<26 && noise.noise3(x/90.0,y/50.0,z/90.0,0x1a7a001L)>.33)
            return BlockType.LAVA;
        if(y<localTable && basin>.10 && (surface.rainfall()>.30||surface.waterBody()!=WorldGenerator.WaterBody.LAND))
            return BlockType.WATER;
        return BlockType.AIR;
    }
}
