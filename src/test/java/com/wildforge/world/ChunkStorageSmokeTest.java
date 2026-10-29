package com.wildforge.world;

import com.wildforge.world.chunk.ChunkSection;
import java.util.Random;
import java.util.Arrays;

/** Verifies section palette transitions and legacy 512-height indexing. */
public final class ChunkStorageSmokeTest {
    public static void main(String[] args) {
        byte[] solid = new byte[ChunkSection.VOLUME];
        Arrays.fill(solid, (byte)127);
        ChunkSection base = ChunkSection.encode(solid);
        require(base.storedBytes() == Integer.BYTES, "single-value compression");
        require(base.get(4095) == 127, "max section index");
        ChunkSection changed = base.with(0, (byte)-128);
        require(base.get(0) == 127, "copy-on-write immutability");
        require(changed.get(0) == -128, "unsigned high-bit block ids preserved");
        require(changed.with(0, (byte)-128) == changed, "no-op update reuses section");

        Random random = new Random(0x574F524CL);
        for (int paletteSize : new int[]{1, 2, 4, 8, 16, 17, 40, 256}) {
            byte[] raw = new byte[ChunkSection.VOLUME];
            for (int i = 0; i < raw.length; i++) raw[i] = (byte)random.nextInt(paletteSize);
            ChunkSection encoded = ChunkSection.encode(raw);
            require(Arrays.equals(raw, encoded.decode()), "lossless section with " + paletteSize + " values");
        }

        byte[] original = new byte[16 * 16 * 512];
        random.nextBytes(original);
        PaletteColumn column = new PaletteColumn(original);
        require(Arrays.equals(original, column.copyRaw()), "whole-column migration round-trip");
        for (int y : new int[]{0, 15, 16, 191, 192, 511}) {
            int idx = ChunkCoordinates.localIndex(15, y, 15);
            byte expected = (byte)(original[idx] ^ 0xff);
            column.set(idx, expected);
            original[idx] = expected;
        }
        require(Arrays.equals(original, column.copyRaw()), "edits across 16-block section boundaries");
        var firstRender = new com.wildforge.world.mesh.ChunkRenderData(32);
        firstRender.installSection(0, new float[]{1f}, new float[]{2f});
        var secondRender = new com.wildforge.world.mesh.ChunkRenderData(32);
        secondRender.installSection(0, new float[]{3f}, new float[]{4f});
        require(firstRender.terrainRevision(0) != secondRender.terrainRevision(0),
                "reloaded same-coordinate mesh gets a fresh GPU revision");
        require(firstRender.distantRevision() != secondRender.distantRevision(),
                "reloaded LOD gets a fresh GPU revision");
        System.out.println("ChunkStorage passed: palette sections, 512-high indexing, fresh mesh revisions");
    }
    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
