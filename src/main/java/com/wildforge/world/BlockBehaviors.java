package com.wildforge.world;

import com.wildforge.content.BlockCatalog;
import com.wildforge.content.BlockDefinition;
import com.wildforge.content.ResourceId;
import java.util.List;
import java.util.Objects;

/** Data-backed behavior queries shared by placement, harvesting and future ticks.
 * Game logic consults this service instead of multiplying per-block enum switches. */
public final class BlockBehaviors {
    public enum Support { NONE, BELOW, ABOVE }
    public record Behavior(ResourceId id, boolean solid, boolean breakable,
                           float hardness, Support support,
                           List<BlockDefinition.Drop> drops) {
        public Behavior { drops=List.copyOf(drops); }
        public boolean canPlace(BlockAccess world, BlockPos pos) {
            Objects.requireNonNull(world);Objects.requireNonNull(pos);
            if(support==Support.NONE)return true;
            int offset=support==Support.ABOVE?1:-1;
            BlockPos neighbor=new BlockPos(pos.x(),pos.y()+offset,pos.z());
            if(!world.isLoaded(neighbor))return false;
            BlockState state=world.getBlockState(neighbor);
            BlockDefinition backing=BlockCatalog.byId(state.resourceId());
            return backing==null ? state.toLegacyBlock().isSolid() : backing.isSolid();
        }
    }
    private BlockBehaviors() {}

    public static Behavior forBlock(ResourceId id) {
        BlockDefinition definition=BlockCatalog.byId(id);
        if(definition==null)return null;
        BlockModels.Shape shape=BlockModels.modelFor(id).shape();
        Support support=switch(shape) {
            case CROSS_PLANT, TALL_CROSS_PLANT, SPIKE, CRYSTAL -> Support.BELOW;
            case CEILING_ATTACHMENT -> Support.ABOVE;
            default -> Support.NONE;
        };
        // Stalactites attach above; stalagmites attach below.
        if(id.getPath().equals("stalactite"))support=Support.ABOVE;
        return new Behavior(id,definition.isSolid(),definition.isBreakable(),
                definition.getHardness(),support,definition.getDrops());
    }

    public static Behavior forBlock(BlockType type) {
        if(type==null)return null;
        ResourceId id=ResourceId.parse("wildforge:"+type.name().toLowerCase(java.util.Locale.ROOT));
        Behavior definition=forBlock(id);
        if(definition!=null)return definition;
        // Standalone tests may run without content loading. Preserve basic support rules.
        BlockModels.Shape shape=BlockModels.modelFor(BlockState.fromLegacy(type)).shape();
        Support support=switch(shape) {
            case CROSS_PLANT, TALL_CROSS_PLANT, SPIKE, CRYSTAL -> Support.BELOW;
            case CEILING_ATTACHMENT -> Support.ABOVE;
            default -> Support.NONE;
        };
        if(type==BlockType.STALACTITE)support=Support.ABOVE;
        return new Behavior(id,type.isSolid(),type.isBreakable(),.5f,support,List.of());
    }
}
