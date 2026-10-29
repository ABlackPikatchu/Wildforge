package com.wildforge.world;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Sparse column edits, version 6. A per-file namespaced state palette avoids
 * persisting JVM-local runtime IDs. Sorted palette/chunk/position order makes
 * repeated saves byte-for-byte deterministic for the same world state.
 */
public final class StateEditCodec {
    private static final int MAX_CHUNKS = 1_000_000;
    private static final int MAX_EDIT_INDEX = 16 * 16 * 512;
    private static final int MAX_PALETTE = 65536;

    private StateEditCodec() {}

    public static void write(DataOutput out, Map<Long, ? extends Map<Integer,Integer>> edits,
                             BlockStateRegistry registry) throws IOException {
        TreeSet<String> keys = new TreeSet<>();
        for (var chunk : edits.values())
            for (int stateId : chunk.values()) keys.add(registry.persistentKey(stateId));
        if (keys.size() > MAX_PALETTE || edits.size() > MAX_CHUNKS)
            throw new IOException("World state palette or chunk count exceeds limit");
        ArrayList<String> palette = new ArrayList<>(keys);
        HashMap<String,Integer> positions = new HashMap<>();
        out.writeInt(palette.size());
        for (int i = 0; i < palette.size(); i++) {
            positions.put(palette.get(i), i);
            out.writeUTF(palette.get(i));
        }
        out.writeInt(edits.size());
        TreeMap<Long,Map<Integer,Integer>> chunks = new TreeMap<>();
        for (var entry : edits.entrySet()) chunks.put(entry.getKey(), entry.getValue());
        for (var entry : chunks.entrySet()) {
            if (entry.getValue().size() > MAX_EDIT_INDEX)
                throw new IOException("Too many edits in chunk");
            out.writeLong(entry.getKey());
            out.writeInt(entry.getValue().size());
            for (var change : new TreeMap<>(entry.getValue()).entrySet()) {
                if (change.getKey() < 0 || change.getKey() >= MAX_EDIT_INDEX)
                    throw new IOException("Invalid edit index " + change.getKey());
                out.writeInt(change.getKey());
                out.writeInt(positions.get(registry.persistentKey(change.getValue())));
            }
        }
    }

    public static HashMap<Long,HashMap<Integer,Integer>> read(DataInput in,
                                                              BlockStateRegistry registry) throws IOException {
        int count = in.readInt();
        if (count < 0 || count > MAX_PALETTE) throw new IOException("Invalid state palette size");
        int[] palette = new int[count];
        TreeSet<String> unique = new TreeSet<>();
        for (int i = 0; i < count; i++) {
            String key = in.readUTF();
            if (!unique.add(key)) throw new IOException("Duplicate state palette entry: " + key);
            try { palette[i] = registry.idFromKey(key); }
            catch (RuntimeException error) { throw new IOException("Unknown or invalid saved block state: " + key, error); }
        }
        int chunks = in.readInt();
        if (chunks < 0 || chunks > MAX_CHUNKS) throw new IOException("Invalid chunk count");
        HashMap<Long,HashMap<Integer,Integer>> results = new HashMap<>();
        for (int n = 0; n < chunks; n++) {
            long chunkKey = in.readLong();
            int edits = in.readInt();
            if (edits < 0 || edits > MAX_EDIT_INDEX) throw new IOException("Invalid edit count");
            HashMap<Integer,Integer> values = new HashMap<>();
            for (int i = 0; i < edits; i++) {
                int index = in.readInt(), paletteId = in.readInt();
                if (index < 0 || index >= MAX_EDIT_INDEX || paletteId < 0 || paletteId >= count ||
                    values.putIfAbsent(index, palette[paletteId]) != null)
                    throw new IOException("Invalid or duplicate block state edit");
            }
            if (results.putIfAbsent(chunkKey, values) != null) throw new IOException("Duplicate chunk");
        }
        return results;
    }
}
