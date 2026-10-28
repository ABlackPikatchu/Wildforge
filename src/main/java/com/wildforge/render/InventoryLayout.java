package com.wildforge.render;

import com.wildforge.player.Player;

/** Shared geometry for drawing and hit-testing inventory slots. */
final class InventoryLayout {
    private InventoryLayout() { }

    static final int SLOT_SIZE = 48;
    static final int SLOT_GAP = 5;
    static final int INVENTORY_PANEL_WIDTH = 568;
    static final int INVENTORY_PANEL_HEIGHT = 355;
    static final int INVENTORY_TOP_PADDING = 67;
    static final int HOTBAR_ROW_PADDING = 27;


    static int slotAtVirtual(
            float mouseX, float mouseY, int width, int height) {

        float panelX = (width - INVENTORY_PANEL_WIDTH) / 2f;
        float panelY = (height - INVENTORY_PANEL_HEIGHT) / 2f;

        if (mouseX < panelX
                || mouseX >= panelX + INVENTORY_PANEL_WIDTH
                || mouseY < panelY
                || mouseY >= panelY + INVENTORY_PANEL_HEIGHT) {
            return -1;
        }

        float gridWidth =
                Player.HOTBAR_SLOTS * SLOT_SIZE
                + (Player.HOTBAR_SLOTS - 1) * SLOT_GAP;
        float startX = (width - gridWidth) / 2f;
        float startY = panelY + INVENTORY_TOP_PADDING;

        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < Player.HOTBAR_SLOTS; col++) {
                float slotX = startX + col * (SLOT_SIZE + SLOT_GAP);
                float slotY = startY + row * (SLOT_SIZE + SLOT_GAP);
                if (insideSlot(mouseX, mouseY, slotX, slotY)) {
                    return row * Player.HOTBAR_SLOTS + col;
                }
            }
        }

        float hotbarY = startY
                + 3 * (SLOT_SIZE + SLOT_GAP)
                + HOTBAR_ROW_PADDING;
        for (int col = 0; col < Player.HOTBAR_SLOTS; col++) {
            float slotX = startX + col * (SLOT_SIZE + SLOT_GAP);
            if (insideSlot(mouseX, mouseY, slotX, hotbarY)) {
                return Player.HOTBAR_START_INDEX + col;
            }
        }

        return -1;
    }

    private static boolean insideSlot(
            float mouseX, float mouseY, float slotX, float slotY) {
        return mouseX >= slotX
                && mouseX < slotX + SLOT_SIZE
                && mouseY >= slotY
                && mouseY < slotY + SLOT_SIZE;
    }

}
