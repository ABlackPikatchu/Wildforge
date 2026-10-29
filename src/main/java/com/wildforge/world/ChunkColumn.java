package com.wildforge.world;

/**
 * One resident 16x16 block column. Owns palette-backed block storage and the
 * CPU mesh revisions for its vertical sections; terrain streaming owns lifetimes.
 * The save file still uses the historical column-major block indices.
 */
final class ChunkColumn {
    final int cx, cz;
    byte[] blocks = new byte[TerrainMesh.CHUNK_SIZE * TerrainMesh.CHUNK_SIZE * TerrainMesh.WORLD_HEIGHT];
    PaletteColumn compact;
    float[] lodVertices;
    float[] lodFluidVertices;
    int lodRevision;
    final float[][] sectionVertices = new float[TerrainMesh.SECTION_COUNT][];
    final float[][] fluidVertices = new float[TerrainMesh.SECTION_COUNT][];
    final int[] sectionRevision = new int[TerrainMesh.SECTION_COUNT];
    final int[] fluidRevision = new int[TerrainMesh.SECTION_COUNT];

    ChunkColumn(int cx, int cz) {
        this.cx = cx;
        this.cz = cz;
        for (int section = 0; section < TerrainMesh.SECTION_COUNT; section++) {
            sectionVertices[section] = new float[0];
            fluidVertices[section] = new float[0];
        }
    }

    byte get(int index) { return compact == null ? blocks[index] : compact.get(index); }
    void set(int index, byte block) {
        if (compact == null) blocks[index] = block;
        else compact.set(index, block);
    }
    byte[] copyBlocks() { return compact == null ? blocks.clone() : compact.copyRaw(); }
    void compact() {
        if (compact != null) return;
        compact = new PaletteColumn(blocks);
        blocks = null;
    }
}
