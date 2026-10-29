package com.wildforge.world;

/** Immutable world-space cell coordinate; floor division handles negatives. */
public record BlockPos(int x, int y, int z) {
    public BlockPos offset(int dx, int dy, int dz) {
        return new BlockPos(Math.addExact(x, dx), Math.addExact(y, dy), Math.addExact(z, dz));
    }
    public int chunkX() { return Math.floorDiv(x, TerrainMesh.CHUNK_SIZE); }
    public int chunkZ() { return Math.floorDiv(z, TerrainMesh.CHUNK_SIZE); }
    public int localX() { return Math.floorMod(x, TerrainMesh.CHUNK_SIZE); }
    public int localZ() { return Math.floorMod(z, TerrainMesh.CHUNK_SIZE); }
    public boolean withinWorldHeight() { return y >= 0 && y < TerrainMesh.WORLD_HEIGHT; }
}
