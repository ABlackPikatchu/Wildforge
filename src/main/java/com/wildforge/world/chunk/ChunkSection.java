package com.wildforge.world.chunk;

import java.util.Arrays;

/**
 * Immutable palette-encoded 16x16x16 block section.
 *
 * The layout is (localX * 16 + localZ) * 16 + localY, matching Wildforge's
 * historical column-major save indices. Section updates produce a new section
 * so readers never observe a partially re-encoded palette.
 */
public final class ChunkSection {
    public static final int VOLUME = 16 * 16 * 16;
    private final byte[] palette;
    private final byte[] packed;
    private final int bits;

    private ChunkSection(byte[] palette, byte[] packed, int bits) {
        this.palette = palette;
        this.packed = packed;
        this.bits = bits;
    }

    public static ChunkSection encode(byte[] input) {
        if (input == null || input.length != VOLUME)
            throw new IllegalArgumentException("Section must contain exactly 4096 blocks");
        int[] indices = new int[256];
        Arrays.fill(indices, -1);
        byte[] unique = new byte[16];
        int used = 0;
        for (byte block : input) {
            int id = block & 255;
            if (indices[id] >= 0) continue;
            if (used == 16) return new ChunkSection(new byte[0], input.clone(), 8);
            indices[id] = used;
            unique[used++] = block;
        }
        if (used == 1) return new ChunkSection(new byte[]{unique[0]}, new byte[0], 0);
        int width = used <= 2 ? 1 : used <= 4 ? 2 : 4;
        byte[] data = new byte[(VOLUME * width + 7) / 8];
        for (int i = 0; i < VOLUME; i++) {
            int bit = i * width;
            data[bit >>> 3] |= (byte)(indices[input[i] & 255] << (bit & 7));
        }
        return new ChunkSection(Arrays.copyOf(unique, used), data, width);
    }

    public byte get(int index) {
        if (index < 0 || index >= VOLUME) throw new IndexOutOfBoundsException(index);
        if (bits == 0) return palette[0];
        if (bits == 8) return packed[index];
        int bit = index * bits;
        int paletteIndex = ((packed[bit >>> 3] & 255) >>> (bit & 7)) & ((1 << bits) - 1);
        return palette[paletteIndex];
    }

    public ChunkSection with(int index, byte block) {
        if (get(index) == block) return this;
        byte[] copy = decode();
        copy[index] = block;
        return encode(copy);
    }

    public byte[] decode() {
        if (bits == 8) return packed.clone();
        byte[] result = new byte[VOLUME];
        if (bits == 0) {
            Arrays.fill(result, palette[0]);
        } else {
            for (int i = 0; i < VOLUME; i++) result[i] = get(i);
        }
        return result;
    }

    public int storedBytes() { return palette.length + packed.length; }
}
