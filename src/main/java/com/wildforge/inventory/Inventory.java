package com.wildforge.inventory;

import com.wildforge.item.ItemStack;
import com.wildforge.item.ItemType;
import org.lwjgl.glfw.GLFW;

/** Owns item slots, cursor stack, hotbar selection, and inventory operations. */
public final class Inventory {
    public static final int HOTBAR_SLOTS = 9;
    public static final int HOTBAR_START_INDEX = 27;
    public static final int MAIN_INVENTORY_SLOTS = 27;
    public static final int TOTAL_SLOTS = 36;

    private final ItemStack[] slots = new ItemStack[TOTAL_SLOTS];
    private ItemStack cursorStack;
    private int selectedHotbarSlot;
    private double hotbarScrollRemainder;

    public ItemStack getCursorStack() { return cursorStack; }

    public ItemStack getStack(int slot) {
        if (slot < 0 || slot >= TOTAL_SLOTS) return null;
        ItemStack stack = slots[slot];
        return stack == null || stack.isEmpty() ? null : stack;
    }

    public ItemStack getSelectedStack() { return slots[HOTBAR_START_INDEX + selectedHotbarSlot]; }

    public ItemType getHotbarItem(int slot) {
        if (slot < 0 || slot >= HOTBAR_SLOTS) return null;
        ItemStack stack = getStack(HOTBAR_START_INDEX + slot);
        return stack == null ? null : stack.getItemType();
    }

    public int getSelectedHotbarSlot() { return selectedHotbarSlot; }

    public void selectHotbarSlot(int slot) {
        if (slot >= 0 && slot < HOTBAR_SLOTS) selectedHotbarSlot = slot;
    }

    public void scrollHotbar(double amount) {
        if (!Double.isFinite(amount) || amount == 0.0) return;
        hotbarScrollRemainder += amount;
        int steps = (int) hotbarScrollRemainder;
        if (steps == 0) return;
        hotbarScrollRemainder -= steps;
        selectedHotbarSlot = Math.floorMod(selectedHotbarSlot - steps, HOTBAR_SLOTS);
    }

    public void setSelectedHotbarSlot(int slot) {
        if (slot < 0 || slot >= HOTBAR_SLOTS) throw new IllegalArgumentException("Invalid hotbar slot: " + slot);
        selectedHotbarSlot = slot;
    }

    public void setStack(int slot, ItemStack stack) {
        if (slot < 0 || slot >= TOTAL_SLOTS) throw new IndexOutOfBoundsException("Invalid inventory slot: " + slot);
        slots[slot] = stack == null || stack.isEmpty() ? null : stack;
    }

    /** Replaces slot contents after save data has been fully validated. */
    public void setContents(ItemStack[] contents) {
        if (contents == null || contents.length != TOTAL_SLOTS) throw new IllegalArgumentException("Inventory must contain 36 slots");
        System.arraycopy(contents, 0, slots, 0, TOTAL_SLOTS);
        cursorStack = null;
    }

    public int getItemCount(ItemType itemType) {
        if (itemType == null) return 0;
        int total = 0;
        for (ItemStack stack : slots) {
            if (stack != null && !stack.isEmpty() && stack.getItemType() == itemType) total += stack.getCount();
        }
        return total;
    }

    public boolean canAddItem(ItemType itemType) { return canAddItem(itemType, 1); }

    public boolean canAddItem(ItemType itemType, int amount) {
        if (itemType == null || amount <= 0) return false;
        int availableSpace = 0;
        int maxStackSize = itemType.getMaxStackSize();
        for (ItemStack stack : slots) {
            if (stack == null || stack.isEmpty()) availableSpace += maxStackSize;
            else if (stack.getItemType() == itemType) availableSpace += maxStackSize - stack.getCount();
            if (availableSpace >= amount) return true;
        }
        return false;
    }

