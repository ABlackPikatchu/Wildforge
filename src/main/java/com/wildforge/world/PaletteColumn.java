package com.wildforge.world;

import com.wildforge.world.chunk.ChunkSection;

/**
 * Column adapter: section storage uses 16^3 palette chunks while the public
 * indices and on-disk sparse edits retain the original 16x512x16 Y layout.
 */
final class PaletteColumn {
    private static final int WIDTH = TerrainMesh.CHUNK_SIZE;
    private static final int HEIGHT = TerrainMesh.WORLD_HEIGHT;
    private static final int SECTION_HEIGHT = TerrainMesh.SECTION_HEIGHT;
    private final ChunkSection[] sections = new ChunkSection[TerrainMesh.SECTION_COUNT];

    PaletteColumn(byte[] source) {
        if (source == null || source.length != WIDTH * WIDTH * HEIGHT)
            throw new IllegalArgumentException("Invalid column length");
        for (int sy = 0; sy < sections.length; sy++) {
            byte[] values = new byte[ChunkSection.VOLUME];
            for (int x = 0; x < WIDTH; x++) for (int z = 0; z < WIDTH; z++) {
                System.arraycopy(source, (x * WIDTH + z) * HEIGHT + sy * SECTION_HEIGHT,
                    values, (x * WIDTH + z) * SECTION_HEIGHT, SECTION_HEIGHT);
            }
            sections[sy] = ChunkSection.encode(values);
        }
    }

    int getStateId(int index) {
        int y = index % HEIGHT;
        return sections[y / SECTION_HEIGHT].stateIdAt(
                (index / HEIGHT) * SECTION_HEIGHT + (y % SECTION_HEIGHT));
    }

    void setStateId(int index, int stateId) {
        int y = index % HEIGHT;
        int section = y / SECTION_HEIGHT;
        int localIndex = (index / HEIGHT) * SECTION_HEIGHT + y % SECTION_HEIGHT;
        sections[section] = sections[section].withStateId(localIndex, stateId);
    }

    byte get(int index) {
        return BlockStateRegistry.GLOBAL.legacyRenderId(getStateId(index));
    }

    void set(int index, byte value) {
        setStateId(index, Byte.toUnsignedInt(value));
    }

    int[] copyStateIds() {
        int[] raw = new int[WIDTH * WIDTH * HEIGHT];
        for (int sy = 0; sy < sections.length; sy++) {
            int[] values = sections[sy].decodeStateIds();
            for (int x = 0; x < WIDTH; x++) for (int z = 0; z < WIDTH; z++) {
                System.arraycopy(values, (x * WIDTH + z) * SECTION_HEIGHT, raw,
                    (x * WIDTH + z) * HEIGHT + sy * SECTION_HEIGHT, SECTION_HEIGHT);
            }
        }
        return raw;
    }

    byte[] copyRaw() {
        byte[] raw = new byte[WIDTH * WIDTH * HEIGHT];
        for (int sy = 0; sy < sections.length; sy++) {
            int[] ids = sections[sy].decodeStateIds();
            byte[] values = new byte[ChunkSection.VOLUME];
            for (int i = 0; i < values.length; i++)
                values[i] = BlockStateRegistry.GLOBAL.legacyRenderId(ids[i]);
            for (int x = 0; x < WIDTH; x++) for (int z = 0; z < WIDTH; z++) {
                System.arraycopy(values, (x * WIDTH + z) * SECTION_HEIGHT, raw,
                    (x * WIDTH + z) * HEIGHT + sy * SECTION_HEIGHT, SECTION_HEIGHT);
            }
        }
        return raw;
    }

    int storedBytes() {
        int total = 0;
        for (ChunkSection section : sections) total += section.storedBytes();
        return total;
    }
}
