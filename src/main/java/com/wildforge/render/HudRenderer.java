package com.wildforge.render;

import com.wildforge.core.GameSettings;
import com.wildforge.item.ItemStack;
import com.wildforge.player.Player;
import java.util.ArrayList;
import java.util.List;
import static com.wildforge.render.InventoryLayout.SLOT_SIZE;
import static com.wildforge.render.PixelFontRenderer.*;
import static com.wildforge.render.UiPrimitives.rect;

/** Small public entry point used by Renderer/Main. Layout and GPU work live in collaborators. */
public final class HudRenderer {
    public enum ScreenMode {
        MAIN_MENU,
        SAVE_SELECT,
        WORLD_CREATE,
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
        RENDER_DISTANCE,
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

    private final UiSkin uiSkin;
    private final InventoryRenderer inventory;
    private final MenuRenderer menu;
    private final UiBatchRenderer batches;
    private boolean showCrosshair = true;
    private boolean showFps;
    private int measuredFps;
    private boolean debugOverlay;
    private int debugLoaded, debugPending, debugDistance;
    private int debugMeshes,debugLodJobs,debugLodReady,debugGpu,debugUploads;
    private long debugStorageBytes,debugUploadMicros;
    private int debugAverageFps;
    private float debugAverageMs, debugP95Ms, debugWorstMs;
    // One global scale for menus, dialogs, inventory, icons, text and the HUD.
    private static float interfaceScale = 1.25f;

    public void setUiScale(float scale) {
        interfaceScale = Math.max(0.75f, Math.min(2f, scale));
    }

    /**
     * Make the interface responsive, keeping inventory accessible on small screens.
     * Menu panels have their own proportional fit rules in virtual coordinates.
     */
    static float effectiveUiScale(int width, int height) {
        return Math.min(interfaceScale, Math.min(width / 610f, height / 400f));
    }

