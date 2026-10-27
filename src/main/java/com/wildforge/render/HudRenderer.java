
package com.wildforge.render;

import com.wildforge.item.ItemStack;
import com.wildforge.item.ItemType;
import com.wildforge.player.Player;
import com.wildforge.world.BlockType;
import org.lwjgl.BufferUtils;

import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.List;

import static org.lwjgl.opengl.GL33.*;

public final class HudRenderer {
    public enum ScreenMode {
        MAIN_MENU,
        SAVE_SELECT,
        PLAYING,
        PAUSED,
        LOADING,
        SAVING
    }

    public enum MenuButton {
        PLAY,
        CONTINUE,
        OPTIONS,
        QUIT,
        SAVE_ONE,
        SAVE_TWO,
        SAVE_THREE,
        BACK,
        NONE
    }

    private int shaderProgram;
    private int vao;
    private int vbo;
    private int resolutionLocation;

    private final BlockTextures blockTextures;
    private int iconShaderProgram;
    private int iconVao;
    private int iconVbo;
    private int iconResolutionLocation;
    private int iconTextureLocation;

    private static final int SLOT_SIZE = 48;
    private static final int SLOT_GAP = 5;
    private static final int INVENTORY_PANEL_WIDTH = 540;
    private static final int INVENTORY_PANEL_HEIGHT = 330;
    private static final int INVENTORY_TOP_PADDING = 35;
    private static final int HOTBAR_ROW_PADDING = 12;

    public HudRenderer(BlockTextures blockTextures) {
        if (blockTextures == null) {
            throw new IllegalArgumentException(
                    "Block textures are required");
        }
        this.blockTextures = blockTextures;

        shaderProgram = createShaderProgram();
        iconShaderProgram = createIconShaderProgram();

        vao = glGenVertexArrays();
        vbo = glGenBuffers();

        glBindVertexArray(vao);
        glBindBuffer(GL_ARRAY_BUFFER, vbo);

        glEnableVertexAttribArray(0);
        glVertexAttribPointer(0, 2, GL_FLOAT, false,
                6 * Float.BYTES, 0L);

        glEnableVertexAttribArray(1);
        glVertexAttribPointer(1, 4, GL_FLOAT, false,
                6 * Float.BYTES, 2L * Float.BYTES);

        glBindBuffer(GL_ARRAY_BUFFER, 0);
        glBindVertexArray(0);

        resolutionLocation =
                glGetUniformLocation(shaderProgram, "uResolution");

        iconVao = glGenVertexArrays();
        iconVbo = glGenBuffers();

        glBindVertexArray(iconVao);
        glBindBuffer(GL_ARRAY_BUFFER, iconVbo);

        glEnableVertexAttribArray(0);
        glVertexAttribPointer(0, 2, GL_FLOAT, false,
                6 * Float.BYTES, 0L);

        glEnableVertexAttribArray(1);
        glVertexAttribPointer(1, 2, GL_FLOAT, false,
                6 * Float.BYTES, 2L * Float.BYTES);

        glEnableVertexAttribArray(2);
        glVertexAttribPointer(2, 1, GL_FLOAT, false,
                6 * Float.BYTES, 4L * Float.BYTES);

        glEnableVertexAttribArray(3);
        glVertexAttribPointer(3, 1, GL_FLOAT, false,
                6 * Float.BYTES, 5L * Float.BYTES);

        glBindBuffer(GL_ARRAY_BUFFER, 0);
        glBindVertexArray(0);

        iconResolutionLocation =
                glGetUniformLocation(
                        iconShaderProgram, "uResolution");
        iconTextureLocation =
                glGetUniformLocation(
                        iconShaderProgram, "uBlockTextures");
        glUseProgram(iconShaderProgram);
        glUniform1i(iconTextureLocation, 0);
        glUseProgram(0);
    }

