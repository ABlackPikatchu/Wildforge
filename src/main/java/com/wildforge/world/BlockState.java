package com.wildforge.world;

import java.util.Objects;
import java.util.Map;
import java.util.TreeMap;
import java.util.Collections;
import com.wildforge.content.ResourceId;

/**
 * Logical block identity plus orientation. Logs are ONE logical wood block
 * with an axis property. Other properties are stored in a sorted immutable map,
 * with integer runtime IDs and stable named save identities. Existing enum IDs
 * are compatibility aliases for worldgen, mesh building and older save data.
 */
public record BlockState(BlockType block, Axis axis, Map<String,String> properties, ResourceId resourceId) {
    public BlockState(BlockType block, Axis axis, Map<String,String> properties) {
        this(block, axis, properties, new ResourceId("wildforge", block.name().toLowerCase(java.util.Locale.ROOT)));
    }
    /** Create a registered data-only block. The legacy material is a temporary geometry fallback. */
    public static BlockState registered(ResourceId id, BlockType renderFallback) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(renderFallback, "renderFallback");
        return new BlockState(renderFallback, Axis.NONE, Map.of(), id);
    }

    public BlockState(BlockType block, Axis axis) { this(block, axis, Map.of()); }
    public enum Axis {
        NONE, X, Y, Z;
        /** Face normal axis: 0=X, 1=Y, 2=Z. */
        public static Axis fromFaceAxis(int axis) {
            return switch (axis) {
                case 0 -> X;
                case 1 -> Y;
                case 2 -> Z;
                default -> throw new IllegalArgumentException("Unknown face axis: " + axis);
            };
        }
    }

    private static final BlockState[] LEGACY = new BlockState[256];
    static {
        for (BlockType type : BlockType.values()) {
            BlockType canonical = canonical(type);
            Axis axis = axisFromLegacy(type, canonical);
            LEGACY[Byte.toUnsignedInt(type.getId())] = new BlockState(canonical, axis);
        }
    }

    public BlockState {
        Objects.requireNonNull(block, "block");
        Objects.requireNonNull(axis, "axis");
        Objects.requireNonNull(properties, "properties");
        Objects.requireNonNull(resourceId, "resourceId");
        TreeMap<String,String> validated = new TreeMap<>();
        for (var prop : properties.entrySet()) {
            if (!prop.getKey().matches("[a-z][a-z0-9_]*") ||
                prop.getValue() == null || !prop.getValue().matches("[a-zA-Z0-9_.-]+"))
                throw new IllegalArgumentException("Invalid state property: " + prop);
            if ("axis".equals(prop.getKey())) throw new IllegalArgumentException("Axis is an intrinsic log property");
            validated.put(prop.getKey(), prop.getValue());
        }
        properties = Collections.unmodifiableMap(validated);
        if (block != canonical(block))
            throw new IllegalArgumentException("Use canonical logical block, not legacy axis ID: " + block);
        boolean log = canonicalIsLog(block);
        if (log == (axis == Axis.NONE) && resourceId.getPath().equals(block.name().toLowerCase(java.util.Locale.ROOT)))
            throw new IllegalArgumentException("Axis must be X/Y/Z for logs and NONE for other blocks");
    }

    public boolean isLog() { return axis != Axis.NONE; }

    public static BlockState fromLegacy(BlockType legacy) {
        Objects.requireNonNull(legacy, "legacy");
        return LEGACY[Byte.toUnsignedInt(legacy.getId())];
    }

    public static BlockState fromLegacyId(byte id) {
        BlockState state = LEGACY[Byte.toUnsignedInt(id)];
        return state == null ? fromLegacy(BlockType.AIR) : state;
    }

    public BlockState withAxis(Axis newAxis) {
        if (!isLog()) return this;
        return new BlockState(block, newAxis, properties, resourceId);
    }

    /** Additional states can be stored without reserving new BlockType enum members. */
    public BlockState withProperty(String name, String value) {
        TreeMap<String,String> next = new TreeMap<>(properties);
        next.put(name, value);
        return new BlockState(block, axis, next, resourceId);
    }

    /** Stable on-disk identity. Runtime state numbers are NEVER written to disk. */
    public String persistentKey() {
        StringBuilder result = new StringBuilder(resourceId.toString());
        TreeMap<String,String> values = new TreeMap<>(properties);
        if (isLog()) values.put("axis", axis.name().toLowerCase(java.util.Locale.ROOT));
        if (!values.isEmpty()) {
            result.append('[');
            boolean first = true;
            for (var entry : values.entrySet()) {
                if (!first) result.append(',');
                result.append(entry.getKey()).append('=').append(entry.getValue());
                first = false;
            }
            result.append(']');
        }
        return result.toString();
    }

    public static BlockState fromPersistentKey(String key) {
        Objects.requireNonNull(key, "key");
        int bracket = key.indexOf('[');
        if (bracket >= 0 && (!key.endsWith("]") || bracket == key.length() - 2))
            throw new IllegalArgumentException("Invalid state key: " + key);
        ResourceId id = ResourceId.parse(bracket < 0 ? key : key.substring(0, bracket));
        BlockState base = BlockStateRegistry.GLOBAL.baseState(id);
        TreeMap<String,String> extras = new TreeMap<>();
        if (bracket >= 0) {
            String interior = key.substring(bracket + 1, key.length() - 1);
            if (interior.isBlank()) throw new IllegalArgumentException("Empty state properties");
            for (String pair : interior.split(",", -1)) {
                String[] parts = pair.split("=", -1);
                if (parts.length != 2 || extras.putIfAbsent(parts[0], parts[1]) != null)
                    throw new IllegalArgumentException("Invalid/duplicate property: " + pair);
            }
        }
        if (base.isLog()) {
            String direction = extras.remove("axis");
            if (direction == null) throw new IllegalArgumentException("Missing log axis in " + key);
            base = base.withAxis(Axis.valueOf(direction.toUpperCase(java.util.Locale.ROOT)));
        } else if (extras.containsKey("axis")) {
            throw new IllegalArgumentException("Non-log cannot have an axis: " + key);
        }
        return new BlockState(base.block, base.axis, extras, base.resourceId);
    }

    /** Legacy byte conversion is a *rendering fallback* for extra properties. */
    public BlockType toLegacyBlock() {
        return isLog() ? encodeLog(block, axis) : block;
    }

    public byte toLegacyId() { return toLegacyBlock().getId(); }

    private static boolean canonicalIsLog(BlockType type) {
        return switch (type) {
            case OAK_LOG,PINE_LOG,MAPLE_LOG,WILLOW_LOG,CYPRESS_LOG,BIRCH_LOG,SPRUCE_LOG,ACACIA_LOG,ALDER_LOG,MANGROVE_LOG,PALM_LOG -> true;
            default -> false;
        };
    }

    private static BlockType canonical(BlockType type) {
        return switch (type) {
            case OAK_LOG,OAK_LOG_X,OAK_LOG_Z -> BlockType.OAK_LOG;
            case PINE_LOG,PINE_LOG_X,PINE_LOG_Z -> BlockType.PINE_LOG;
            case MAPLE_LOG,MAPLE_LOG_X,MAPLE_LOG_Z -> BlockType.MAPLE_LOG;
            case WILLOW_LOG,WILLOW_LOG_X,WILLOW_LOG_Z -> BlockType.WILLOW_LOG;
            case CYPRESS_LOG,CYPRESS_LOG_X,CYPRESS_LOG_Z -> BlockType.CYPRESS_LOG;
            case BIRCH_LOG,BIRCH_LOG_X,BIRCH_LOG_Z -> BlockType.BIRCH_LOG;
            case SPRUCE_LOG,SPRUCE_LOG_X,SPRUCE_LOG_Z -> BlockType.SPRUCE_LOG;
            case ACACIA_LOG,ACACIA_LOG_X,ACACIA_LOG_Z -> BlockType.ACACIA_LOG;
            case ALDER_LOG,ALDER_LOG_X,ALDER_LOG_Z -> BlockType.ALDER_LOG;
            case MANGROVE_LOG,MANGROVE_LOG_X,MANGROVE_LOG_Z -> BlockType.MANGROVE_LOG;
            case PALM_LOG,PALM_LOG_X,PALM_LOG_Z -> BlockType.PALM_LOG;
            default -> type;
        };
    }

    private static Axis axisFromLegacy(BlockType type, BlockType base) {
        return switch (type) {
            case OAK_LOG_X,PINE_LOG_X,MAPLE_LOG_X,WILLOW_LOG_X,CYPRESS_LOG_X,BIRCH_LOG_X,SPRUCE_LOG_X,ACACIA_LOG_X,ALDER_LOG_X,MANGROVE_LOG_X,PALM_LOG_X -> Axis.X;
            case OAK_LOG_Z,PINE_LOG_Z,MAPLE_LOG_Z,WILLOW_LOG_Z,CYPRESS_LOG_Z,BIRCH_LOG_Z,SPRUCE_LOG_Z,ACACIA_LOG_Z,ALDER_LOG_Z,MANGROVE_LOG_Z,PALM_LOG_Z -> Axis.Z;
            default -> canonicalIsLog(base) ? Axis.Y : Axis.NONE;
        };
    }

    private static BlockType encodeLog(BlockType base, Axis axis) {
        return switch (base) {
            case OAK_LOG -> switch (axis) {
                case X -> BlockType.OAK_LOG_X;
                case Y -> BlockType.OAK_LOG;
                case Z -> BlockType.OAK_LOG_Z;
                case NONE -> throw new IllegalArgumentException("Log state must have an axis");
            };
            case PINE_LOG -> switch (axis) {
                case X -> BlockType.PINE_LOG_X;
                case Y -> BlockType.PINE_LOG;
                case Z -> BlockType.PINE_LOG_Z;
                case NONE -> throw new IllegalArgumentException("Log state must have an axis");
            };
            case MAPLE_LOG -> switch (axis) {
                case X -> BlockType.MAPLE_LOG_X;
                case Y -> BlockType.MAPLE_LOG;
                case Z -> BlockType.MAPLE_LOG_Z;
                case NONE -> throw new IllegalArgumentException("Log state must have an axis");
            };
            case WILLOW_LOG -> switch (axis) {
                case X -> BlockType.WILLOW_LOG_X;
                case Y -> BlockType.WILLOW_LOG;
                case Z -> BlockType.WILLOW_LOG_Z;
                case NONE -> throw new IllegalArgumentException("Log state must have an axis");
            };
            case CYPRESS_LOG -> switch (axis) {
                case X -> BlockType.CYPRESS_LOG_X;
                case Y -> BlockType.CYPRESS_LOG;
                case Z -> BlockType.CYPRESS_LOG_Z;
                case NONE -> throw new IllegalArgumentException("Log state must have an axis");
            };
            case BIRCH_LOG -> switch (axis) {
                case X -> BlockType.BIRCH_LOG_X;
                case Y -> BlockType.BIRCH_LOG;
                case Z -> BlockType.BIRCH_LOG_Z;
                case NONE -> throw new IllegalArgumentException("Log state must have an axis");
            };
            case SPRUCE_LOG -> switch (axis) {
                case X -> BlockType.SPRUCE_LOG_X;
                case Y -> BlockType.SPRUCE_LOG;
                case Z -> BlockType.SPRUCE_LOG_Z;
                case NONE -> throw new IllegalArgumentException("Log state must have an axis");
            };
            case ACACIA_LOG -> switch (axis) {
                case X -> BlockType.ACACIA_LOG_X;
                case Y -> BlockType.ACACIA_LOG;
                case Z -> BlockType.ACACIA_LOG_Z;
                case NONE -> throw new IllegalArgumentException("Log state must have an axis");
            };
            case ALDER_LOG -> switch (axis) {
                case X -> BlockType.ALDER_LOG_X;
                case Y -> BlockType.ALDER_LOG;
                case Z -> BlockType.ALDER_LOG_Z;
                case NONE -> throw new IllegalArgumentException("Log state must have an axis");
            };
            case MANGROVE_LOG -> switch (axis) {
                case X -> BlockType.MANGROVE_LOG_X;
                case Y -> BlockType.MANGROVE_LOG;
                case Z -> BlockType.MANGROVE_LOG_Z;
                case NONE -> throw new IllegalArgumentException("Log state must have an axis");
            };
            case PALM_LOG -> switch (axis) {
                case X -> BlockType.PALM_LOG_X;
                case Y -> BlockType.PALM_LOG;
                case Z -> BlockType.PALM_LOG_Z;
                case NONE -> throw new IllegalArgumentException("Log state must have an axis");
            };
            default -> throw new IllegalArgumentException("Not a log: " + base);
        };
    }
}