    static int virtualSize(int physicalSize, float scale) {
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
    public void setDebugOverlay(boolean enabled){debugOverlay=enabled;}
    private static String formatMs(float value){return String.format(java.util.Locale.ROOT,"%.2f",value);}
    public void setDebugFrameStats(int fps,float avgMs,float p95Ms,float worstMs){
        debugAverageFps=fps;debugAverageMs=avgMs;debugP95Ms=p95Ms;debugWorstMs=worstMs;
    }
    public void setDebugStreaming(int loaded,int pending,int distance){
        debugLoaded=loaded;debugPending=pending;debugDistance=distance;
    }

    public void setDebugWorkload(int meshes,int lodJobs,int lodReady,long bytes,int gpu,int uploads,long micros){
        debugMeshes=meshes;debugLodJobs=lodJobs;debugLodReady=lodReady;debugStorageBytes=bytes;debugGpu=gpu;debugUploads=uploads;debugUploadMicros=micros;
    }

    public HudRenderer(BlockTextures blockTextures) {
        if (blockTextures == null) {
            throw new IllegalArgumentException("Block textures are required");
        }
        uiSkin = new UiSkin();
        inventory = new InventoryRenderer(uiSkin);
        menu = new MenuRenderer(uiSkin);
        batches = new UiBatchRenderer(blockTextures);
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
            int optionsPage, String worldName, String worldSeed, boolean editingWorldName) {

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
        List<Float> dimData = new ArrayList<>();
        List<Float> skinData = new ArrayList<>();
        List<Float> tooltipSkin = new ArrayList<>();
        List<Float> iconData = new ArrayList<>();
        List<Float> countData = new ArrayList<>();
        List<Float> tooltipData = new ArrayList<>();

        if (screenMode == ScreenMode.PLAYING) {
            // Show the crosshair only while interacting with the world.
            // Inventory screens release the mouse and must not show aiming UI.
            if (showCrosshair && !player.isInventoryOpen()) {
                rect(data, width / 2f - 1, height / 2f - 8,
                        2, 16, 1f, 1f, 1f, 0.9f);
                rect(data, width / 2f - 8, height / 2f - 1,
                        16, 2, 1f, 1f, 1f, 0.9f);
            }

            inventory.drawHotbar(
                    data, skinData, iconData, countData,
                    width, height, player, settings);

            if (showFps) {
                rect(data, 10f, 10f, 132f, 37f, 0.04f, 0.05f, 0.06f, 0.68f);
                menuText(data, "FPS " + measuredFps, 76f, 19f, 2.05f,
                        0.96f, 0.86f, 0.66f, 1f);
            }
            if (debugOverlay) {
                rect(data,10f,showFps?53f:10f,355f,188f,0.04f,0.05f,0.06f,0.78f);
                float y0=showFps?63f:20f;
                menuText(data,"DEBUG F3  FPS " + measuredFps,145f,y0,1.7f,0.9f,0.9f,0.72f,1f);
                menuText(data,"AVG " + debugAverageFps + " FPS  FRAME " + formatMs(debugAverageMs) + " MS  P95 " + formatMs(debugP95Ms) + " MS  MAX " + formatMs(debugWorstMs) + " MS",185f,y0+24f,1.05f,0.9f,0.9f,0.72f,1f);
                menuText(data,"CHUNKS " + debugLoaded + " / " + ((2*debugDistance+1)*(2*debugDistance+1)),145f,y0+49f,1.5f,0.9f,0.9f,0.72f,1f);
                menuText(data,"GENERATING " + debugPending + "  VIEW " + debugDistance,185f,y0+74f,1.5f,0.9f,0.9f,0.72f,1f);
                menuText(data,"MESH JOBS " + debugMeshes + "  LOD JOBS " + debugLodJobs + "  READY " + debugLodReady,185f,y0+99f,1.25f,0.9f,0.9f,0.72f,1f);
                menuText(data,"GPU MESHES " + debugGpu + "  UPLOADS " + debugUploads,185f,y0+124f,1.25f,0.9f,0.9f,0.72f,1f);
                menuText(data,"UPLOAD CPU " + (debugUploadMicros/1000f) + " MS  BLOCK DATA " + (debugStorageBytes/1048576L) + " MIB",185f,y0+149f,1.15f,0.9f,0.9f,0.72f,1f);
            }

            if (player.isInventoryOpen()) {
                inventory.drawInventory(
                        data, dimData, skinData, iconData, countData,
                        width, height, player, settings);

                // Draw the held stack above the inventory and cursor.
                ItemStack cursorStack = player.getCursorStack();
                if (cursorStack != null) {
                    float x = mouseX - SLOT_SIZE / 2f;
                    float y = mouseY - SLOT_SIZE / 2f;

                    inventory.drawItemIcon(
                            data, iconData, x, y,
                            cursorStack.getItemType());
                    drawNumber(countData, x, y, cursorStack.getCount());
                }

                int hoveredSlot =
                        InventoryLayout.slotAtVirtual(mouseX, mouseY, width, height);
                if (hoveredSlot >= 0) {
                    ItemStack hoveredStack =
                            player.getInventoryStack(hoveredSlot);
                    if (hoveredStack != null) {
                        inventory.drawTooltip(
                                tooltipData, tooltipSkin,
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
            menu.drawMenu(
                    data, dimData, skinData, width, height, mouseX, mouseY,
                    screenMode, statusMessage,
                    visibleSaveLabels, visibleSaveExists,
                    savePage, hasPreviousSavePage, hasNextSavePage,
                    settings, deleteWorldLabel, optionsPage);
        } else if(screenMode==ScreenMode.WORLD_CREATE){
            menu.drawCreateWorldScreen(data,dimData,skinData,width,height,
                    worldName,worldSeed,editingWorldName,statusMessage);
        } else {
            menu.drawStatusScreen(data, dimData, skinData,
                    width, height, screenMode, statusMessage);
        }

        // Backdrop -> textured frame/slots -> text -> item icons -> counts.
        // Tooltip lives above everything, with its own background and text.
        batches.uploadAndDraw(dimData, width, height);
        uiSkin.render(skinData, width, height);
        batches.uploadAndDraw(data, width, height);
        batches.uploadAndDrawIcons(iconData, width, height);
        batches.uploadAndDraw(countData, width, height);
        uiSkin.render(tooltipSkin, width, height);
        batches.uploadAndDraw(tooltipData, width, height);
    }

    /** Public framebuffer coordinates, converted using the same scale as drawing. */
    public static int inventorySlotAt(float mouseX, float mouseY, int width, int height) {
        if (width <= 0 || height <= 0) return -1;
        float scale = effectiveUiScale(width, height);
        return InventoryLayout.slotAtVirtual(mouseX / scale, mouseY / scale,
                virtualSize(width, scale), virtualSize(height, scale));
    }

    public MenuButton buttonAt(float mouseX, float mouseY, int width, int height,
                               ScreenMode screenMode) {
        return menu.buttonAt(mouseX, mouseY, width, height, screenMode);
    }

    public MenuButton buttonAt(float mouseX, float mouseY, int width, int height,
                               ScreenMode screenMode, int optionsPage) {
        return menu.buttonAt(mouseX, mouseY, width, height, screenMode, optionsPage);
    }

    public int fovSliderValueAt(float mouseX, float mouseY,
                                int width, int height, int optionsPage) {
        return menu.fovSliderValueAt(mouseX, mouseY, width, height, optionsPage);
    }

    public int fovSliderValueClamped(float mouseX, int width, int height, int optionsPage) {
        return menu.fovSliderValueClamped(mouseX, width, height, optionsPage);
    }

    public int renderDistanceSliderValueAt(float x,float y,int w,int h,int page){return menu.renderDistanceSliderValueAt(x,y,w,h,page);}
    public int renderDistanceSliderValueClamped(float x,int w,int h,int page){return menu.renderDistanceSliderValueClamped(x,w,h,page);}
    public float[] uiScaleSliderTrack(int width, int height, int optionsPage) {
        return menu.uiScaleSliderTrack(width, height, optionsPage);
    }

    public void close() {
        batches.close();
        uiSkin.close();
    }
}