    public void render(
            int width,
            int height,
            Player player,
            float mouseX,
            float mouseY,
            ScreenMode screenMode,
            String statusMessage,
            boolean[] saveSlotsExist) {

        if (width <= 0 || height <= 0) {
            return;
        }

        List<Float> data = new ArrayList<>();
        List<Float> iconData = new ArrayList<>();
        List<Float> countData = new ArrayList<>();
        List<Float> tooltipData = new ArrayList<>();

        if (screenMode == ScreenMode.PLAYING) {
            // Crosshair.
            rect(data, width / 2f - 1, height / 2f - 8,
                    2, 16, 1f, 1f, 1f, 0.9f);
            rect(data, width / 2f - 8, height / 2f - 1,
                    16, 2, 1f, 1f, 1f, 0.9f);

            drawHotbar(
                    data, iconData, countData, width, height, player);

            if (player.isInventoryOpen()) {
                drawInventory(
                        data, iconData, countData,
                        width, height, player);

                // Draw the held stack above the inventory and cursor.
                ItemStack cursorStack = player.getCursorStack();
                if (cursorStack != null) {
                    float x = mouseX - SLOT_SIZE / 2f;
                    float y = mouseY - SLOT_SIZE / 2f;

                    drawItemIcon(
                            data, iconData, x, y,
                            cursorStack.getItemType());
                    drawNumber(
                            countData,
                            x + SLOT_SIZE - 17,
                            y + SLOT_SIZE - 17,
                            cursorStack.getCount()
                    );
                }

                int hoveredSlot =
                        inventorySlotAt(mouseX, mouseY, width, height);
                if (hoveredSlot >= 0) {
                    ItemStack hoveredStack =
                            player.getInventoryStack(hoveredSlot);
                    if (hoveredStack != null) {
                        drawTooltip(
                                tooltipData,
                                hoveredStack.getItemType().getDisplayName(),
                                mouseX, mouseY, width, height);
                    }
                }
            }
        } else if (screenMode == ScreenMode.MAIN_MENU
                || screenMode == ScreenMode.SAVE_SELECT
                || screenMode == ScreenMode.PAUSED) {
            drawMenu(
                    data, width, height, mouseX, mouseY,
                    screenMode, statusMessage, saveSlotsExist);
        } else {
            drawStatusScreen(data, width, height, screenMode, statusMessage);
        }

        uploadAndDraw(data, width, height);
        uploadAndDrawIcons(iconData, width, height);
        uploadAndDraw(countData, width, height);
        uploadAndDraw(tooltipData, width, height);
    }

