package com.wildforge.world;

import java.util.Arrays;

/** Compact, mutable 16x256x16 resident block storage. No save-format changes. */
final class PaletteColumn {
    private static final int SECTIONS = 16, WIDTH = 16, CELLS = 4096;
    private final Section[] sections = new Section[SECTIONS];

    PaletteColumn(byte[] source) {
        if (source.length != 65536) throw new IllegalArgumentException("Invalid column length");
        for (int sy = 0; sy < SECTIONS; sy++) {
            byte[] values = new byte[CELLS];
            for (int x = 0; x < WIDTH; x++) for (int z = 0; z < WIDTH; z++) {
                System.arraycopy(source, (x * WIDTH + z) * 256 + sy * 16,
                    values, (x * WIDTH + z) * 16, 16);
            }
            sections[sy] = Section.encode(values);
        }
    }

    byte get(int sourceIndex) {
        int y = sourceIndex & 255;
        int column = sourceIndex >>> 8;
        return sections[y >>> 4].get(column * 16 + (y & 15));
    }

    void set(int sourceIndex, byte value) {
        int y = sourceIndex & 255;
        int section = y >>> 4;
        int i = (sourceIndex >>> 8) * 16 + (y & 15);
        Section old = sections[section];
        if (old.get(i) == value) return;
        byte[] values = old.decode();
        values[i] = value;
        sections[section] = Section.encode(values);
    }

    byte[] copyRaw() {
        byte[] raw = new byte[65536];
        for (int sy = 0; sy < SECTIONS; sy++) {
            byte[] values = sections[sy].decode();
            for (int x = 0; x < WIDTH; x++) for (int z = 0; z < WIDTH; z++) {
                System.arraycopy(values, (x * WIDTH + z) * 16, raw,
                    (x * WIDTH + z) * 256 + sy * 16, 16);
            }
        }
        return raw;
    }

    int storedBytes() {
        int total = 0;
        for (Section s : sections) total += s.data.length + s.palette.length;
        return total;
    }

    private static final class Section {
        final byte[] palette;
        final byte[] data;
        final int bits;
        Section(byte[] palette, byte[] data, int bits) {
            this.palette = palette; this.data = data; this.bits = bits;
        }
        static Section encode(byte[] input) {
            int[] indices = new int[256];
            Arrays.fill(indices, -1);
            byte[] palette = new byte[16];
            int used = 0;
            for (byte v : input) {
                int k = v & 255;
                if (indices[k] >= 0) continue;
                if (used == 16) return new Section(new byte[0], input.clone(), 8);
                indices[k] = used;
                palette[used++] = v;
            }
            if (used == 1) return new Section(new byte[]{palette[0]}, new byte[0], 0);
            int bits = used <= 2 ? 1 : used <= 4 ? 2 : 4;
            byte[] encoded = new byte[(CELLS * bits + 7) / 8];
            int mask = (1 << bits) - 1;
            for (int i = 0; i < CELLS; i++) {
                int shift = (i * bits) & 7;
                encoded[(i * bits) >>> 3] |= (byte)((indices[input[i] & 255] & mask) << shift);
            }
            return new Section(Arrays.copyOf(palette, used), encoded, bits);
        }
        byte get(int i) {
            if (bits == 0) return palette[0];
            if (bits == 8) return data[i];
            int pos = i * bits;
            return palette[((data[pos >>> 3] & 255) >>> (pos & 7)) & ((1 << bits) - 1)];
        }
        byte[] decode() {
            if (bits == 8) return data.clone();
            byte[] out = new byte[CELLS];
            if (bits == 0) { Arrays.fill(out, palette[0]); return out; }
            for (int i = 0; i < CELLS; i++) out[i] = get(i);
            return out;
        }
    }
}
