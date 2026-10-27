package com.wildforge.item;

/** An inventory stack of one item type. */
public final class ItemStack {
    private final ItemType itemType;
    private int count;

    public ItemStack(ItemType itemType, int count) {
        if (itemType == null || count <= 0) {
            this.itemType = null;
            this.count = 0;
        } else {
            this.itemType = itemType;
            this.count = Math.min(count, itemType.getMaxStackSize());
        }
    }

    public ItemType getItemType() {
        return itemType;
    }

    public int getCount() {
        return count;
    }

    public void setCount(int count) {
        if (itemType == null) {
            this.count = 0;
            return;
        }
        this.count = Math.max(
                0, Math.min(count, itemType.getMaxStackSize()));
    }

    public boolean isEmpty() {
        return itemType == null || count <= 0;
    }
}