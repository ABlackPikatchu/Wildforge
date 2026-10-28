
package com.wildforge.render;

import com.wildforge.core.GameSettings;
import com.wildforge.core.KeyBindings;
import com.wildforge.item.ItemStack;
import com.wildforge.item.ItemType;
import com.wildforge.player.Player;
import com.wildforge.world.BlockType;
import org.lwjgl.BufferUtils;

import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.List;

import static org.lwjgl.opengl.GL11.GL_BLEND;
import static org.lwjgl.opengl.GL11.GL_CULL_FACE;
import static org.lwjgl.opengl.GL11.GL_DEPTH_TEST;
import static org.lwjgl.opengl.GL11.GL_FALSE;
import static org.lwjgl.opengl.GL11.GL_FLOAT;
import static org.lwjgl.opengl.GL11.GL_ONE_MINUS_SRC_ALPHA;
import static org.lwjgl.opengl.GL11.GL_SRC_ALPHA;
import static org.lwjgl.opengl.GL11.GL_TRIANGLES;
import static org.lwjgl.opengl.GL11.glBindTexture;
import static org.lwjgl.opengl.GL11.glBlendFunc;
import static org.lwjgl.opengl.GL11.glDisable;
import static org.lwjgl.opengl.GL11.glDrawArrays;
import static org.lwjgl.opengl.GL11.glEnable;
import static org.lwjgl.opengl.GL11.glIsEnabled;
import static org.lwjgl.opengl.GL13.GL_TEXTURE0;
import static org.lwjgl.opengl.GL13.glActiveTexture;
import static org.lwjgl.opengl.GL15.GL_ARRAY_BUFFER;
import static org.lwjgl.opengl.GL15.GL_DYNAMIC_DRAW;
import static org.lwjgl.opengl.GL15.glBindBuffer;
import static org.lwjgl.opengl.GL15.glBufferData;
import static org.lwjgl.opengl.GL15.glDeleteBuffers;
import static org.lwjgl.opengl.GL15.glGenBuffers;
import static org.lwjgl.opengl.GL20.GL_COMPILE_STATUS;
import static org.lwjgl.opengl.GL20.GL_FRAGMENT_SHADER;
import static org.lwjgl.opengl.GL20.GL_LINK_STATUS;
import static org.lwjgl.opengl.GL20.GL_VERTEX_SHADER;
import static org.lwjgl.opengl.GL20.glAttachShader;
import static org.lwjgl.opengl.GL20.glCompileShader;
import static org.lwjgl.opengl.GL20.glCreateProgram;
import static org.lwjgl.opengl.GL20.glCreateShader;
import static org.lwjgl.opengl.GL20.glDeleteProgram;
import static org.lwjgl.opengl.GL20.glDeleteShader;
import static org.lwjgl.opengl.GL20.glEnableVertexAttribArray;
import static org.lwjgl.opengl.GL20.glGetProgramInfoLog;
import static org.lwjgl.opengl.GL20.glGetProgrami;
import static org.lwjgl.opengl.GL20.glGetShaderInfoLog;
import static org.lwjgl.opengl.GL20.glGetShaderi;
import static org.lwjgl.opengl.GL20.glGetUniformLocation;
import static org.lwjgl.opengl.GL20.glLinkProgram;
import static org.lwjgl.opengl.GL20.glShaderSource;
import static org.lwjgl.opengl.GL20.glUniform1i;
import static org.lwjgl.opengl.GL20.glUniform2f;
import static org.lwjgl.opengl.GL20.glUseProgram;
import static org.lwjgl.opengl.GL20.glVertexAttribPointer;
import static org.lwjgl.opengl.GL30.GL_TEXTURE_2D_ARRAY;
import static org.lwjgl.opengl.GL30.glBindVertexArray;
import static org.lwjgl.opengl.GL30.glDeleteVertexArrays;
import static org.lwjgl.opengl.GL30.glGenVertexArrays;

public final class HudRenderer {
    public enum ScreenMode {
        MAIN_MENU,
        SAVE_SELECT,
        OPTIONS,
        KEYBINDS,
        DELETE_CONFIRM,
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
        NEW_GAME,
        PREVIOUS_PAGE,
        NEXT_PAGE,
        DELETE_ONE,
        DELETE_TWO,
        DELETE_THREE,
        CONFIRM_DELETE,
        CANCEL_DELETE,
        VIDEO_TAB,
        CONTROLS_TAB,
        DISPLAY_MODE,
        RESOLUTION,
        VSYNC,
        FPS_LIMIT,
        FOV,
        SHOW_FPS,
        CROSSHAIR,
        UI_SCALE,
        KEYBINDS_TAB,
        BIND_1,
        BIND_2,
        BIND_3,
        BIND_4,
        BIND_5,
        BIND_6,
        BIND_PREVIOUS,
        BIND_NEXT,
        RESET_BINDS,
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
    private boolean showCrosshair = true;
    private boolean showFps;
    private int measuredFps;
    // One global scale for menus, dialogs, inventory, icons, text and the HUD.
    private static float interfaceScale = 1.25f;

    public void setUiScale(float scale) {
        interfaceScale = Math.max(0.75f, Math.min(2f, scale));
    }

    /**
     * Make the interface responsive, keeping inventory accessible on small screens.
     * Menu panels have their own proportional fit rules in virtual coordinates.
     */
    private static float effectiveUiScale(int width, int height) {
        return Math.min(interfaceScale, Math.min(width / 610f, height / 400f));
    }

    private static int virtualSize(int physicalSize, float scale) {
        return Math.round(physicalSize / scale);
    }

    public void setShowCrosshair(boolean enabled) {
        showCrosshair = enabled;
    }

    public void setShowFps(boolean enabled) {
        showFps = enabled;
    }

