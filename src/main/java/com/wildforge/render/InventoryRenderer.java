package com.wildforge.render;

import com.wildforge.core.GameSettings;
import com.wildforge.core.KeyBindings;
import com.wildforge.item.ItemStack;
import com.wildforge.item.ItemType;
import com.wildforge.player.Player;
import com.wildforge.world.BlockType;
import java.util.List;

import static com.wildforge.render.InventoryLayout.*;
import static com.wildforge.render.PixelFontRenderer.*;
import static com.wildforge.render.UiPrimitives.rect;

/** Inventory, hotbar, item icon geometry and hover tooltips. No GL ownership. */
final class InventoryRenderer {
    private final UiSkin uiSkin;

    InventoryRenderer(UiSkin uiSkin) {
        this.uiSkin = uiSkin;
    }

    void drawTooltip(
            List<Float> data,
            List<Float> tooltipSkin,
            String itemName,
            float mouseX,
            float mouseY,
            int width,
            int height) {

        float scale = 1.6f;
        float textWidth = itemName.length() * 6.0f * scale - scale;
        float padding = 8.0f;
        float boxWidth = textWidth + padding * 2.0f;
        float boxHeight = 7.0f * scale + padding * 2.0f;

        float x = mouseX + 16.0f;
        float y = mouseY + 16.0f;
        if (x + boxWidth > width - 4.0f) {
            x = mouseX - boxWidth - 12.0f;
        }
        if (y + boxHeight > height - 4.0f) {
            y = mouseY - boxHeight - 12.0f;
        }

        x = Math.max(4.0f, Math.min(x, width - boxWidth - 4.0f));
        y = Math.max(4.0f, Math.min(y, height - boxHeight - 4.0f));

        uiSkin.tooltip(tooltipSkin, x, y, boxWidth, boxHeight);

        drawText(
                data,
                itemName,
                x + boxWidth / 2.0f,
                y + padding,
                scale,
                0.94f, 0.91f, 0.82f, 1.0f);
    }


    void drawHotbar(
            List<Float> data,
            List<Float> skinData,
            List<Float> iconData,
            List<Float> countData,
            int width,
            int height,
            Player player,
            GameSettings settings) {

        int totalWidth = hotbarWidth();
        float startX = (width - totalWidth) / 2f;
        float y = height - SLOT_SIZE - 22;
        uiSkin.hotbar(skinData, startX - 11, y - 10,
                totalWidth + 22, SLOT_SIZE + 20);

        for (int i = 0; i < Player.HOTBAR_SLOTS; i++) {
            float x = startX + i * (SLOT_SIZE + SLOT_GAP);
            boolean selected = i == player.getSelectedSlot();

            drawSlot(skinData, x, y, selected);

            ItemStack stack = player.getInventoryStack(
                    Player.HOTBAR_START_INDEX + i);

            if (stack != null) {
                drawItemIcon(
                        data, iconData, x, y, stack.getItemType());
                drawNumber(countData, x, y, stack.getCount());
            }

            // Reflect remapped hotbar keys instead of hardcoding 1 through 9.
            drawHotbarKey(data, x, y, i, settings);
        }
    }

