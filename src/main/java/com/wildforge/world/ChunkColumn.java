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
    final com.wildforge.world.mesh.ChunkRenderData render =
            new com.wildforge.world.mesh.ChunkRenderData(TerrainMesh.SECTION_COUNT);

    ChunkColumn(int cx, int cz) {
        this.cx = cx;
        this.cz = cz;
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
