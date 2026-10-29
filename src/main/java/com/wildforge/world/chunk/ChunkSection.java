package com.wildforge.world.chunk;

import java.util.Arrays;

/**
 * Immutable bit-packed palette of 4096 runtime state IDs. The indices are
 * (x*16+z)*16+y, matching the existing column data layout. Palette indices
 * are bit-packed into long words, including boundary-spanning entries.
 */
public final class ChunkSection {
    public static final int VOLUME = 4096;
    private final int[] palette;
    private final long[] words;
    private final int bits;

    private ChunkSection(int[] palette, long[] words, int bits) {
        this.palette = palette;
        this.words = words;
        this.bits = bits;
    }

    public static ChunkSection encode(byte[] bytes) {
        if (bytes == null || bytes.length != VOLUME)
            throw new IllegalArgumentException("Section must contain 4096 blocks");
        int[] ids = new int[VOLUME];
        for (int i = 0; i < VOLUME; i++) ids[i] = Byte.toUnsignedInt(bytes[i]);
        return encodeStates(ids);
    }

    public static ChunkSection encodeStates(int[] ids) {
        if (ids == null || ids.length != VOLUME)
            throw new IllegalArgumentException("Section must contain 4096 block states");
        java.util.LinkedHashMap<Integer,Integer> lookup = new java.util.LinkedHashMap<>();
        for (int id : ids) {
            if (id < 0 || id >= (1 << 20)) throw new IllegalArgumentException("Invalid state ID: " + id);
            lookup.computeIfAbsent(id, ignored -> lookup.size());
        }
        int[] palette = new int[lookup.size()];
        for (var entry : lookup.entrySet()) palette[entry.getValue()] = entry.getKey();
        if (palette.length == 1) return new ChunkSection(palette, new long[0], 0);
        int bits = 32 - Integer.numberOfLeadingZeros(palette.length - 1);
        long[] packed = new long[(VOLUME * bits + 63) >>> 6];
        for (int i = 0; i < VOLUME; i++) {
            long index = lookup.get(ids[i]);
            int bitOffset = i * bits, wordIndex = bitOffset >>> 6, shift = bitOffset & 63;
            packed[wordIndex] |= index << shift;
            if (shift + bits > 64) packed[wordIndex + 1] |= index >>> (64 - shift);
        }
        return new ChunkSection(palette, packed, bits);
    }

    public int stateIdAt(int index) {
        if (index < 0 || index >= VOLUME) throw new IndexOutOfBoundsException(index);
        if (bits == 0) return palette[0];
        int offset = index * bits, word = offset >>> 6, shift = offset & 63;
        long data = words[word] >>> shift;
        if (shift + bits > 64) data |= words[word + 1] << (64 - shift);
        return palette[(int)(data & ((1L << bits) - 1))];
    }

    public ChunkSection withStateId(int index, int id) {
        if (id < 0 || id >= (1 << 20)) throw new IllegalArgumentException("Invalid state ID: " + id);
        if (stateIdAt(index) == id) return this;
        // Most gameplay edits reuse a material already in this section. Change
        // one packed palette index without allocating/encoding 4096 values.
        for (int paletteIndex = 0; paletteIndex < palette.length; paletteIndex++) {
            if (palette[paletteIndex] != id) continue;
            if (bits == 0) return this; // The only palette value was handled above.
            long[] updated = words.clone();
            int offset = index * bits, word = offset >>> 6, shift = offset & 63;
            long mask = (1L << bits) - 1L;
            updated[word] = (updated[word] & ~(mask << shift)) | ((long)paletteIndex << shift);
            if (shift + bits > 64) {
                int remaining = shift + bits - 64;
                long upperMask = (1L << remaining) - 1;
                updated[word + 1] = (updated[word + 1] & ~upperMask) |
                        ((long)paletteIndex >>> (64 - shift));
            }
            return new ChunkSection(palette, updated, bits);
        }
        int[] values = decodeStateIds();
        values[index] = id;
        return encodeStates(values);
    }

    public int[] decodeStateIds() {
        int[] values = new int[VOLUME];
        if (bits == 0) Arrays.fill(values, palette[0]);
        else for (int i = 0; i < values.length; i++) values[i] = stateIdAt(i);
        return values;
    }

    // Transitional adapters for existing byte-oriented mesh and worldgen APIs.
    public byte get(int index) {
        int stateId = stateIdAt(index);
        if (stateId > 255) throw new IllegalStateException("State " + stateId + " requires the typed state API");
        return (byte)stateId;
    }
    public ChunkSection with(int index, byte value) { return withStateId(index, Byte.toUnsignedInt(value)); }
    public byte[] decode() {
        byte[] values = new byte[VOLUME];
        for (int i = 0; i < values.length; i++) values[i] = get(i);
        return values;
    }
    public int paletteSize() { return palette.length; }
    public int storedBytes() { return palette.length * Integer.BYTES + words.length * Long.BYTES; }
}
