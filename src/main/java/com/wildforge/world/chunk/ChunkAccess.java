package com.wildforge.world.chunk;

/** Read-only local palette cell access, independent of worldgen and rendering. */
public interface ChunkAccess {
    int chunkX();
    int chunkZ();
    /** Local X/Z 0..15; Y 0..511. Returns the session-local runtime state ID. */
    int stateId(int localX, int worldY, int localZ);
}
