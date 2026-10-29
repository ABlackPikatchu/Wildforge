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
    private String creativeSearch="";
    private int creativePage=0,creativeCategory=0;
    public void setCreativeInventoryState(String query,int page,int category){
        creativeSearch=query==null?"":query;
        creativePage=Math.max(0,page);creativeCategory=Math.max(0,Math.min(com.wildforge.ui.CreativeCatalog.CATEGORY_COUNT-1,category));
    }
    public int creativeInventoryHit(float x,float y,int w,int h){
        if(w<=0||h<=0)return -1;
        float scale=effectiveUiScale(w,h);
        return com.wildforge.ui.CreativeCatalog.hit(x/scale,y/scale,virtualSize(w,scale),virtualSize(h,scale));
    }
    private final MenuRenderer menu;
    private final UiBatchRenderer batches;
    private boolean showCrosshair = true;
    private boolean showFps;
    private int measuredFps;
    private boolean debugOverlay;
    private boolean consoleOpen;
    private String consoleInput="",consoleFeedback="";
    private java.util.List<String> consoleSuggestions=java.util.List.of();
    private com.wildforge.item.ItemType creativeDragItem;
    private int creativeDragCount;
    public void setConsoleSuggestions(java.util.List<String> suggestions) {
        consoleSuggestions=suggestions==null?java.util.List.of():suggestions;
    }
    public void setCreativeDragItem(com.wildforge.item.ItemType item,int count){
        creativeDragItem=item;creativeDragCount=count;
    }
    private String debugBiome="",debugMode="",debugFacing="";
    private long debugSeed;
    private int debugX,debugY,debugZ,debugFluidQueue;
    private boolean debugFlying;
    private long debugHeapUsed,debugHeapMax;
    private int lastBiomeX=Integer.MIN_VALUE,lastBiomeZ=Integer.MIN_VALUE;
    private long lastBiomeSampleNanos;
    public void setDeveloperOverlay(Player player, com.wildforge.world.TerrainMesh terrain,
                                    boolean open,String input,String feedback){
        consoleOpen=open;consoleInput=input==null?"":input;
        consoleFeedback=feedback==null?"":feedback;
        if (player==null || terrain==null || !debugOverlay)return;
        debugX=(int)Math.floor(player.getX());debugY=(int)Math.floor(player.getFeetY());debugZ=(int)Math.floor(player.getZ());
        if (Math.abs(debugX-lastBiomeX)>8 || Math.abs(debugZ-lastBiomeZ)>8 ||
                System.nanoTime()-lastBiomeSampleNanos>1_000_000_000L) {
            debugBiome=terrain.biomeNameAt(debugX,debugZ);
            lastBiomeX=debugX;lastBiomeZ=debugZ;
            lastBiomeSampleNanos=System.nanoTime();
        }
        debugMode=player.getGameMode().name();debugFlying=player.isFlying();
        debugSeed=terrain.seed();debugFluidQueue=terrain.activeFluidUpdates();
        org.joml.Vector3f look=player.getCamera().getLookDirection();
        debugFacing=Math.abs(look.x)>Math.abs(look.z)?(look.x>0?"EAST":"WEST"):(look.z>0?"SOUTH":"NORTH");
        Runtime runtime=Runtime.getRuntime();
        debugHeapUsed=runtime.totalMemory()-runtime.freeMemory();debugHeapMax=runtime.maxMemory();
    }
    private int debugLoaded, debugPending, debugDistance;
    private int debugMeshes,debugMeshSections,debugLodJobs,debugLodReady,debugGpu,debugUploads;
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
        return Math.min(interfaceScale, Math.min(width / 610f, height / 480f));
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

    public void setDebugWorkload(int meshes,int sections,int lodJobs,int lodReady,long bytes,int gpu,int uploads,long micros){
        debugMeshSections=sections;debugMeshes=meshes;debugLodJobs=lodJobs;debugLodReady=lodReady;debugStorageBytes=bytes;debugGpu=gpu;debugUploads=uploads;debugUploadMicros=micros;
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
            int optionsPage, String worldName, String worldSeed, boolean editingWorldName,
            com.wildforge.player.GameMode newGameMode, boolean cheatsEnabled) {

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
                float y0=showFps?58f:15f;
                rect(data,5f,y0,390f,284f,0.04f,0.05f,0.06f,0.72f);
                float px=198f;
                menuText(data,"WILDFORGE 0.1 / DEBUG [F3]",px,y0+7f,1.5f,.94f,.94f,.78f,1f);
                menuText(data,"FPS " + measuredFps + " AVG " + debugAverageFps + " P95 " + formatMs(debugP95Ms) + "MS",px,y0+29f,1.18f,.94f,.94f,.78f,1f);
                menuText(data,"FRAME " + formatMs(debugAverageMs) + " MS MAX " + formatMs(debugWorstMs) + " MS",px,y0+47f,1.18f,.94f,.94f,.78f,1f);
                menuText(data,"XYZ " + debugX + " / " + debugY + " / " + debugZ + "  " + debugFacing,px,y0+69f,1.25f,.94f,.94f,.78f,1f);
                menuText(data,"CHUNK " + Math.floorDiv(debugX,16) + " / " + Math.floorDiv(debugZ,16) + "  VIEW " + debugDistance,px,y0+87f,1.15f,.94f,.94f,.78f,1f);
                menuText(data,"BIOME " + debugBiome,px,y0+106f,1.07f,.94f,.94f,.78f,1f);
                menuText(data,"MODE " + debugMode + (debugFlying?"  FLYING":""),px,y0+126f,1.15f,.94f,.94f,.78f,1f);
                menuText(data,"CHUNKS " + debugLoaded + " / " + ((2*debugDistance+1)*(2*debugDistance+1)) + "  GEN " + debugPending,px,y0+146f,1.08f,.94f,.94f,.78f,1f);
                menuText(data,"MESH JOBS " + debugMeshes + "  SECTIONS " + debugMeshSections + " LOD JOBS " + debugLodJobs,px,y0+164f,1.0f,.94f,.94f,.78f,1f);
                menuText(data,"GPU " + debugGpu + "  LOD READY " + debugLodReady + "  UPLOADS " + debugUploads,px,y0+183f,1.03f,.94f,.94f,.78f,1f);
                menuText(data,"UPLOAD CPU " + formatMs(debugUploadMicros/1000f) + " MS  BLOCK DATA " + (debugStorageBytes/1048576) + " MIB",px,y0+202f,0.98f,.94f,.94f,.78f,1f);
                menuText(data,"FLUID " + debugFluidQueue + "  MEM " + (debugHeapUsed/1048576) + " / " + (debugHeapMax/1048576) + " MIB",px,y0+221f,1.08f,.94f,.94f,.78f,1f);
                menuText(data,"SEED " + debugSeed,px,y0+241f,.95f,.94f,.94f,.78f,1f);
            }
            if(consoleOpen || !consoleFeedback.isBlank()) {
                // A left-aligned chat-style input bar. Slash is a supported pixel glyph.
                float barWidth=Math.min(width-20f,750f);
                float barY=height-62f;
                rect(data,10f,barY,barWidth,48f,.035f,.045f,.065f,.84f);
                if(consoleOpen) {
                    menuText(data,consoleInput+"_",10f+barWidth/2f,barY+16f,1.7f,.96f,.98f,.95f,1f);
                    int display=Math.min(5,consoleSuggestions.size());
                    if(display>0){
                        float top=barY-display*25f-6f;
                        rect(data,10f,top,barWidth,display*25f+3f,.03f,.04f,.055f,.80f);
                        for(int i=0;i<display;i++)
                            menuText(data,consoleSuggestions.get(i),10f+barWidth/2f,top+i*25f+7f,
                                1.12f,i==0?.99f:.70f,i==0?.88f:.75f,.80f,1f);
                    }
                } else if(!consoleFeedback.isBlank()) {
                    menuText(data,consoleFeedback,10f+barWidth/2f,barY+16f,1.28f,.90f,.90f,.77f,1f);
                }
            }

            if (player.isInventoryOpen()) {
                if(player.getGameMode()==com.wildforge.player.GameMode.CREATIVE) {
                    inventory.drawCreative(data,dimData,skinData,iconData,countData,tooltipData,tooltipSkin,
                        width,height,mouseX,mouseY,player,creativeSearch,creativePage,creativeCategory);
                } else inventory.drawInventory(
                        data, dimData, skinData, iconData, countData,
                        width, height, player, settings);

                if(player.getGameMode()==com.wildforge.player.GameMode.CREATIVE && creativeDragItem!=null){
                    inventory.drawItemIcon(data,iconData,mouseX-24f,mouseY-24f,creativeDragItem);
                    drawNumber(countData,mouseX-24f,mouseY-24f,creativeDragCount);
                }
                // Draw the held stack above the inventory and cursor.
                ItemStack cursorStack = player.getGameMode()==com.wildforge.player.GameMode.CREATIVE?null:player.getCursorStack();
                if (cursorStack != null) {
                    float x = mouseX - SLOT_SIZE / 2f;
                    float y = mouseY - SLOT_SIZE / 2f;

                    inventory.drawItemIcon(
                            data, iconData, x, y,
                            cursorStack.getItemType());
                    drawNumber(countData, x, y, cursorStack.getCount());
                }

                int hoveredSlot = player.getGameMode()==com.wildforge.player.GameMode.CREATIVE ? -1 :
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
                    worldName,worldSeed,editingWorldName,statusMessage,newGameMode,cheatsEnabled);
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
