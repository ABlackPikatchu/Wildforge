package com.wildforge.content;

import com.wildforge.world.BlockType;
import java.util.Locale;
import java.util.Objects;

/** Installed block data is authoritative; enums remain adapters for worldgen and meshing. */
public final class BlockCatalog {
    private static volatile BlockRegistry registry;
    private BlockCatalog() {}
    public static void install(BlockRegistry definitions) { registry=Objects.requireNonNull(definitions); }
    public static BlockDefinition byId(ResourceId id) {
        BlockRegistry current=registry;
        return current==null ? null : current.get(id);
    }
    public static BlockDefinition legacy(BlockType type) {
        return type==null ? null : byId(ResourceId.parse("wildforge:"+type.name().toLowerCase(Locale.ROOT)));
    }
    /** null when no legacy geometry/physics bridge exists yet. */
    public static BlockType legacy(ResourceId id) {
        if(id==null)return null;
        var definition=byId(id);
        if(definition==null)return null;
        try {
            BlockType type=BlockType.fromId((byte)definition.getNumericId());
            return Byte.toUnsignedInt(type.getId())==definition.getNumericId()
                    && id.equals(ResourceId.parse("wildforge:"+type.name().toLowerCase(Locale.ROOT)))
                    ? type : null;
        }catch(RuntimeException ex){return null;}
    }
}
