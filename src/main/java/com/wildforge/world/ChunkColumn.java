package com.wildforge.world;

/**
 * One resident 16x16 block column. Owns palette-backed block storage and the
 * CPU mesh revisions for its vertical sections; terrain streaming owns lifetimes.
 * The save file still uses the historical column-major block indices.
 */
final class ChunkColumn implements com.wildforge.world.chunk.ChunkAccess {
    final int cx, cz;
    byte[] blocks = new byte[TerrainMesh.CHUNK_SIZE * TerrainMesh.CHUNK_SIZE * TerrainMesh.WORLD_HEIGHT];
    PaletteColumn compact;
    /** Only extended (non-byte) runtime states need extra render snapshot storage. */
    private final java.util.HashMap<Integer,Integer> extendedStates = new java.util.HashMap<>();
    final com.wildforge.world.mesh.ChunkRenderData render =
            new com.wildforge.world.mesh.ChunkRenderData(TerrainMesh.SECTION_COUNT);

    ChunkColumn(int cx, int cz) {
        this.cx = cx;
        this.cz = cz;
    }

    @Override public int chunkX() { return cx; }
    @Override public int chunkZ() { return cz; }
    @Override public int stateId(int localX, int worldY, int localZ) {
        if (localX < 0 || localX >= 16 || localZ < 0 || localZ >= 16 ||
            worldY < 0 || worldY >= TerrainMesh.WORLD_HEIGHT)
            throw new IndexOutOfBoundsException("Invalid chunk-local coordinates");
        return getStateId(ChunkCoordinates.localIndex(localX,worldY,localZ));
    }
    byte get(int index) { return compact == null ? blocks[index] : compact.get(index); }
    void set(int index, byte block) {
        if (compact == null) blocks[index] = block;
        else compact.set(index, block);
        extendedStates.remove(index);
    }
    int getStateId(int index) {
        return compact == null ? Byte.toUnsignedInt(blocks[index]) : compact.getStateId(index);
    }
    void setStateId(int index, int stateId) {
        if (compact == null) compact();
        compact.setStateId(index, stateId);
        if(stateId>255)extendedStates.put(index,stateId);
        else extendedStates.remove(index);
    }
    java.util.Map<Integer,Integer> extendedSnapshot(){return java.util.Map.copyOf(extendedStates);}
    byte[] copyBlocks() { return compact == null ? blocks.clone() : compact.copyRaw(); }
    int[] copyStateIds() {
        if (compact != null) return compact.copyStateIds();
        int[] ids = new int[blocks.length];
        for (int i = 0; i < ids.length; i++) ids[i] = Byte.toUnsignedInt(blocks[i]);
        return ids;
    }
    void compact() {
        if (compact != null) return;
        compact = new PaletteColumn(blocks);
        blocks = null;
    }
}
