package com.wildforge.world;

/** Packs signed chunk coordinates and indexes the existing column-major save layout. */
public final class ChunkCoordinates {
    private ChunkCoordinates() { }

    public static long pack(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) | (chunkZ & 0xffffffffL);
    }
    public static int chunkX(long packed) { return (int) (packed >> 32); }
    public static int chunkZ(long packed) { return (int) packed; }
    public static int localIndex(int localX, int y, int localZ) {
        return (localX * TerrainMesh.CHUNK_SIZE + localZ) * TerrainMesh.WORLD_HEIGHT + y;
    }
}