    /** Returns the inventory or hotbar slot at a framebuffer coordinate. */
    public static int inventorySlotAt(
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

    private void drawTooltip(
            List<Float> data,
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

        // Warm border and dark background match the revised inventory palette.
        rect(data, x + 2.0f, y + 2.0f,
                boxWidth, boxHeight,
                0.0f, 0.0f, 0.0f, 0.42f);
        rect(data, x, y, boxWidth, boxHeight,
                0.46f, 0.43f, 0.32f, 1.0f);
        rect(data, x + 1.0f, y + 1.0f,
                boxWidth - 2.0f, boxHeight - 2.0f,
                0.075f, 0.065f, 0.048f, 0.98f);

        drawText(
                data,
                itemName,
                x + boxWidth / 2.0f,
                y + padding,
                scale,
                0.94f, 0.91f, 0.82f, 1.0f);
    }

    public MenuButton buttonAt(
            float mouseX,
            float mouseY,
            int width,
            int height,
            ScreenMode screenMode) {

        if (screenMode != ScreenMode.MAIN_MENU
                && screenMode != ScreenMode.SAVE_SELECT
                && screenMode != ScreenMode.PAUSED) {
            return MenuButton.NONE;
        }

        for (int index = 0; index < buttonCount(screenMode); index++) {
            float[] bounds = buttonBounds(index, width, height, screenMode);
            if (mouseX >= bounds[0]
                    && mouseX < bounds[0] + bounds[2]
                    && mouseY >= bounds[1]
                    && mouseY < bounds[1] + bounds[3]) {
                return buttonForIndex(index, screenMode);
            }
        }

        return MenuButton.NONE;
    }

    private void drawMenu(
            List<Float> data,
            int width,
            int height,
            float mouseX,
            float mouseY,
            ScreenMode screenMode,
            String statusMessage,
            boolean[] saveSlotsExist) {

        rect(data, 0, 0, width, height,
                0.025f, 0.035f, 0.055f, 0.78f);

        String title = switch (screenMode) {
            case MAIN_MENU -> "WILDFORGE";
            case SAVE_SELECT -> "SELECT A SAVE";
            default -> "PAUSED";
        };
        float titleScale = 6.0f;
        drawText(
                data, title,
                width / 2.0f,
                height * 0.20f,
                titleScale,
                0.96f, 0.78f, 0.30f, 1.0f
        );

        for (int index = 0; index < buttonCount(screenMode); index++) {
            MenuButton button = buttonForIndex(index, screenMode);
            float[] bounds = buttonBounds(index, width, height, screenMode);
            boolean hovered = buttonAt(
                    mouseX, mouseY, width, height, screenMode) == button;

            float border = hovered ? 0.95f : 0.58f;
            rect(data,
                    bounds[0] - 2, bounds[1] - 2,
                    bounds[2] + 4, bounds[3] + 4,
                    border, hovered ? 0.76f : 0.62f,
                    hovered ? 0.26f : 0.70f, 1.0f);
            rect(data,
                    bounds[0], bounds[1], bounds[2], bounds[3],
                    hovered ? 0.24f : 0.12f,
                    hovered ? 0.27f : 0.16f,
                    hovered ? 0.31f : 0.21f, 0.98f);

            if (screenMode == ScreenMode.SAVE_SELECT
                    && index < 3) {
                drawText(
                        data,
                        buttonLabel(button),
                        bounds[0] + bounds[2] / 2.0f,
                        bounds[1] + 8.0f,
                        2.5f,
                        0.96f, 0.96f, 0.94f, 1.0f
                );

                boolean exists = saveSlotsExist != null
                        && index < saveSlotsExist.length
                        && saveSlotsExist[index];
                drawText(
                        data,
                        exists ? "SAVED" : "EMPTY",
                        bounds[0] + bounds[2] / 2.0f,
                        bounds[1] + 36.0f,
                        1.8f,
                        exists ? 0.65f : 0.76f,
                        exists ? 0.88f : 0.76f,
                        exists ? 0.68f : 0.76f,
                        1.0f
                );
            } else {
                drawText(
                        data,
                        buttonLabel(button),
                        bounds[0] + bounds[2] / 2.0f,
                        bounds[1] + (bounds[3] - 7 * 3.0f) / 2.0f,
                        3.0f,
                        0.96f, 0.96f, 0.94f, 1.0f
                );
            }
        }

        if (statusMessage != null && !statusMessage.isBlank()) {
            drawText(
                    data,
                    statusMessage.toUpperCase(),
                    width / 2.0f,
                    height * 0.90f,
                    2.0f,
                    1.0f, 0.55f, 0.45f, 1.0f
            );
        }


    }

    private void drawStatusScreen(
            List<Float> data,
            int width,
            int height,
            ScreenMode screenMode,
            String statusMessage) {

        rect(data, 0, 0, width, height,
                0.025f, 0.035f, 0.055f, 0.92f);

        String title = screenMode == ScreenMode.LOADING
                ? "LOADING WORLD" : "SAVING WORLD";
        drawText(
                data, title,
                width / 2.0f,
                height / 2.0f - 34.0f,
                4.0f,
                0.96f, 0.84f, 0.44f, 1.0f
        );

        if (statusMessage != null && !statusMessage.isBlank()) {
            drawText(
                    data,
                    statusMessage.toUpperCase(),
                    width / 2.0f,
                    height / 2.0f + 22.0f,
                    2.0f,
                    0.96f, 0.96f, 0.94f, 1.0f
            );
        }
    }

    private int buttonCount(ScreenMode screenMode) {
        return screenMode == ScreenMode.SAVE_SELECT ? 4 : 3;
    }

    private float[] buttonBounds(
            int index, int width, int height, ScreenMode screenMode) {

        float buttonWidth = Math.min(360.0f, Math.max(180.0f, width - 40.0f));
        float buttonHeight = screenMode == ScreenMode.SAVE_SELECT
                ? 58.0f : 56.0f;
        float gap = screenMode == ScreenMode.SAVE_SELECT ? 10.0f : 14.0f;
        float startY;

        if (screenMode == ScreenMode.MAIN_MENU) {
            startY = height * 0.48f;
        } else if (screenMode == ScreenMode.SAVE_SELECT) {
            startY = height * 0.30f;
        } else {
            startY = height * 0.39f;
        }

        return new float[] {
                (width - buttonWidth) / 2.0f,
                startY + index * (buttonHeight + gap),
                buttonWidth,
                buttonHeight
        };
    }

    private MenuButton buttonForIndex(int index, ScreenMode screenMode) {
        if (screenMode == ScreenMode.MAIN_MENU) {
            return switch (index) {
                case 0 -> MenuButton.PLAY;
                case 1 -> MenuButton.OPTIONS;
                case 2 -> MenuButton.QUIT;
                default -> MenuButton.NONE;
            };
        }

        if (screenMode == ScreenMode.SAVE_SELECT) {
            return switch (index) {
                case 0 -> MenuButton.SAVE_ONE;
                case 1 -> MenuButton.SAVE_TWO;
                case 2 -> MenuButton.SAVE_THREE;
                case 3 -> MenuButton.BACK;
                default -> MenuButton.NONE;
            };
        }

        return switch (index) {
            case 0 -> MenuButton.CONTINUE;
            case 1 -> MenuButton.OPTIONS;
            case 2 -> MenuButton.QUIT;
            default -> MenuButton.NONE;
        };
    }

    private String buttonLabel(MenuButton button) {
        return switch (button) {
            case PLAY -> "PLAY";
            case CONTINUE -> "CONTINUE";
            case OPTIONS -> "OPTIONS";
            case QUIT -> "QUIT";
            case SAVE_ONE -> "WORLD ONE";
            case SAVE_TWO -> "WORLD TWO";
            case SAVE_THREE -> "WORLD THREE";
            case BACK -> "BACK";
            case NONE -> "";
        };
    }

    private void drawText(
            List<Float> data,
            String text,
            float centerX,
            float topY,
            float scale,
            float red,
            float green,
            float blue,
            float alpha) {

        float textWidth = text.length() * 6.0f * scale - scale;
        float startX = centerX - textWidth / 2.0f;

        for (int index = 0; index < text.length(); index++) {
            String[] rows = glyph(Character.toUpperCase(text.charAt(index)));
            float charX = startX + index * 6.0f * scale;

            for (int row = 0; row < rows.length; row++) {
                for (int column = 0; column < rows[row].length(); column++) {
                    if (rows[row].charAt(column) == '1') {
                        rect(data,
                                charX + column * scale,
                                topY + row * scale,
                                scale, scale,
                                red, green, blue, alpha);
                    }
                }
            }
        }
    }

    private String[] glyph(char character) {
        return switch (character) {
            case 'A' -> new String[] {"01110", "10001", "10001", "11111", "10001", "10001", "10001"};
            case 'B' -> new String[] {"11110", "10001", "10001", "11110", "10001", "10001", "11110"};
            case 'C' -> new String[] {"01111", "10000", "10000", "10000", "10000", "10000", "01111"};
            case 'D' -> new String[] {"11110", "10001", "10001", "10001", "10001", "10001", "11110"};
            case 'E' -> new String[] {"11111", "10000", "10000", "11110", "10000", "10000", "11111"};
            case 'F' -> new String[] {"11111", "10000", "10000", "11110", "10000", "10000", "10000"};
            case 'G' -> new String[] {"01111", "10000", "10000", "10111", "10001", "10001", "01110"};
            case 'H' -> new String[] {"10001", "10001", "10001", "11111", "10001", "10001", "10001"};
            case 'I' -> new String[] {"11111", "00100", "00100", "00100", "00100", "00100", "11111"};
            case 'J' -> new String[] {"00111", "00010", "00010", "00010", "10010", "10010", "01100"};
            case 'K' -> new String[] {"10001", "10010", "10100", "11000", "10100", "10010", "10001"};
            case 'L' -> new String[] {"10000", "10000", "10000", "10000", "10000", "10000", "11111"};
            case 'M' -> new String[] {"10001", "11011", "10101", "10101", "10001", "10001", "10001"};
            case 'N' -> new String[] {"10001", "11001", "11001", "10101", "10011", "10011", "10001"};
            case 'O' -> new String[] {"01110", "10001", "10001", "10001", "10001", "10001", "01110"};
            case 'P' -> new String[] {"11110", "10001", "10001", "11110", "10000", "10000", "10000"};
            case 'Q' -> new String[] {"01110", "10001", "10001", "10001", "10101", "10010", "01101"};
            case 'R' -> new String[] {"11110", "10001", "10001", "11110", "10100", "10010", "10001"};
            case 'S' -> new String[] {"01111", "10000", "10000", "01110", "00001", "00001", "11110"};
            case 'T' -> new String[] {"11111", "00100", "00100", "00100", "00100", "00100", "00100"};
            case 'U' -> new String[] {"10001", "10001", "10001", "10001", "10001", "10001", "01110"};
            case 'V' -> new String[] {"10001", "10001", "10001", "10001", "10001", "01010", "00100"};
            case 'W' -> new String[] {"10001", "10001", "10001", "10101", "10101", "10101", "01010"};
            case 'X' -> new String[] {"10001", "10001", "01010", "00100", "01010", "10001", "10001"};
            case 'Y' -> new String[] {"10001", "10001", "01010", "00100", "00100", "00100", "00100"};
            case 'Z' -> new String[] {"11111", "00001", "00010", "00100", "01000", "10000", "11111"};
            default -> new String[] {"00000", "00000", "00000", "00000", "00000", "00000", "00000"};
        };
    }

    private void drawHotbar(
            List<Float> data,
            List<Float> iconData,
            List<Float> countData,
            int width,
            int height,
            Player player) {

        int totalWidth = hotbarWidth();
        float startX = (width - totalWidth) / 2f;
        float y = height - SLOT_SIZE - 24;

        for (int i = 0; i < Player.HOTBAR_SLOTS; i++) {
            float x = startX + i * (SLOT_SIZE + SLOT_GAP);
            boolean selected = i == player.getSelectedSlot();

            drawSlot(data, x, y, selected);

            ItemStack stack = player.getInventoryStack(
                    Player.HOTBAR_START_INDEX + i);

            if (stack != null) {
                drawItemIcon(
                        data, iconData, x, y, stack.getItemType());
                drawNumber(countData, x + SLOT_SIZE - 17,
                        y + SLOT_SIZE - 17, stack.getCount());
            }

            // Hotbar key label.
            drawDigit(data, x + 5, y + 5,
                    i + 1, 0.9f, 0.9f, 0.9f);
        }
    }

    private void drawInventory(
            List<Float> data,
            List<Float> iconData,
            List<Float> countData,
            int width,
            int height,
            Player player) {

        float panelWidth = INVENTORY_PANEL_WIDTH;
        float panelHeight = INVENTORY_PANEL_HEIGHT;
        float panelX = (width - panelWidth) / 2f;
        float panelY = (height - panelHeight) / 2f;

        // Dim the world behind the inventory.
        rect(data, 0, 0, width, height,
                0f, 0f, 0f, 0.58f);

        // Panel and inner background.
        rect(data, panelX, panelY, panelWidth, panelHeight,
                0.11f, 0.10f, 0.08f, 0.98f);
        rect(data, panelX + 4, panelY + 4,
                panelWidth - 8, panelHeight - 8,
                0.19f, 0.17f, 0.13f, 0.98f);

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

                drawSlot(data, x, y, false);
                drawStack(data, iconData, countData, x, y,
                        player.getInventoryStack(index));
            }
        }

