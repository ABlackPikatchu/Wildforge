package com.wildforge.content;

import java.util.List;
import java.util.Objects;
import com.wildforge.lang.LanguageManager;

/** Immutable data definition; the existing BlockType enum remains the gameplay authority. */
public final class BlockDefinition {
    public record Drop(ResourceId item, int count) {
        public Drop {
            Objects.requireNonNull(item, "drop item");
            if (count < 1 || count > 64) {
                throw new IllegalArgumentException("Drop count must be 1..64");
            }
        }
    }

    private final ResourceId id;
    private final int numericId;
    private final boolean solid;
    private final boolean breakable;
    private final float hardness;
    private final ResourceId blockItem;
    private final ResourceId model;
    private final ResourceId topTexture;
    private final ResourceId sideTexture;
    private final ResourceId bottomTexture;
    private final List<Drop> drops;

    public BlockDefinition(ResourceId id, int numericId, boolean solid,
                           boolean breakable, float hardness, ResourceId blockItem,
                           ResourceId model, ResourceId topTexture,
                           ResourceId sideTexture, ResourceId bottomTexture,
                           List<Drop> drops) {
        this.id = Objects.requireNonNull(id, "id");
        if (numericId < 0 || numericId > 255) {
            throw new IllegalArgumentException("Block numeric_id must be 0..255: " + id);
        }
        if (!Float.isFinite(hardness) || hardness < 0) {
            throw new IllegalArgumentException("Invalid hardness for " + id);
        }
        this.numericId = numericId;
        this.solid = solid;
        this.breakable = breakable;
        this.hardness = hardness;
        this.blockItem = blockItem;
        this.model = model;
        this.topTexture = topTexture;
        this.sideTexture = sideTexture;
        this.bottomTexture = bottomTexture;
        this.drops = List.copyOf(Objects.requireNonNull(drops, "drops"));
    }

    public ResourceId getId() { return id; }
    public String getTranslationKey() { return LanguageManager.blockKey(id); }
    public String getDisplayName() {
        return LanguageManager.blockName(id, id.getPath().replace('_', ' '));
    }
    public int getNumericId() { return numericId; }
    public boolean isSolid() { return solid; }
    public boolean isBreakable() { return breakable; }
    public float getHardness() { return hardness; }
    /** Null for non-placeable blocks such as air and bedrock. */
    public ResourceId getBlockItem() { return blockItem; }
    public ResourceId getModel() { return model; }
    public ResourceId getTopTexture() { return topTexture; }
    public ResourceId getSideTexture() { return sideTexture; }
    public ResourceId getBottomTexture() { return bottomTexture; }
    public List<Drop> getDrops() { return drops; }
}