    /** All-or-nothing insertion prevents callers from duplicating partial stacks. */
    public boolean addItem(ItemType itemType, int amount) {
        if (!canAddItem(itemType, amount)) return false;
        int remaining = amount;
        int maxStackSize = itemType.getMaxStackSize();
        for (ItemStack stack : slots) {
            if (stack == null || stack.isEmpty() || stack.getItemType() != itemType || stack.getCount() >= maxStackSize) continue;
            int moved = Math.min(remaining, maxStackSize - stack.getCount());
            stack.setCount(stack.getCount() + moved);
            remaining -= moved;
            if (remaining == 0) return true;
        }
        for (int i = 0; i < slots.length; i++) {
            if (slots[i] != null && !slots[i].isEmpty()) continue;
            int moved = Math.min(remaining, maxStackSize);
            slots[i] = new ItemStack(itemType, moved);
            remaining -= moved;
            if (remaining == 0) return true;
        }
        return false;
    }

    public boolean removeItem(ItemType itemType, int amount) {
        if (itemType == null || amount <= 0 || getItemCount(itemType) < amount) return false;
        int remaining = amount;
        int selectedIndex = HOTBAR_START_INDEX + selectedHotbarSlot;
        remaining = removeFromSlot(selectedIndex, itemType, remaining);
        for (int i = HOTBAR_START_INDEX; i < TOTAL_SLOTS && remaining > 0; i++) {
            if (i != selectedIndex) remaining = removeFromSlot(i, itemType, remaining);
        }
        for (int i = 0; i < MAIN_INVENTORY_SLOTS && remaining > 0; i++) remaining = removeFromSlot(i, itemType, remaining);
        return remaining == 0;
    }

    private int removeFromSlot(int slot, ItemType itemType, int amount) {
        ItemStack stack = slots[slot];
        if (stack == null || stack.isEmpty() || stack.getItemType() != itemType || amount <= 0) return amount;
        int removed = Math.min(amount, stack.getCount());
        stack.setCount(stack.getCount() - removed);
        if (stack.isEmpty()) slots[slot] = null;
        return amount - removed;
    }

    public void returnCursorStackToInventory() {
        if (cursorStack == null || cursorStack.isEmpty()) { cursorStack = null; return; }
        if (addItem(cursorStack.getItemType(), cursorStack.getCount())) cursorStack = null;
    }

    public void clickSlot(int slot, boolean rightClick) {
        clickSlot(slot, rightClick ? GLFW.GLFW_MOUSE_BUTTON_RIGHT : GLFW.GLFW_MOUSE_BUTTON_LEFT);
    }

    public void clickSlot(int slot, int mouseButton) {
        if (slot < 0 || slot >= TOTAL_SLOTS) return;
        boolean rightClick = mouseButton == GLFW.GLFW_MOUSE_BUTTON_RIGHT;
        ItemStack target = slots[slot];
        if (target != null && target.isEmpty()) { target = null; slots[slot] = null; }

        if (cursorStack == null || cursorStack.isEmpty()) {
            if (target == null) return;
            if (rightClick) {
                int take = (target.getCount() + 1) / 2;
                cursorStack = new ItemStack(target.getItemType(), take);
                target.setCount(target.getCount() - take);
            } else {
                cursorStack = target;
                slots[slot] = null;
            }
            if (target.isEmpty()) slots[slot] = null;
            return;
        }

        if (target == null) {
            if (rightClick) {
                slots[slot] = new ItemStack(cursorStack.getItemType(), 1);
                cursorStack.setCount(cursorStack.getCount() - 1);
            } else {
                slots[slot] = cursorStack;
                cursorStack = null;
            }
            if (cursorStack != null && cursorStack.isEmpty()) cursorStack = null;
            return;
        }

        if (target.getItemType() == cursorStack.getItemType()) {
            int space = target.getItemType().getMaxStackSize() - target.getCount();
            if (space <= 0) return;
            int moved = rightClick ? 1 : Math.min(space, cursorStack.getCount());
            moved = Math.min(moved, space);
            target.setCount(target.getCount() + moved);
            cursorStack.setCount(cursorStack.getCount() - moved);
            if (cursorStack.isEmpty()) cursorStack = null;
            return;
        }

        slots[slot] = cursorStack;
        cursorStack = target;
    }
}
