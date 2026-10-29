package com.wildforge.world;

/** Headless invariants for append-only snow height states and leaf collision. */
public final class WorldgenPatch4SmokeTest {
    public static void main(String[] args) {
        for(int i=1;i<=7;i++) {
            BlockType snow=BlockType.snowLayer(i);
            if(!snow.isSnowLayer() || snow.snowLayers()!=i || !snow.isSolid() ||
               Math.abs(snow.collisionHeight()-i/8f)>1e-6)
                throw new AssertionError("Invalid snow layer "+i);
        }
        if(BlockType.snowLayer(8)!=BlockType.SNOW)throw new AssertionError("Full snow mismatch");
        for(BlockType type:BlockType.values())if(type.name().endsWith("_LEAVES") &&
              (!type.isSolid() || type.collisionHeight()!=1f))
            throw new AssertionError("Leaf collision missing: "+type);
        if(!TerrainMesh.supportedWaterSource(BlockType.STONE,BlockType.WATER,
                BlockType.WATER,BlockType.AIR,BlockType.AIR))
            throw new AssertionError("2-source conversion must work on solid floors");
        if(TerrainMesh.supportedWaterSource(BlockType.AIR,BlockType.WATER,
                BlockType.WATER,BlockType.AIR,BlockType.AIR))
            throw new AssertionError("Never create floating source blocks");
        if(TerrainMesh.supportedWaterSource(BlockType.STONE,BlockType.WATER,
                BlockType.FLOWING_WATER,BlockType.AIR,BlockType.AIR))
            throw new AssertionError("A single source is insufficient");
        for(BlockType foliage:new BlockType[]{BlockType.CLOVER_PLANT,BlockType.TALL_GRASS_PLANT,
                BlockType.CATTAIL_PLANT,BlockType.CORNFLOWER,BlockType.ORCHID_FLOWER,
                BlockType.FROST_SHRUB,BlockType.DRY_SCRUB,BlockType.LICHEN_PATCH}) {
            if(foliage.isSolid() || foliage.getDropItem()==null)
                throw new AssertionError("Flora registration broken: "+foliage);
        }
        if(TerrainMesh.WORLDGEN_REVISION<=0)throw new AssertionError("Missing worldgen revision");
        System.out.println("Patch 4 snow / foliage / revision checks passed");
    }
}
