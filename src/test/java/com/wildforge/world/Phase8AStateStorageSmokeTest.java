package com.wildforge.world;

import com.wildforge.world.chunk.ChunkSection;
import java.io.*;
import java.util.*;

/** Run without OpenGL or a world save. Exercises all bits and stable remapping. */
public final class Phase8AStateStorageSmokeTest {
    private static void check(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
    }
    public static void main(String[] args) throws Exception {
        BlockStateRegistry registry = BlockStateRegistry.GLOBAL;
        for (BlockType type : BlockType.values()) {
            int legacyId = Byte.toUnsignedInt(type.getId());
            check(registry.byId(legacyId).equals(BlockState.fromLegacy(type)), "legacy id " + legacyId);
            check(registry.byId(legacyId).toLegacyBlock() == type, "legacy orientation " + type);
        }
        BlockState oriented = BlockState.fromLegacy(BlockType.OAK_LOG)
                .withAxis(BlockState.Axis.X).withProperty("waterlogged", "true");
        int customId = registry.idFor(oriented);
        check(customId > 255, "extra properties not restricted to byte ID");
        check(registry.idFor(oriented) == customId, "interned state identity");
        check(oriented.equals(BlockState.fromPersistentKey(oriented.persistentKey())), "persistent state key");
        check(registry.legacyRenderId(customId) == BlockType.OAK_LOG_X.getId(), "render adapter");
        check(!oriented.equals(BlockState.fromLegacy(BlockType.OAK_LOG_X)), "new property preserved");

        // Content definitions are already namespaced; future numeric content
        // IDs are no longer artificially rejected by the block registry.
        var futureBlocks = new com.wildforge.content.BlockRegistry();
        var futureId = com.wildforge.content.ResourceId.parse("wildforge:future_block");
        futureBlocks.register(new com.wildforge.content.BlockDefinition(
                futureId, 1024, true, true, 2f, null, null, null, null, null, java.util.List.of()));
        check(futureBlocks.getByNumericId(1024) != null, "extended definition numeric ID");
        var futureItems = new com.wildforge.content.ItemRegistry();
        futureItems.register(new com.wildforge.content.ItemDefinition(
                com.wildforge.content.ResourceId.parse("wildforge:future_item"),
                2048, 64, futureId, null, null));
        check(futureItems.getByNumericId(2048) != null, "namespaced item registry future ID");
        Random rng = new Random(82401);
        for (int unique : new int[]{1,2,3,4,7,16,17,31,40,65,128,256,257,4096}) {
            int[] raw = new int[ChunkSection.VOLUME];
            for (int i = 0; i < raw.length; i++) raw[i] = rng.nextInt(unique) + 1000;
            ChunkSection section = ChunkSection.encodeStates(raw);
            check(Arrays.equals(raw, section.decodeStateIds()), "bits round trip palette=" + unique);
            int slot = 1917;
            ChunkSection changed = section.withStateId(slot, 999_999);
            check(changed.stateIdAt(slot) == 999_999 && section.stateIdAt(slot) == raw[slot], "immutable replacement");
            int reused = raw[22];
            changed = section.withStateId(slot, reused);
            check(changed.stateIdAt(slot) == reused, "fast palette reuse across word boundary");
            check(changed.withStateId(slot, reused) == changed, "no-op identity");
        }
        // Check all index offsets for 3, 5 and 9-bit palette values.
        for (int size : new int[]{5,17,300}) {
            int[] raw = new int[ChunkSection.VOLUME];
            for (int i=0;i<raw.length;i++)raw[i]=i%size;
            ChunkSection section = ChunkSection.encodeStates(raw);
            for (int i=0;i<raw.length;i+=37) {
                ChunkSection next=section.withStateId(i,raw[(i+1)%raw.length]);
                check(next.stateIdAt(i)==raw[(i+1)%raw.length],"mutated bits at "+i);
            }
        }
        byte[] source = new byte[16*16*512];
        PaletteColumn column = new PaletteColumn(source);
        for (int y : new int[]{0,15,16,127,128,511}) {
            int i = ChunkCoordinates.localIndex(15,y,15);
            column.setStateId(i, customId);
            check(column.getStateId(i)==customId, "typed column at y=" + y);
            check(column.get(i)==BlockType.OAK_LOG_X.getId(), "legacy renderer mapping");
        }
        ChunkColumn live = new ChunkColumn(-12, 27);
        int local = ChunkCoordinates.localIndex(15, 511, 0);
        live.setStateId(local, customId);
        check(live.stateId(15, 511, 0) == customId, "chunk access exact typed state");
        BlockPos negative = new BlockPos(-1, 11, -17);
        check(negative.chunkX() == -1 && negative.chunkZ() == -2 &&
              negative.localX() == 15 && negative.localZ() == 15,
              "negative world position to chunk coordinates");
        // Sparse save keys must not include runtime IDs. Round-trip and corruption checks.
        HashMap<Long,Map<Integer,Integer>> edits = new HashMap<>();
        long chunk = ChunkCoordinates.pack(-12, 27);
        edits.put(chunk, Map.of(1, customId, 4095, 128, 127*512, 0));
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        StateEditCodec.write(new DataOutputStream(sink), edits, registry);
        var decoded = StateEditCodec.read(new DataInputStream(new ByteArrayInputStream(sink.toByteArray())), registry);
        check(decoded.get(chunk).equals(edits.get(chunk)), "persistent palette round trip");
        check(Arrays.equals(sink.toByteArray(), encode(edits,registry)), "deterministic edit encoding");
        byte[] damaged = sink.toByteArray();
        damaged[0] = (byte) 0x7f;
        try {
            StateEditCodec.read(new DataInputStream(new ByteArrayInputStream(damaged)), registry);
            throw new AssertionError("corrupt palette size accepted");
        } catch (IOException expected) { /* expected */ }
        System.out.println("Phase 8A passed: 195 existing states, typed properties, packed sections, stable save palettes");
    }
    private static byte[] encode(Map<Long,? extends Map<Integer,Integer>> edits,BlockStateRegistry registry) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        StateEditCodec.write(new DataOutputStream(bytes),edits,registry);
        return bytes.toByteArray();
    }
}
