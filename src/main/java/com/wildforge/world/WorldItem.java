
package com.wildforge.world;

import com.wildforge.item.ItemType;

/**
 * One physical stack of dropped items in the world.
 */
public final class WorldItem {
    private final ItemType itemType;
    private final int count;

    private float x;
    private float y;
    private float z;
    private float age;

    public WorldItem(
            ItemType itemType,
            int count,
            float x,
            float y,
            float z
    ) {
        if (itemType == null) {
            throw new IllegalArgumentException("Item type cannot be null");
        }
        if (count < 1 || count > itemType.getMaxStackSize()) {
            throw new IllegalArgumentException("Invalid dropped stack count");
        }
        if (!Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(z)) {
            throw new IllegalArgumentException("Drop position must be finite");
        }

        this.itemType = itemType;
        this.count = count;
        this.x = x;
        this.y = y;
        this.z = z;
    }

    public ItemType getItemType() {
        return itemType;
    }

    public int getCount() {
        return count;
    }

    public float getX() {
        return x;
    }

    public float getY() {
        return y;
    }

    public float getZ() {
        return z;
    }

    public float getAge() {
        return age;
    }

    /** Slight vertical movement for the eventual world-drop renderer. */
    public float getRenderY() {
        return y + (float) Math.sin(age * 3.0f) * 0.08f;
    }

    void update(float deltaTime) {
        age += deltaTime;
    }

    void setPosition(float x, float y, float z) {
        this.x = x;
        this.y = y;
        this.z = z;
    }

    void setAge(float age) {
        this.age = Math.max(0.0f, age);
    }
}