    void drawInventory(
            List<Float> data,
            List<Float> dimData,
            List<Float> skinData,
            List<Float> iconData,
            List<Float> countData,
            int width,
            int height,
            Player player,
            GameSettings settings) {

        float panelWidth = INVENTORY_PANEL_WIDTH;
        float panelHeight = INVENTORY_PANEL_HEIGHT;
        float panelX = (width - panelWidth) / 2f;
        float panelY = (height - panelHeight) / 2f;

        // One atlas for backgrounds/slots; labels and icons render above it.
        rect(dimData, 0, 0, width, height, 0f, 0f, 0f, 0.48f);
        uiSkin.panel(skinData, panelX, panelY, panelWidth, panelHeight);
        menuText(data, "INVENTORY", width / 2f, panelY + 18f,
                2.65f, 0.97f, 0.87f, 0.67f, 1f);
        uiSkin.divider(skinData, panelX + 26f, panelY + 51f, panelWidth - 52f);
        menuText(data, "STORAGE", panelX + 66f, panelY + 54f,
                1.15f, 0.73f, 0.76f, 0.75f, 1f);

        float gridWidth =
                Player.HOTBAR_SLOTS * SLOT_SIZE
                + (Player.HOTBAR_SLOTS - 1) * SLOT_GAP;

        float startX = (width - gridWidth) / 2f;
        float startY = panelY + INVENTORY_TOP_PADDING;

        // Three rows of main inventory slots.
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < Player.HOTBAR_SLOTS; col++) {
                int index = row * Player.HOTBAR_SLOTS + col;

                float x = startX + col * (SLOT_SIZE + SLOT_GAP);
                float y = startY + row * (SLOT_SIZE + SLOT_GAP);

                drawSlot(skinData, x, y, false);
                drawStack(data, iconData, countData, x, y,
                        player.getInventoryStack(index));
            }
        }

        // Divider above the hotbar row.
        float hotbarY =
                startY + 3 * (SLOT_SIZE + SLOT_GAP)
                + HOTBAR_ROW_PADDING;

        uiSkin.divider(skinData, startX, hotbarY - 10, gridWidth);
        menuText(data, "QUICK ACCESS", width / 2f, hotbarY - 25f,
                1.35f, 0.92f, 0.77f, 0.55f, 1f);

        // Hotbar slots also appear in the inventory.
        for (int col = 0; col < Player.HOTBAR_SLOTS; col++) {
            int index = Player.HOTBAR_START_INDEX + col;
            float x = startX + col * (SLOT_SIZE + SLOT_GAP);

            drawSlot(skinData, x, hotbarY,
                    col == player.getSelectedSlot());

            drawStack(data, iconData, countData, x, hotbarY,
                    player.getInventoryStack(index));

            drawHotbarKey(data, x, hotbarY, col, settings);
        }

        menuText(data, "DRAG TO MOVE ITEMS", width / 2f, panelY + panelHeight - 29f,
                1.2f, 0.70f, 0.71f, 0.69f, 0.86f);
    }

    private void drawHotbarKey(List<Float> data, float x, float y,
                               int index, GameSettings settings) {
        String key = settings == null ? Integer.toString(index + 1)
                : settings.keyBindings().keyLabel(KeyBindings.Action.at(
                        KeyBindings.Action.HOTBAR_1.ordinal() + index));
        if (key.length() > 4) key = key.substring(0, 4);
        menuText(data, key, x + 11f, y + 5f, 1.05f,
                0.86f, 0.79f, 0.64f, 0.96f);
    }

    private void drawStack(
            List<Float> data,
            List<Float> iconData,
            List<Float> countData,
            float x,
            float y,
            ItemStack stack) {

        if (stack == null) {
            return;
        }

        drawItemIcon(data, iconData, x, y, stack.getItemType());
        drawNumber(countData, x, y, stack.getCount());
    }

    private void drawSlot(
            List<Float> skinData, float x, float y, boolean selected) {
        uiSkin.slot(skinData, x, y, selected);
    }

    void drawItemIcon(
            List<Float> data,
            List<Float> iconData,
            float x,
            float y,
            ItemType itemType) {

        if (itemType == null) {
            return;
        }

        BlockType placedBlock = itemType.getPlacedBlock();
        if (placedBlock != null && placedBlock != BlockType.AIR) {
            drawTexturedBlockIcon(iconData, x, y, placedBlock);
            return;
        }

        // A simple item sprite is used for non-placeable items such as coal.
        float ix = x + 10;
        float iy = y + 13;
        rect(data, ix + 5, iy + 8, 17, 15,
                0.10f, 0.11f, 0.13f, 1f);
        rect(data, ix + 9, iy + 4, 12, 21,
                0.16f, 0.17f, 0.19f, 1f);
        rect(data, ix + 7, iy + 12, 18, 12,
                0.07f, 0.08f, 0.09f, 1f);
        rect(data, ix + 12, iy + 7, 5, 3,
                0.34f, 0.35f, 0.37f, 1f);
        rect(data, ix + 17, iy + 18, 4, 3,
                0.28f, 0.29f, 0.31f, 1f);
    }

    private void drawTexturedBlockIcon(
            List<Float> data,
            float slotX,
            float slotY,
            BlockType blockType) {

        int topLayer;
        int sideLayer;

        switch (blockType) {
            case GRASS -> {
                topLayer = BlockTextures.GRASS_TOP;
                sideLayer = BlockTextures.GRASS_SIDE;
            }
            case DIRT -> {
                topLayer = BlockTextures.DIRT;
                sideLayer = BlockTextures.DIRT;
            }
            case STONE -> {
                topLayer = BlockTextures.STONE;
                sideLayer = BlockTextures.STONE;
            }
            default -> {
                // Use the block's texture layer for other block types.
                topLayer = blockType.getTopTextureLayer();
                sideLayer = blockType.getSideTextureLayer();
            }
        }

        // A slightly taller isometric cube, seated lower in its 48x48 slot.
        // Keep a consistent silhouette for inventory, hotbar and cursor icons.
        // The top face is 16px deep and the sides are 19px tall, avoiding
        // the compressed look of the previous 30x30 projection.
        float left = slotX + 7;
        float top = slotY + 7;
        float middle = left + 17;
        float right = left + 34;

        float topQuarter = top + 8;
        float center = top + 16;
        float bottomQuarter = top + 27;
        float bottom = top + 35;

        // Top face.
        appendTexturedQuad(
                data,
                middle, top,          0.5f, 1f,
                left, topQuarter,     0f,   0.5f,
                middle, center,       0.5f, 0f,
                right, topQuarter,    1f,   0.5f,
                topLayer, 1.0f);

        // Left face: top uses v=0, bottom uses v=1.
        appendTexturedQuad(
                data,
                left, topQuarter,     0f, 0f,
                middle, center,       1f, 0f,
                middle, bottom,       1f, 1f,
                left, bottomQuarter,  0f, 1f,
                sideLayer, 0.68f);

        // Right face: top uses v=0, bottom uses v=1.
        appendTexturedQuad(
                data,
                middle, center,       0f, 0f,
                right, topQuarter,    1f, 0f,
                right, bottomQuarter, 1f, 1f,
                middle, bottom,       0f, 1f,
                sideLayer, 0.86f);
    }

    private void appendTexturedQuad(
            List<Float> data,
            float x0, float y0, float u0, float v0,
            float x1, float y1, float u1, float v1,
            float x2, float y2, float u2, float v2,
            float x3, float y3, float u3, float v3,
            int textureLayer,
            float shade) {

        appendTexturedVertex(
                data, x0, y0, u0, v0, textureLayer, shade);
        appendTexturedVertex(
                data, x1, y1, u1, v1, textureLayer, shade);
        appendTexturedVertex(
                data, x2, y2, u2, v2, textureLayer, shade);
        appendTexturedVertex(
                data, x0, y0, u0, v0, textureLayer, shade);
        appendTexturedVertex(
                data, x2, y2, u2, v2, textureLayer, shade);
        appendTexturedVertex(
                data, x3, y3, u3, v3, textureLayer, shade);
    }

    private void appendTexturedVertex(
            List<Float> data,
            float x,
            float y,
            float u,
            float v,
            int textureLayer,
            float shade) {
        data.add(x);
        data.add(y);
        data.add(u);
        data.add(v);
        data.add((float) textureLayer);
        data.add(shade);
    }


    private static int hotbarWidth() {
        return Player.HOTBAR_SLOTS * SLOT_SIZE
                + (Player.HOTBAR_SLOTS - 1) * SLOT_GAP;
    }

}
