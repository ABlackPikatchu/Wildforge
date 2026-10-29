package com.wildforge.world;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import com.wildforge.content.BlockRegistry;
import com.wildforge.content.BlockDefinition;
import com.wildforge.content.ResourceId;

/**
 * Session-wide identity table for canonical block states. The first 256 slots are
 * reserved for historical block encodings, so new properties never collide with
 * old bytes. A runtime ID is not a serialization ID: saves use persistent keys.
 *
 * Register content before use on the simulation thread; worker threads may read
 * immutable built-ins but must never introduce new dynamic states themselves.
 */
public final class BlockStateRegistry {
    public static final int MAX_STATES = 1 << 20;
    private final ArrayList<BlockState> states = new ArrayList<>();
    private final Map<BlockState,Integer> ids = new HashMap<>();
    /** Read-only published lookup: mesh workers must never lock on every voxel. */
    private volatile BlockState[] published;
    private final Map<ResourceId,BlockState> baseStates = new HashMap<>();

    private BlockStateRegistry() {
        for (int i = 0; i < 256; i++) states.add(null);
        for (BlockType type : BlockType.values()) {
            int id = Byte.toUnsignedInt(type.getId());
            BlockState state = BlockState.fromLegacy(type);
            if (states.get(id) != null) throw new IllegalStateException("Duplicate legacy state " + id);
            states.set(id, state);
            ids.putIfAbsent(state, id);
            baseStates.putIfAbsent(state.resourceId(), BlockState.fromLegacy(type));
        }
        published=states.toArray(BlockState[]::new);
    }

    public static final BlockStateRegistry GLOBAL = new BlockStateRegistry();

    /** Explicit registration; a saved unknown ID is never silently replaced with air. */
    public synchronized void register(BlockDefinition definition) {
        Objects.requireNonNull(definition, "definition");
        ResourceId id = definition.getId();
        if (baseStates.containsKey(id)) return; // built-in aliases share their registry identity
        // Content IDs do not describe their physics/material. Until the native
        // collision/render material pipeline is complete, choose a stable
        // fallback rather than aliasing an unrelated legacy numeric ID.
        BlockType fallback = BlockType.STONE;
        BlockState registered = BlockState.registered(id, fallback);
        baseStates.put(id, registered);
        idFor(registered);
    }
    public synchronized void install(BlockRegistry definitions) {
        for (BlockDefinition definition : definitions.getAll()) register(definition);
    }
    public synchronized BlockState baseState(ResourceId id) {
        BlockState state = baseStates.get(Objects.requireNonNull(id));
        if (state == null) throw new IllegalArgumentException("Unregistered block: " + id);
        return state;
    }
    public synchronized boolean isRegistered(ResourceId id) { return baseStates.containsKey(id); }


    public synchronized int idFor(BlockState state) {
        Objects.requireNonNull(state, "state");
        Integer known = ids.get(state);
        if (known != null) return known;
        if (states.size() >= MAX_STATES) throw new IllegalStateException("Too many block states");
        int id = states.size();
        states.add(state);
        ids.put(state, id);
        published=states.toArray(BlockState[]::new);
        return id;
    }

    public BlockState byId(int id) {
        BlockState[] table=published;
        if (id < 0 || id >= table.length || table[id] == null)
            throw new IllegalArgumentException("Unknown block state ID " + id);
        return table[id];
    }

    /** Returns the old render/material ID, not a persistent state identifier. */
    public byte legacyRenderId(int id) {
        // Raw legacy section bytes include up to 256 possible values, even
        // where the current content catalog intentionally leaves gaps.
        if (id >= 0 && id <= 255) return (byte)id;
        return byId(id).toLegacyId();
    }
    public synchronized int idFromKey(String key) { return idFor(BlockState.fromPersistentKey(key)); }
    public synchronized String persistentKey(int id) { return byId(id).persistentKey(); }
    public synchronized int size() { return ids.size(); }
}