    public void setMeasuredFps(int fps) {
        measuredFps = Math.max(0, fps);
    }

    private static final int SLOT_SIZE = 48;
    private static final int SLOT_GAP = 5;
    private static final int INVENTORY_PANEL_WIDTH = 568;
    private static final int INVENTORY_PANEL_HEIGHT = 355;
    private static final int INVENTORY_TOP_PADDING = 67;
    private static final int HOTBAR_ROW_PADDING = 27;

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
            String[] visibleSaveLabels,
            boolean[] visibleSaveExists,
            int savePage,
            boolean hasPreviousSavePage,
            boolean hasNextSavePage,
            GameSettings settings,
            String deleteWorldLabel,
            int optionsPage) {

        if (width <= 0 || height <= 0) {
            return;
        }

        // Every UI screen uses the same virtual coordinate transform. The title
        // background is drawn separately at the native framebuffer resolution.
        float scale = effectiveUiScale(width, height);
        width = virtualSize(width, scale);
        height = virtualSize(height, scale);
        mouseX /= scale;
        mouseY /= scale;

        List<Float> data = new ArrayList<>();
        List<Float> iconData = new ArrayList<>();
        List<Float> countData = new ArrayList<>();
        List<Float> tooltipData = new ArrayList<>();

        if (screenMode == ScreenMode.PLAYING) {
            // Crosshair can be hidden through Options.
            if (showCrosshair) {
                rect(data, width / 2f - 1, height / 2f - 8,
                        2, 16, 1f, 1f, 1f, 0.9f);
                rect(data, width / 2f - 8, height / 2f - 1,
                        16, 2, 1f, 1f, 1f, 0.9f);
            }

            drawHotbar(
                    data, iconData, countData, width, height, player, settings);

            if (showFps) {
                rect(data, 10f, 10f, 132f, 37f, 0.04f, 0.05f, 0.06f, 0.68f);
                menuText(data, "FPS " + measuredFps, 76f, 19f, 2.05f,
                        0.96f, 0.86f, 0.66f, 1f);
            }

            if (player.isInventoryOpen()) {
                drawInventory(
                        data, iconData, countData,
                        width, height, player, settings);

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
                        inventorySlotAtVirtual(mouseX, mouseY, width, height);
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
                || screenMode == ScreenMode.OPTIONS
                || screenMode == ScreenMode.KEYBINDS
                || screenMode == ScreenMode.DELETE_CONFIRM
                || screenMode == ScreenMode.PAUSED) {
            drawMenu(
                    data, width, height, mouseX, mouseY,
                    screenMode, statusMessage,
                    visibleSaveLabels, visibleSaveExists,
                    savePage, hasPreviousSavePage, hasNextSavePage,
                    settings, deleteWorldLabel, optionsPage);
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
        float scale = effectiveUiScale(width, height);
        return inventorySlotAtVirtual(mouseX / scale, mouseY / scale,
                virtualSize(width, scale), virtualSize(height, scale));
    }

    private static int inventorySlotAtVirtual(
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
        return buttonAt(mouseX, mouseY, width, height, screenMode, 0);
    }

    public MenuButton buttonAt(
            float mouseX,
            float mouseY,
            int width,
            int height,
            ScreenMode screenMode,
            int optionsPage) {

        if (screenMode != ScreenMode.MAIN_MENU
                && screenMode != ScreenMode.SAVE_SELECT
                && screenMode != ScreenMode.OPTIONS
                && screenMode != ScreenMode.DELETE_CONFIRM
                && screenMode != ScreenMode.PAUSED
                && screenMode != ScreenMode.KEYBINDS) {
            return MenuButton.NONE;
        }

        float scale = effectiveUiScale(width, height);
        mouseX /= scale;
        mouseY /= scale;
        width = virtualSize(width, scale);
        height = virtualSize(height, scale);
        return buttonAtVirtual(mouseX, mouseY, width, height, screenMode, optionsPage);
    }

    private MenuButton buttonAtVirtual(float mouseX, float mouseY,
                                       int width, int height,
                                       ScreenMode screenMode, int optionsPage) {
        // Delete buttons are inset inside save rows; test those first.
        for (int index = buttonCount(screenMode, optionsPage) - 1; index >= 0; index--) {
            float[] bounds = buttonBounds(index, width, height, screenMode, optionsPage);
            if (mouseX >= bounds[0]
                    && mouseX < bounds[0] + bounds[2]
                    && mouseY >= bounds[1]
                    && mouseY < bounds[1] + bounds[3]) {
                return buttonForIndex(index, screenMode, optionsPage);
            }
        }

        return MenuButton.NONE;
    }

    /** Convert physical framebuffer coordinates to the virtual FOV slider. */
    public int fovSliderValueAt(float mouseX, float mouseY,
                                int width, int height, int optionsPage) {
        if (optionsPage != 0) return -1;
        float scale = effectiveUiScale(width, height);
        mouseX /= scale;
        mouseY /= scale;
        width = virtualSize(width, scale);
        height = virtualSize(height, scale);
        float[] row = buttonBounds(6, width, height, ScreenMode.OPTIONS, optionsPage);
        float[] track = fovSliderBounds(row, optionsMenuScale(width, height));
        float margin = Math.max(6f * optionsMenuScale(width, height), 4f);
        if (mouseX < track[0] - margin || mouseX > track[0] + track[2] + margin
                || mouseY < row[1] || mouseY > row[1] + row[3]) return -1;
        return sliderValue(mouseX, track, GameSettings.MIN_FOV, GameSettings.MAX_FOV, 1);
    }

    public int fovSliderValueClamped(float mouseX, int width, int height, int optionsPage) {
        if (optionsPage != 0) return -1;
        float scale = effectiveUiScale(width, height);
        mouseX /= scale;
        width = virtualSize(width, scale);
        height = virtualSize(height, scale);
        float[] row = buttonBounds(6, width, height, ScreenMode.OPTIONS, optionsPage);
        float[] track = fovSliderBounds(row, optionsMenuScale(width, height));
        return sliderValue(mouseX, track, GameSettings.MIN_FOV, GameSettings.MAX_FOV, 1);
    }

    /**
     * Return an anchored, physical-pixel slider track for drag gestures. The
     * scale changes as the user drags, so the gesture must retain the original
     * geometry rather than jumping when the menu itself resizes.
     */
    public float[] uiScaleSliderTrack(int width, int height, int optionsPage) {
        if (optionsPage != 0 || width <= 0 || height <= 0) return null;
        float scale = effectiveUiScale(width, height);
        int virtualWidth = virtualSize(width, scale);
        int virtualHeight = virtualSize(height, scale);
        float[] row = buttonBounds(8, virtualWidth, virtualHeight,
                ScreenMode.OPTIONS, optionsPage);
        float[] virtualTrack = fovSliderBounds(row,
                optionsMenuScale(virtualWidth, virtualHeight));
        return new float[] {
                virtualTrack[0] * scale,
                virtualTrack[1] * scale,
                virtualTrack[2] * scale,
                virtualTrack[3] * scale
        };
    }

    private static int sliderValue(float cursorX, float[] track,
                                   int minimum, int maximum, int step) {
        float ratio = Math.max(0f, Math.min(1f,
                (cursorX - track[0]) / track[2]));
        int value = minimum + Math.round(ratio * (maximum - minimum) / step) * step;
        return Math.max(minimum, Math.min(maximum, value));
    }

    private float[] fovSliderBounds(float[] row, float scale) {
        return new float[] {row[0] + 258f * scale, row[1] + 29f * scale,
                row[2] - 355f * scale, 10f * scale};
    }

    /**
     * The artwork contains the Wildforge logo, so the main menu must not draw
     * another title over it. Buttons sit in a low-profile footer instead.
     */
    private void drawMenu(
            List<Float> data,
            int width,
            int height,
            float mouseX,
            float mouseY,
            ScreenMode screenMode,
            String statusMessage,
            String[] visibleSaveLabels,
            boolean[] visibleSaveExists,
            int savePage,
            boolean hasPreviousSavePage,
            boolean hasNextSavePage,
            GameSettings settings,
            String deleteWorldLabel,
            int optionsPage) {

        if (screenMode == ScreenMode.MAIN_MENU) {
            // Keep the sunset and built-in logo bright. Darken only the footer
            // behind our controls rather than dimming the entire artwork.
            rect(data, 0, height * 0.76f, width, height * 0.24f,
                    0.025f, 0.033f, 0.047f, 0.22f);
            rect(data, 0, height * 0.84f, width, height * 0.16f,
                    0.025f, 0.033f, 0.047f, 0.38f);
            float s = mainMenuScale(width, height);
            menuText(data, "EXPLORE  BUILD  FORGE", width * 0.5f,
                    height - 26f * s, 1.65f * s, 0.94f, 0.79f, 0.52f, 0.9f);
        } else {
            rect(data, 0, 0, width, height,
                    0.018f, 0.028f, 0.035f,
                    screenMode == ScreenMode.SAVE_SELECT ? 0.40f : 0.56f);

            if (screenMode == ScreenMode.SAVE_SELECT) {
                float s = saveMenuScale(width, height);
                float panelWidth = 570f * s;
                float panelHeight = 645f * s;
                float panelX = (width - panelWidth) / 2f;
                float panelY = (height - panelHeight) / 2f;
                drawForgePanel(data, panelX, panelY, panelWidth, panelHeight, s);
                menuText(data, "SELECT WORLD", width * 0.5f,
                        panelY + 39f * s, 3.65f * s,
                        0.98f, 0.83f, 0.55f, 1f);
                menuText(data, "CHOOSE A SAVE OR START FRESH", width * 0.5f,
                        panelY + 81f * s, 1.65f * s,
                        0.76f, 0.76f, 0.72f, 1f);
                rect(data, panelX + 36f * s, panelY + 103f * s,
                        panelWidth - 72f * s, 2f * s,
                        0.63f, 0.39f, 0.17f, 0.85f);
                menuText(data, "PAGE " + (savePage + 1), width * 0.5f,
                        panelY + 524f * s, 1.65f * s,
                        0.83f, 0.79f, 0.71f, 1f);
            } else if (screenMode == ScreenMode.OPTIONS) {
                float s = optionsMenuScale(width, height);
                float panelW = 660f * s;
                float panelH = 750f * s;
                float x = (width - panelW) / 2f;
                float y = (height - panelH) / 2f;
                drawForgePanel(data, x, y, panelW, panelH, s);
                menuText(data, "OPTIONS", width * 0.5f, y + 36f * s,
                        4.0f * s, 0.98f, 0.83f, 0.55f, 1f);
                menuText(data, optionsPage == 0 ? "VIDEO AND DISPLAY SETTINGS"
                                : "CONTROLS AND INTERFACE",
                        width * 0.5f, y + 78f * s, 1.65f * s,
                        0.78f, 0.78f, 0.73f, 1f);
            } else if (screenMode == ScreenMode.KEYBINDS) {
                float sc = optionsMenuScale(width, height);
                float px = (width - 660f * sc) / 2f;
                float py = (height - 750f * sc) / 2f;
                drawForgePanel(data, px, py, 660f * sc, 750f * sc, sc);
                menuText(data, "KEY BINDINGS", width * 0.5f, py + 36f * sc,
                        3.75f * sc, 0.98f, 0.83f, 0.55f, 1f);
                menuText(data, "CLICK A KEY TO REBIND   PAGE " + (optionsPage - 1) + " OF 3",
                        width * 0.5f, py + 83f * sc, 1.5f * sc,
                        0.78f, 0.79f, 0.75f, 1f);
            } else if (screenMode == ScreenMode.DELETE_CONFIRM) {
                float s = confirmMenuScale(width, height);
                float panelW = 570f * s;
                float panelH = 380f * s;
                float x = (width - panelW) / 2f;
                float y = (height - panelH) / 2f;
                drawForgePanel(data, x, y, panelW, panelH, s);
                menuText(data, "DELETE WORLD?", width * 0.5f,
                        y + 37f * s, 3.8f * s, 1f, 0.67f, 0.46f, 1f);
                menuText(data, deleteWorldLabel == null ? "WORLD" : deleteWorldLabel,
                        width * 0.5f, y + 112f * s, 2.65f * s,
                        0.98f, 0.94f, 0.86f, 1f);
                menuText(data, "THIS CANNOT BE UNDONE", width * 0.5f,
                        y + 161f * s, 1.7f * s,
                        1.0f, 0.54f, 0.45f, 1f);
            } else {
                float s = pauseMenuScale(width, height);
                float panelW = 510f * s;
                float panelH = 435f * s;
                float x = (width - panelW) / 2f;
                float y = (height - panelH) / 2f;
                drawForgePanel(data, x, y, panelW, panelH, s);
                menuText(data, "GAME PAUSED", width * 0.5f,
                        y + 61f * s, 4.0f * s,
                        0.98f, 0.83f, 0.55f, 1f);
                menuText(data, "YOUR ADVENTURE AWAITS", width * 0.5f,
                        y + 112f * s, 1.7f * s,
                        0.77f, 0.77f, 0.74f, 1f);
            }
        }

        for (int index = 0; index < buttonCount(screenMode, optionsPage); index++) {
            MenuButton button = buttonForIndex(index, screenMode, optionsPage);
            float[] bounds = buttonBounds(index, width, height, screenMode, optionsPage);
            float x = bounds[0];
            float y = bounds[1];
            float w = bounds[2];
            float h = bounds[3];

            boolean saveEntry = screenMode == ScreenMode.SAVE_SELECT && index < 3;
            boolean deleteEntry = screenMode == ScreenMode.SAVE_SELECT && index >= 7;
            int worldIndex = deleteEntry ? index - 7 : index;
            boolean existingSave = (saveEntry || deleteEntry)
                    && visibleSaveExists != null
                    && worldIndex < visibleSaveExists.length
                    && visibleSaveExists[worldIndex];
            boolean disabled = (screenMode == ScreenMode.KEYBINDS
                    && ((button == MenuButton.BIND_PREVIOUS && optionsPage <= 2)
                        || (button == MenuButton.BIND_NEXT && optionsPage >= 4)
                        || (button.name().startsWith("BIND_")
                            && button.ordinal() >= MenuButton.BIND_1.ordinal()
                            && button.ordinal() <= MenuButton.BIND_6.ordinal()
                            && KeyBindings.Action.at((optionsPage - 2) * 6
                                + button.ordinal() - MenuButton.BIND_1.ordinal()) == null)))
                    || ((saveEntry || deleteEntry) && !existingSave)
                    || (button == MenuButton.PREVIOUS_PAGE && !hasPreviousSavePage)
                    || (button == MenuButton.NEXT_PAGE && !hasNextSavePage)
                    || (screenMode == ScreenMode.OPTIONS && settings != null
                        && button == MenuButton.RESOLUTION && !settings.allowsResolutionChange());
            boolean hovered = !disabled && buttonAtVirtual(mouseX, mouseY,
                    width, height, screenMode, optionsPage) == button;
            boolean emphasized = button == MenuButton.PLAY || button == MenuButton.NEW_GAME
                    || (button == MenuButton.CONTINUE && screenMode == ScreenMode.PAUSED);
            float s = switch (screenMode) {
                case SAVE_SELECT -> saveMenuScale(width, height);
                case MAIN_MENU -> mainMenuScale(width, height);
                case OPTIONS, KEYBINDS -> optionsMenuScale(width, height);
                case DELETE_CONFIRM -> confirmMenuScale(width, height);
                default -> pauseMenuScale(width, height);
            };

            drawForgeButton(data, x, y, w, h, hovered, emphasized,
                    disabled, s);

            if (screenMode == ScreenMode.KEYBINDS && button.ordinal() >= MenuButton.BIND_1.ordinal()
                    && button.ordinal() <= MenuButton.BIND_6.ordinal()) {
                KeyBindings.Action action = KeyBindings.Action.at((optionsPage - 2) * 6
                        + button.ordinal() - MenuButton.BIND_1.ordinal());
                if (action != null && settings != null) {
                    menuText(data, action.label(), x + 175f * s, y + 15f * s,
                            1.9f * s, 0.95f, 0.93f, 0.86f, 1f);
                    menuText(data, settings.keyBindings().keyLabel(action), x + 475f * s,
                            y + 15f * s, 1.9f * s, 1f, 0.74f, 0.42f, 1f);
                }
                continue;
            }
            if (screenMode == ScreenMode.OPTIONS && settings != null
                    && (button == MenuButton.FOV || button == MenuButton.UI_SCALE)) {
                boolean isFov = button == MenuButton.FOV;
                menuText(data, isFov ? "FIELD OF VIEW" : "UI SCALE", x + 120f * s,
                        y + 17f * s, 2.0f * s,
                        0.96f, 0.92f, 0.85f, 1f);
                float[] track = fovSliderBounds(bounds, s);
                float ratio = isFov
                        ? (settings.fov() - GameSettings.MIN_FOV)
                                / (float) (GameSettings.MAX_FOV - GameSettings.MIN_FOV)
                        : (settings.uiScalePercent() - GameSettings.MIN_UI_SCALE)
                                / (float) (GameSettings.MAX_UI_SCALE - GameSettings.MIN_UI_SCALE);
                ratio = Math.max(0f, Math.min(1f, ratio));
                rect(data, track[0], track[1], track[2], track[3],
                        0.045f, 0.047f, 0.05f, 1f);
                rect(data, track[0], track[1], track[2] * ratio, track[3],
                        0.91f, 0.49f, 0.15f, 1f);
                float knobX = track[0] + ratio * track[2];
                rect(data, knobX - 5f * s, track[1] - 7f * s,
                        10f * s, 24f * s, 0.98f, 0.82f, 0.48f, 1f);
                menuText(data, isFov ? settings.fov() + " DEG"
                                : settings.uiScalePercent() + " PCT", x + 519f * s,
                        y + 15f * s, 1.9f * s,
                        0.99f, 0.87f, 0.66f, 1f);
                continue;
            }
            if (screenMode == ScreenMode.OPTIONS
                    && (button == MenuButton.VIDEO_TAB || button == MenuButton.CONTROLS_TAB)) {
                boolean selected = button == MenuButton.VIDEO_TAB ? optionsPage == 0 : optionsPage == 1;
                if (selected) {
                    rect(data, x + 5f * s, y + h - 7f * s,
                            w - 10f * s, 4f * s, 0.96f, 0.53f, 0.19f, 1f);
                }
            }
            if (deleteEntry) {
                menuText(data, "DELETE", x + w / 2f,
                        y + (h - 7f * 1.65f * s) / 2f, 1.65f * s,
                        disabled ? 0.55f : hovered ? 1f : 0.94f,
                        disabled ? 0.55f : 0.55f,
                        disabled ? 0.55f : 0.46f, 1f);
                continue;
            }

            if (saveEntry) {
                String label = existingSave && visibleSaveLabels != null
                        && index < visibleSaveLabels.length
                        && visibleSaveLabels[index] != null
                        ? visibleSaveLabels[index] : "EMPTY SLOT";
                menuText(data, label, x + w * 0.40f, y + 13f * s,
                        2.65f * s,
                        existingSave ? 0.96f : 0.70f,
                        existingSave ? 0.92f : 0.70f,
                        existingSave ? 0.82f : 0.70f, 1f);
                menuText(data, existingSave ? "LOAD WORLD" : "CREATE WITH NEW GAME",
                        x + w * 0.40f, y + 43f * s, 1.4f * s,
                        existingSave ? 0.74f : 0.60f,
                        existingSave ? 0.76f : 0.61f,
                        existingSave ? 0.70f : 0.59f, 1f);
            } else {
                String label = button == MenuButton.BIND_PREVIOUS ? "PREVIOUS"
                        : button == MenuButton.BIND_NEXT ? "NEXT"
                        : button == MenuButton.RESET_BINDS ? "RESET DEFAULTS"
                        : button == MenuButton.PREVIOUS_PAGE ? "PREVIOUS"
                        : button == MenuButton.NEXT_PAGE ? "NEXT"
                        : screenMode == ScreenMode.OPTIONS ? optionLabel(button, settings)
                        : buttonLabel(button);
                float size = screenMode == ScreenMode.SAVE_SELECT ? 2.35f * s
                        : screenMode == ScreenMode.OPTIONS || screenMode == ScreenMode.KEYBINDS
                                ? 2.15f * s : 2.95f * s;
                menuText(data, label, x + w / 2f,
                        y + (h - 7f * size) / 2f, size,
                        disabled ? 0.54f : hovered ? 1.0f : 0.96f,
                        disabled ? 0.55f : hovered ? 0.87f : 0.93f,
                        disabled ? 0.53f : hovered ? 0.58f : 0.86f, 1f);
            }
        }

        if (statusMessage != null && !statusMessage.isBlank()) {
            menuText(data, statusMessage.toUpperCase(), width / 2f,
                    height * 0.96f, 1.9f, 1f, 0.55f, 0.43f, 1f);
        }
    }

    private String optionLabel(MenuButton button, GameSettings settings) {
        if (settings == null) return buttonLabel(button);
        return switch (button) {
            case VSYNC -> "VSYNC " + (settings.vsync() ? "ON" : "OFF");
            case DISPLAY_MODE -> "DISPLAY MODE  " + settings.displayMode().label();
            case RESOLUTION -> settings.allowsResolutionChange()
                    ? "RESOLUTION " + settings.resolutionLabel()
                    : "RESOLUTION  AUTO";
            case FPS_LIMIT -> "MAX FPS " + settings.fpsLimitLabel();
            case SHOW_FPS -> "SHOW FPS " + (settings.showFps() ? "ON" : "OFF");
            case VIDEO_TAB -> "VIDEO";
            case CONTROLS_TAB -> "CONTROLS";
            case CROSSHAIR -> "CROSSHAIR " + (settings.crosshair() ? "ON" : "OFF");
            case UI_SCALE -> "UI SCALE " + settings.uiScalePercent() + " PCT";
            case KEYBINDS_TAB -> "KEY BINDINGS";
            default -> buttonLabel(button);
        };
    }

    /** Stylized metal/stone panels with ember-colored trim. */
    private void drawForgePanel(List<Float> data, float x, float y,
                                float w, float h, float s) {
        rect(data, x + 8f * s, y + 10f * s, w, h,
                0.006f, 0.007f, 0.010f, 0.62f); // drop shadow
        rect(data, x - 3f * s, y - 3f * s, w + 6f * s, h + 6f * s,
                0.10f, 0.11f, 0.12f, 0.98f);
        rect(data, x, y, w, h,
                0.095f, 0.106f, 0.116f, 0.96f);
        rect(data, x + 5f * s, y + 5f * s, w - 10f * s, h - 10f * s,
                0.14f, 0.15f, 0.16f, 0.96f);
        rect(data, x + 9f * s, y + 9f * s, w - 18f * s, h - 18f * s,
                0.085f, 0.095f, 0.104f, 0.95f);
        // Forge seam and four rivets.
        rect(data, x + 3f * s, y + 3f * s, w - 6f * s, 3f * s,
                0.62f, 0.40f, 0.21f, 0.95f);
        rect(data, x + 3f * s, y + h - 6f * s, w - 6f * s, 3f * s,
                0.29f, 0.24f, 0.19f, 0.95f);
        for (float rx : new float[] {x + 17f * s, x + w - 22f * s}) {
            for (float ry : new float[] {y + 19f * s, y + h - 24f * s}) {
                rect(data, rx, ry, 6f * s, 6f * s, 0.30f, 0.31f, 0.30f, 1f);
                rect(data, rx + s, ry + s, 3f * s, 3f * s,
                        0.67f, 0.58f, 0.45f, 1f);
            }
        }
    }

    /** A beveled voxel-style button; warm focus state is unique to Wildforge. */
    private void drawForgeButton(List<Float> data, float x, float y,
                                 float w, float h,
                                 boolean hovered, boolean emphasized,
                                 boolean disabled, float s) {
        float borderR = disabled ? 0.24f : hovered ? 0.97f : emphasized ? 0.63f : 0.43f;
        float borderG = disabled ? 0.25f : hovered ? 0.62f : emphasized ? 0.43f : 0.45f;
        float borderB = disabled ? 0.25f : hovered ? 0.23f : emphasized ? 0.24f : 0.46f;
        rect(data, x + 2f * s, y + 5f * s, w, h,
                0.008f, 0.009f, 0.013f, 0.70f);
        rect(data, x - 2f * s, y - 2f * s, w + 4f * s, h + 4f * s,
                borderR, borderG, borderB, 1f);
        rect(data, x, y, w, h,
                disabled ? 0.14f : hovered ? 0.29f : emphasized ? 0.23f : 0.18f,
                disabled ? 0.15f : hovered ? 0.22f : emphasized ? 0.20f : 0.20f,
                disabled ? 0.16f : hovered ? 0.17f : emphasized ? 0.17f : 0.21f, 0.97f);
        // Pixel beveled highlights and the dark bottom edge.
        rect(data, x + 3f * s, y + 3f * s, w - 6f * s, 2f * s,
                hovered ? 0.87f : 0.51f, hovered ? 0.56f : 0.53f,
                hovered ? 0.22f : 0.54f, disabled ? 0.25f : 0.84f);
        rect(data, x + 3f * s, y + h - 5f * s, w - 6f * s, 3f * s,
                0.045f, 0.052f, 0.061f, 0.95f);
        if (hovered || emphasized) {
            rect(data, x + 2f * s, y + 5f * s, 4f * s, h - 10f * s,
                    hovered ? 1f : 0.72f, hovered ? 0.58f : 0.40f,
                    hovered ? 0.20f : 0.19f, disabled ? 0.25f : 0.94f);
        }
    }

    private void menuText(List<Float> data, String value, float centerX,
                          float y, float size, float r, float g, float b,
                          float alpha) {
        drawText(data, value, centerX + size * 0.52f, y + size * 0.6f,
                size, 0.015f, 0.018f, 0.024f, alpha * 0.8f);
        drawText(data, value, centerX, y,
                size, r, g, b, alpha);
    }

    private float mainMenuScale(int width, int height) {
        return Math.min(1f, Math.min((width - 36f) / 940f, (height - 30f) / 720f));
    }

    private float saveMenuScale(int width, int height) {
        return Math.min(1f, Math.min((width - 24f) / 570f, (height - 24f) / 645f));
    }

    private float pauseMenuScale(int width, int height) {
        return Math.min(1f, Math.min((width - 24f) / 510f, (height - 24f) / 435f));
    }

    private float optionsMenuScale(int width, int height) {
        return Math.min(1f, Math.min((width - 24f) / 660f, (height - 24f) / 750f));
    }

    private float confirmMenuScale(int width, int height) {
        return Math.min(1f, Math.min((width - 24f) / 570f, (height - 24f) / 380f));
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

    private int buttonCount(ScreenMode screenMode, int optionsPage) {
        return switch (screenMode) {
            case SAVE_SELECT -> 10;
            case OPTIONS -> optionsPage == 0 ? 10 : 5;
            case KEYBINDS -> 10;
            case DELETE_CONFIRM -> 2;
            default -> 3;
        };
    }

    private float[] buttonBounds(
            int index, int width, int height, ScreenMode screenMode, int optionsPage) {
        if (screenMode == ScreenMode.MAIN_MENU) {
            float s = mainMenuScale(width, height);
            float w = 284f * s;
            float gap = 22f * s;
            float total = 3f * w + 2f * gap;
            return new float[] {
                    (width - total) / 2f + index * (w + gap),
                    height - 99f * s,
                    w, 55f * s
            };
        }
        if (screenMode == ScreenMode.SAVE_SELECT) {
            float s = saveMenuScale(width, height);
            float panelX = (width - 570f * s) / 2f;
            float panelY = (height - 645f * s) / 2f;
            if (index >= 7 && index <= 9) {
                int rowIndex = index - 7;
                return new float[] {panelX + 405f * s,
                        panelY + (131f + rowIndex * 82f) * s,
                        107f * s, 43f * s};
            }
            if (index < 3) {
                return new float[] {panelX + 46f * s,
                        panelY + (117f + index * 82f) * s,
                        478f * s, 72f * s};
            }
            if (index == 3) {
                return new float[] {panelX + 46f * s,
                        panelY + 381f * s,
                        478f * s, 57f * s};
            }
            if (index == 4 || index == 5) {
                return new float[] {panelX + (index == 4 ? 46f : 291f) * s,
                        panelY + 457f * s,
                        233f * s, 48f * s};
            }
            return new float[] {panelX + 46f * s,
                    panelY + 556f * s,
                    478f * s, 52f * s};
        }
        if (screenMode == ScreenMode.OPTIONS) {
            float s = optionsMenuScale(width, height);
            float panelX = (width - 660f * s) / 2f;
            float panelY = (height - 750f * s) / 2f;
            if (index < 2) {
                return new float[] {panelX + (index == 0 ? 45f : 343f) * s,
                        panelY + 106f * s, 272f * s, 44f * s};
            }
            if ((optionsPage == 0 && index == 9) || (optionsPage == 1 && index == 4)) {
                return new float[] {panelX + 46f * s, panelY + 652f * s,
                        568f * s, 52f * s};
            }
            return new float[] {panelX + 46f * s,
                    panelY + (170f + (index - 2) * 62f) * s,
                    568f * s, 50f * s};
        }
        if (screenMode == ScreenMode.KEYBINDS) {
            float sc = optionsMenuScale(width, height);
            float px = (width - 660f * sc) / 2f;
            float py = (height - 750f * sc) / 2f;
            if (index < 6) {
                return new float[] {px + 46f * sc, py + (127f + index * 71f) * sc,
                        568f * sc, 55f * sc};
            }
            if (index == 6 || index == 7) {
                return new float[] {px + (index == 6 ? 46f : 336f) * sc,
                        py + 574f * sc, 278f * sc, 45f * sc};
            }
            return new float[] {px + (index == 8 ? 46f : 336f) * sc,
                    py + 647f * sc, 278f * sc, 54f * sc};
        }
        if (screenMode == ScreenMode.DELETE_CONFIRM) {
            float s = confirmMenuScale(width, height);
            float panelY = (height - 380f * s) / 2f;
            return new float[] {
                    (width - 478f * s) / 2f,
                    panelY + (209f + index * 78f) * s,
                    478f * s, 59f * s
            };
        }
        float s = pauseMenuScale(width, height);
        float panelY = (height - 435f * s) / 2f;
        float w = 396f * s;
        return new float[] {
                (width - w) / 2f,
                panelY + (170f + index * 79f) * s,
                w, 58f * s
        };
    }

    private MenuButton buttonForIndex(int index, ScreenMode screenMode, int optionsPage) {
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
                case 3 -> MenuButton.NEW_GAME;
                case 4 -> MenuButton.PREVIOUS_PAGE;
                case 5 -> MenuButton.NEXT_PAGE;
                case 6 -> MenuButton.BACK;
                case 7 -> MenuButton.DELETE_ONE;
                case 8 -> MenuButton.DELETE_TWO;
                case 9 -> MenuButton.DELETE_THREE;
                default -> MenuButton.NONE;
            };
        }
        if (screenMode == ScreenMode.OPTIONS) {
            if (optionsPage == 0) {
                return switch (index) {
                    case 0 -> MenuButton.VIDEO_TAB;
                    case 1 -> MenuButton.CONTROLS_TAB;
                    case 2 -> MenuButton.DISPLAY_MODE;
                    case 3 -> MenuButton.RESOLUTION;
                    case 4 -> MenuButton.VSYNC;
                    case 5 -> MenuButton.FPS_LIMIT;
                    case 6 -> MenuButton.FOV;
                    case 7 -> MenuButton.SHOW_FPS;
                    case 8 -> MenuButton.UI_SCALE;
                    case 9 -> MenuButton.BACK;
                    default -> MenuButton.NONE;
                };
            }
            return switch (index) {
                case 0 -> MenuButton.VIDEO_TAB;
                case 1 -> MenuButton.CONTROLS_TAB;
                case 2 -> MenuButton.CROSSHAIR;
                case 3 -> MenuButton.KEYBINDS_TAB;
                case 4 -> MenuButton.BACK;
                default -> MenuButton.NONE;
            };
        }
        if (screenMode == ScreenMode.KEYBINDS) {
            return switch (index) {
                case 0 -> MenuButton.BIND_1;
                case 1 -> MenuButton.BIND_2;
                case 2 -> MenuButton.BIND_3;
                case 3 -> MenuButton.BIND_4;
                case 4 -> MenuButton.BIND_5;
                case 5 -> MenuButton.BIND_6;
                case 6 -> MenuButton.BIND_PREVIOUS;
                case 7 -> MenuButton.BIND_NEXT;
                case 8 -> MenuButton.RESET_BINDS;
                case 9 -> MenuButton.BACK;
                default -> MenuButton.NONE;
            };
        }
        if (screenMode == ScreenMode.DELETE_CONFIRM) {
            return switch (index) {
                case 0 -> MenuButton.CONFIRM_DELETE;
                case 1 -> MenuButton.CANCEL_DELETE;
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
            case NEW_GAME -> "NEW GAME";
            case PREVIOUS_PAGE -> "PREVIOUS PAGE";
            case NEXT_PAGE -> "NEXT PAGE";
            case DELETE_ONE, DELETE_TWO, DELETE_THREE -> "DELETE";
            case CONFIRM_DELETE -> "DELETE FOREVER";
            case CANCEL_DELETE -> "CANCEL";
            case VIDEO_TAB -> "VIDEO";
            case CONTROLS_TAB -> "CONTROLS";
            case DISPLAY_MODE -> "DISPLAY MODE";
            case RESOLUTION -> "RESOLUTION";
            case VSYNC -> "VSYNC";
            case FPS_LIMIT -> "MAX FPS";
            case SHOW_FPS -> "SHOW FPS";
            case FOV -> "FIELD OF VIEW";
            case CROSSHAIR -> "CROSSHAIR";
            case UI_SCALE -> "UI SCALE";
            case KEYBINDS_TAB -> "KEY BINDINGS";
            case BIND_1, BIND_2, BIND_3, BIND_4, BIND_5, BIND_6 -> "REMAP KEY";
            case BIND_PREVIOUS -> "PREVIOUS";
            case BIND_NEXT -> "NEXT";
            case RESET_BINDS -> "RESET DEFAULTS";
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
            case '0' -> new String[] {"01110", "10001", "10011", "10101", "11001", "10001", "01110"};
            case '1' -> new String[] {"00100", "01100", "00100", "00100", "00100", "00100", "01110"};
            case '2' -> new String[] {"01110", "10001", "00001", "00010", "00100", "01000", "11111"};
            case '3' -> new String[] {"11110", "00001", "00001", "01110", "00001", "00001", "11110"};
            case '4' -> new String[] {"00010", "00110", "01010", "10010", "11111", "00010", "00010"};
            case '5' -> new String[] {"11111", "10000", "10000", "11110", "00001", "00001", "11110"};
            case '6' -> new String[] {"01110", "10000", "10000", "11110", "10001", "10001", "01110"};
            case '7' -> new String[] {"11111", "00001", "00010", "00100", "01000", "01000", "01000"};
            case '8' -> new String[] {"01110", "10001", "10001", "01110", "10001", "10001", "01110"};
            case '9' -> new String[] {"01110", "10001", "10001", "01111", "00001", "00001", "01110"};
            default -> new String[] {"00000", "00000", "00000", "00000", "00000", "00000", "00000"};
        };
    }

    private void drawHotbar(
            List<Float> data,
            List<Float> iconData,
            List<Float> countData,
            int width,
            int height,
            Player player,
            GameSettings settings) {

        int totalWidth = hotbarWidth();
        float startX = (width - totalWidth) / 2f;
        float y = height - SLOT_SIZE - 22;
        rect(data, startX - 12, y - 12, totalWidth + 24, SLOT_SIZE + 24,
                0.045f, 0.048f, 0.050f, 0.90f);
        rect(data, startX - 9, y - 9, totalWidth + 18, SLOT_SIZE + 18,
                0.25f, 0.23f, 0.19f, 0.94f);
        rect(data, startX - 7, y - 7, totalWidth + 14, SLOT_SIZE + 14,
                0.085f, 0.095f, 0.102f, 1f);
        rect(data, startX - 7, y - 7, totalWidth + 14, 2,
                0.70f, 0.42f, 0.18f, 0.92f);

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

            // Reflect remapped hotbar keys instead of hardcoding 1 through 9.
            drawHotbarKey(data, x, y, i, settings);
        }
    }

    private void drawInventory(
            List<Float> data,
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

        // Forge-styled inventory: a softer dimmer, metal shell, stone insert and ember trim.
        rect(data, 0, 0, width, height, 0f, 0f, 0f, 0.42f);
        drawForgePanel(data, panelX, panelY, panelWidth, panelHeight, 1f);
        menuText(data, "INVENTORY", width / 2f, panelY + 16f,
                2.9f, 0.99f, 0.83f, 0.57f, 1f);
        rect(data, panelX + 26f, panelY + 52f, panelWidth - 52f, 2,
                0.65f, 0.36f, 0.14f, 0.9f);
        menuText(data, "STORAGE", panelX + 72f, panelY + 54f,
                1.2f, 0.76f, 0.76f, 0.73f, 1f);

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

        rect(data, startX, hotbarY - 10,
                gridWidth, 2, 0.68f, 0.41f, 0.19f, 0.95f);
        menuText(data, "QUICK ACCESS", width / 2f, hotbarY - 25f,
                1.35f, 0.92f, 0.77f, 0.55f, 1f);

        // Hotbar slots also appear in the inventory.
        for (int col = 0; col < Player.HOTBAR_SLOTS; col++) {
            int index = Player.HOTBAR_START_INDEX + col;
            float x = startX + col * (SLOT_SIZE + SLOT_GAP);

            drawSlot(data, x, hotbarY,
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
        drawNumber(countData, x + SLOT_SIZE - 17,
                y + SLOT_SIZE - 17, stack.getCount());
    }

    private void drawSlot(
            List<Float> data, float x, float y, boolean selected) {

        if (selected) {
            rect(data, x - 2, y - 2,
                    SLOT_SIZE + 4, SLOT_SIZE + 4,
                    0.96f, 0.57f, 0.21f, 1f);
        }

        rect(data, x, y, SLOT_SIZE, SLOT_SIZE,
                selected ? 0.60f : 0.045f, selected ? 0.34f : 0.053f,
                selected ? 0.16f : 0.064f, 1f);
        rect(data, x + 2, y + 2,
                SLOT_SIZE - 4, SLOT_SIZE - 4,
                selected ? 0.23f : 0.13f, selected ? 0.19f : 0.14f,
                selected ? 0.14f : 0.15f, 1f);
        rect(data, x + 3, y + 3, SLOT_SIZE - 6, 2,
                selected ? 0.98f : 0.31f, selected ? 0.72f : 0.32f,
                selected ? 0.37f : 0.34f, 0.96f);
        rect(data, x + 3, y + SLOT_SIZE - 5, SLOT_SIZE - 6, 2,
                0.045f, 0.046f, 0.050f, 0.90f);
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
                // Use the block's texture layer for other block types.
                topLayer = blockType.getId();
                sideLayer = blockType.getId();
            }
        }

        // Larger 30x30 icon, centered inside the 48x48 slot.
        float left = slotX + 9;
        float top = slotY + 4;
        float middle = left + 15;
        float right = left + 30;

        float topQuarter = top + 7.5f;
        float center = top + 15;
        float bottomQuarter = top + 22.5f;
        float bottom = top + 30;

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