        // Divider above the hotbar row.
        float hotbarY =
                startY + 3 * (SLOT_SIZE + SLOT_GAP)
                + HOTBAR_ROW_PADDING;

        rect(data, startX, hotbarY - 7,
                gridWidth, 2, 0.43f, 0.39f, 0.29f, 0.95f);

        // Hotbar slots also appear in the inventory.
        for (int col = 0; col < Player.HOTBAR_SLOTS; col++) {
            int index = Player.HOTBAR_START_INDEX + col;
            float x = startX + col * (SLOT_SIZE + SLOT_GAP);

            drawSlot(data, x, hotbarY,
                    col == player.getSelectedSlot());

            drawStack(data, iconData, countData, x, hotbarY,
                    player.getInventoryStack(index));

            drawDigit(data, x + 5, hotbarY + 5,
                    col + 1, 0.9f, 0.9f, 0.9f);
        }
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
        drawNumber(countData, x + SLOT_SIZE - 17,
                y + SLOT_SIZE - 17, stack.getCount());
    }

    private void drawSlot(
            List<Float> data, float x, float y, boolean selected) {

        if (selected) {
            rect(data, x - 2, y - 2,
                    SLOT_SIZE + 4, SLOT_SIZE + 4,
                    0.57f, 0.68f, 0.42f, 1f);
        }

        rect(data, x, y, SLOT_SIZE, SLOT_SIZE,
                0.045f, 0.040f, 0.030f, 1f);

        rect(data, x + 2, y + 2,
                SLOT_SIZE - 4, SLOT_SIZE - 4,
                0.19f, 0.17f, 0.13f, 1f);

        // Inner highlight.
        rect(data, x + 3, y + 3,
                SLOT_SIZE - 6, 1,
                0.34f, 0.30f, 0.23f, 0.85f);
    }

    private void drawItemIcon(
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
        float iy = y + 10;
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
                return;
            }
        }

        float left = slotX + 12;
        float top = slotY + 7;
        float middle = left + 12;
        float right = left + 24;
        float topQuarter = top + 6;
        float center = top + 12;
        float bottomQuarter = top + 18;
        float bottom = top + 24;

        // Top face of a small isometric cube.
        appendTexturedQuad(
                data,
                middle, top, 0.5f, 1f,
                left, topQuarter, 0f, 0.5f,
                middle, center, 0.5f, 0f,
                right, topQuarter, 1f, 0.5f,
                topLayer, 1.0f);

        // Left face.
        appendTexturedQuad(
                data,
                left, topQuarter, 0f, 1f,
                middle, center, 1f, 1f,
                middle, bottom, 1f, 0f,
                left, bottomQuarter, 0f, 0f,
                sideLayer, 0.68f);

        // Right face.
        appendTexturedQuad(
                data,
                middle, center, 0f, 1f,
                right, topQuarter, 1f, 1f,
                right, bottomQuarter, 1f, 0f,
                middle, bottom, 0f, 0f,
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

    private void drawNumber(
            List<Float> data, float x, float y, int number) {

        String text = Integer.toString(number);
        float startX = x - (text.length() - 1) * 7;

        // Dark offset makes the count readable over textured cube faces.
        for (int i = 0; i < text.length(); i++) {
            drawDigit(data, startX + i * 7 + 1, y + 1,
                    text.charAt(i) - '0',
                    0.04f, 0.035f, 0.025f);
        }

        for (int i = 0; i < text.length(); i++) {
            drawDigit(data, startX + i * 7, y,
                    text.charAt(i) - '0',
                    1f, 1f, 1f);
        }
    }

    private void drawDigit(
            List<Float> data, float x, float y, int digit,
            float r, float g, float b) {

        if (digit < 0 || digit > 9) {
            return;
        }

        // Seven-segment digit patterns.
        boolean[][] segments = {
                {true, true, true, true, true, true, false},
                {false, true, true, false, false, false, false},
                {true, true, false, true, true, false, true},
                {true, true, true, true, false, false, true},
                {false, true, true, false, false, true, true},
                {true, false, true, true, false, true, true},
                {true, false, true, true, true, true, true},
                {true, true, true, false, false, false, false},
                {true, true, true, true, true, true, true},
                {true, true, true, true, false, true, true}
        };

        float w = 5;
        float h = 2;
        float mid = 5;
        float bottom = 10;

        // Top, upper-right, lower-right, bottom,
        // lower-left, upper-left, middle.
        float[][] positions = {
                {x, y, w, h},
                {x + 4, y + 1, h, 4},
                {x + 4, y + 5, h, 4},
                {x, y + bottom, w, h},
                {x - 1, y + 5, h, 4},
                {x - 1, y + 1, h, 4},
                {x, y + mid, w, h}
        };

        for (int i = 0; i < 7; i++) {
            if (segments[digit][i]) {
                float[] p = positions[i];
                rect(data, p[0], p[1], p[2], p[3],
                        r, g, b, 1f);
            }
        }
    }

    private void rect(
            List<Float> data, float x, float y,
            float width, float height,
            float r, float g, float b, float a) {

        vertex(data, x, y, r, g, b, a);
        vertex(data, x + width, y, r, g, b, a);
        vertex(data, x + width, y + height, r, g, b, a);

        vertex(data, x, y, r, g, b, a);
        vertex(data, x + width, y + height, r, g, b, a);
        vertex(data, x, y + height, r, g, b, a);
    }

    private void vertex(
            List<Float> data, float x, float y,
            float r, float g, float b, float a) {

        data.add(x);
        data.add(y);
        data.add(r);
        data.add(g);
        data.add(b);
        data.add(a);
    }

    private void uploadAndDraw(
            List<Float> data, int width, int height) {

        if (data.isEmpty()) {
            return;
        }

        FloatBuffer buffer =
                BufferUtils.createFloatBuffer(data.size());

        for (float value : data) {
            buffer.put(value);
        }
        buffer.flip();

        // Preserve the OpenGL state used by the 3D renderer.
        boolean depthWasEnabled = glIsEnabled(GL_DEPTH_TEST);
        boolean cullWasEnabled = glIsEnabled(GL_CULL_FACE);
        boolean blendWasEnabled = glIsEnabled(GL_BLEND);

        // HUD elements are 2D and must not be culled.
        glDisable(GL_DEPTH_TEST);
        glDisable(GL_CULL_FACE);
        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);

        glUseProgram(shaderProgram);
        glUniform2f(resolutionLocation, width, height);

        glBindVertexArray(vao);
        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        glBufferData(GL_ARRAY_BUFFER, buffer, GL_DYNAMIC_DRAW);

        glDrawArrays(GL_TRIANGLES, 0, data.size() / 6);

        glBindBuffer(GL_ARRAY_BUFFER, 0);
        glBindVertexArray(0);
        glUseProgram(0);

        // Restore the original state.
        if (blendWasEnabled) {
            glEnable(GL_BLEND);
        } else {
            glDisable(GL_BLEND);
        }

        if (depthWasEnabled) {
            glEnable(GL_DEPTH_TEST);
        } else {
            glDisable(GL_DEPTH_TEST);
        }

        if (cullWasEnabled) {
            glEnable(GL_CULL_FACE);
        } else {
            glDisable(GL_CULL_FACE);
        }
    }

    private void uploadAndDrawIcons(
            List<Float> data, int width, int height) {

        if (data.isEmpty()) {
            return;
        }

        FloatBuffer buffer =
                BufferUtils.createFloatBuffer(data.size());
        for (float value : data) {
            buffer.put(value);
        }
        buffer.flip();

        boolean depthWasEnabled = glIsEnabled(GL_DEPTH_TEST);
        boolean cullWasEnabled = glIsEnabled(GL_CULL_FACE);
        boolean blendWasEnabled = glIsEnabled(GL_BLEND);

        glDisable(GL_DEPTH_TEST);
        glDisable(GL_CULL_FACE);
        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);

        glUseProgram(iconShaderProgram);
        glUniform2f(iconResolutionLocation, width, height);

        glActiveTexture(GL_TEXTURE0);
        blockTextures.bind(0);

        glBindVertexArray(iconVao);
        glBindBuffer(GL_ARRAY_BUFFER, iconVbo);
        glBufferData(GL_ARRAY_BUFFER, buffer, GL_DYNAMIC_DRAW);
        glDrawArrays(GL_TRIANGLES, 0, data.size() / 6);

        glBindBuffer(GL_ARRAY_BUFFER, 0);
        glBindVertexArray(0);
        glUseProgram(0);
        glBindTexture(GL_TEXTURE_2D_ARRAY, 0);

        if (blendWasEnabled) {
            glEnable(GL_BLEND);
        } else {
            glDisable(GL_BLEND);
        }
        if (depthWasEnabled) {
            glEnable(GL_DEPTH_TEST);
        } else {
            glDisable(GL_DEPTH_TEST);
        }
        if (cullWasEnabled) {
            glEnable(GL_CULL_FACE);
        } else {
            glDisable(GL_CULL_FACE);
        }
    }

    private int createShaderProgram() {
        String vertexSource = """
                #version 330 core
                layout (location = 0) in vec2 aPosition;
                layout (location = 1) in vec4 aColor;

                uniform vec2 uResolution;
                out vec4 vColor;

                void main() {
                    vec2 position = vec2(
                        (aPosition.x / uResolution.x) * 2.0 - 1.0,
                        1.0 - (aPosition.y / uResolution.y) * 2.0
                    );
                    gl_Position = vec4(position, 0.0, 1.0);
                    vColor = aColor;
                }
                """;

        String fragmentSource = """
                #version 330 core
                in vec4 vColor;
                out vec4 FragColor;

                void main() {
                    FragColor = vColor;
                }
                """;

        int vertexShader = glCreateShader(GL_VERTEX_SHADER);
        glShaderSource(vertexShader, vertexSource);
        glCompileShader(vertexShader);

        if (glGetShaderi(vertexShader, GL_COMPILE_STATUS) == GL_FALSE) {
            String log = glGetShaderInfoLog(vertexShader);
            glDeleteShader(vertexShader);
            throw new IllegalStateException(
                    "HUD vertex shader failed: " + log);
        }

        int fragmentShader = glCreateShader(GL_FRAGMENT_SHADER);
        glShaderSource(fragmentShader, fragmentSource);
        glCompileShader(fragmentShader);

        if (glGetShaderi(fragmentShader, GL_COMPILE_STATUS)
                == GL_FALSE) {
            String log = glGetShaderInfoLog(fragmentShader);
            glDeleteShader(vertexShader);
            glDeleteShader(fragmentShader);
            throw new IllegalStateException(
                    "HUD fragment shader failed: " + log);
        }

        int program = glCreateProgram();
        glAttachShader(program, vertexShader);
        glAttachShader(program, fragmentShader);
        glLinkProgram(program);

        glDeleteShader(vertexShader);
        glDeleteShader(fragmentShader);

        if (glGetProgrami(program, GL_LINK_STATUS) == GL_FALSE) {
            String log = glGetProgramInfoLog(program);
            glDeleteProgram(program);
            throw new IllegalStateException(
                    "HUD shader linking failed: " + log);
        }

        return program;
    }

    private int createIconShaderProgram() {
        String vertexSource = """
                #version 330 core
                layout (location = 0) in vec2 aPosition;
                layout (location = 1) in vec2 aTexCoord;
                layout (location = 2) in float aTextureLayer;
                layout (location = 3) in float aShade;

                uniform vec2 uResolution;
                out vec3 vTexCoord;
                out float vShade;

                void main() {
                    vec2 position = vec2(
                        (aPosition.x / uResolution.x) * 2.0 - 1.0,
                        1.0 - (aPosition.y / uResolution.y) * 2.0
                    );
                    gl_Position = vec4(position, 0.0, 1.0);
                    vTexCoord = vec3(aTexCoord, aTextureLayer);
                    vShade = aShade;
                }
                """;

        String fragmentSource = """
                #version 330 core
                in vec3 vTexCoord;
                in float vShade;
                uniform sampler2DArray uBlockTextures;
                out vec4 FragColor;

                void main() {
                    vec4 texel = texture(uBlockTextures, vTexCoord);
                    FragColor = vec4(texel.rgb * vShade, texel.a);
                }
                """;

        int vertexShader = glCreateShader(GL_VERTEX_SHADER);
        glShaderSource(vertexShader, vertexSource);
        glCompileShader(vertexShader);
        if (glGetShaderi(vertexShader, GL_COMPILE_STATUS) == GL_FALSE) {
            String log = glGetShaderInfoLog(vertexShader);
            glDeleteShader(vertexShader);
            throw new IllegalStateException(
                    "HUD icon vertex shader failed: " + log);
        }

        int fragmentShader = glCreateShader(GL_FRAGMENT_SHADER);
        glShaderSource(fragmentShader, fragmentSource);
        glCompileShader(fragmentShader);
        if (glGetShaderi(fragmentShader, GL_COMPILE_STATUS)
                == GL_FALSE) {
            String log = glGetShaderInfoLog(fragmentShader);
            glDeleteShader(vertexShader);
            glDeleteShader(fragmentShader);
            throw new IllegalStateException(
                    "HUD icon fragment shader failed: " + log);
        }

        int program = glCreateProgram();
        glAttachShader(program, vertexShader);
        glAttachShader(program, fragmentShader);
        glLinkProgram(program);
        glDeleteShader(vertexShader);
        glDeleteShader(fragmentShader);

        if (glGetProgrami(program, GL_LINK_STATUS) == GL_FALSE) {
            String log = glGetProgramInfoLog(program);
            glDeleteProgram(program);
            throw new IllegalStateException(
                    "HUD icon shader linking failed: " + log);
        }

        return program;
    }

    private static int hotbarWidth() {
        return Player.HOTBAR_SLOTS * SLOT_SIZE
                + (Player.HOTBAR_SLOTS - 1) * SLOT_GAP;
    }

    public void close() {
        if (vbo != 0) {
            glDeleteBuffers(vbo);
        }
        if (vao != 0) {
            glDeleteVertexArrays(vao);
        }
        if (shaderProgram != 0) {
            glDeleteProgram(shaderProgram);
        }
        if (iconVbo != 0) {
            glDeleteBuffers(iconVbo);
        }
        if (iconVao != 0) {
            glDeleteVertexArrays(iconVao);
        }
        if (iconShaderProgram != 0) {
            glDeleteProgram(iconShaderProgram);
        }
    }
}
