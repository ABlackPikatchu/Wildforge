package com.wildforge;

import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef.HWND;
import com.wildforge.content.ContentLoader;
import com.wildforge.core.DisplayModeController;
import com.wildforge.core.DeveloperCommands;
import com.wildforge.core.FrameTiming;
import com.wildforge.core.GameSettings;
import com.wildforge.core.InputController;
import com.wildforge.core.KeyBindings;
import com.wildforge.core.SaveManager;
import com.wildforge.lang.LanguageManager;
import com.wildforge.player.Player;
import com.wildforge.player.GameMode;
import com.wildforge.render.HudRenderer;
import com.wildforge.render.Renderer;
import com.wildforge.world.TerrainMesh;
import com.wildforge.world.WorldItemManager;
import com.wildforge.ui.SaveSelectionModel;
import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.glfw.GLFWNativeWin32;
import org.lwjgl.glfw.GLFWVidMode;
import org.lwjgl.opengl.GL;

import java.io.IOException;

import static org.lwjgl.glfw.GLFW.*;

public final class Main implements InputController.Callbacks {
    private static final long MIN_SAVING_SCREEN_NANOS = 400_000_000L;
    private static final float MAX_FRAME_DELTA_SECONDS = 0.1f;

    private long window;
    private Renderer renderer;
    private ContentLoader.Content gameContent;
    private InputController inputController;
    private TerrainMesh terrain;
    private Player player;
    private final WorldItemManager worldItems = new WorldItemManager();
    private final SaveManager saveManager = new SaveManager();
    private final GameSettings settings = GameSettings.load();
    private HudRenderer.ScreenMode optionsReturnScreen = HudRenderer.ScreenMode.MAIN_MENU;
    private int optionsPage;
    private boolean fovSliderDragging;
    private boolean renderDistanceSliderDragging;
    private boolean debugOverlay;
    private boolean consoleOpen;
    private boolean skipConsoleTriggerCharacter;
    private boolean suppressWorldCreateCharacter;
    private final StringBuilder consoleInput = new StringBuilder();
    private String consoleFeedback = "";
    private long consoleFeedbackNanos;
    private final java.util.ArrayDeque<String> consoleHistory = new java.util.ArrayDeque<>();
    private int consoleHistoryCursor=-1;
    private final com.wildforge.core.FrameStats frameStats = new com.wildforge.core.FrameStats();
    private boolean uiScaleSliderDragging;
    // Physical track is held constant while the entire UI changes size.
    private float[] uiScaleDragTrack;
    private final FrameTiming frameTiming = new FrameTiming();
    private final DisplayModeController displayController = new DisplayModeController();
    private final SaveSelectionModel saveSelection = new SaveSelectionModel(saveManager);
    private KeyBindings.Action bindingToCapture;
    private int pendingDeleteSlot = -1;
    private String pendingDeleteLabel = "";

    private final int[] framebufferWidth = new int[1];
    private final int[] framebufferHeight = new int[1];
    private final int[] windowWidth = new int[1];
    private final int[] windowHeight = new int[1];

    private double mouseX;
    private double mouseY;

    private boolean inventoryDragActive = false;
    private int inventoryDragSourceSlot = -1;

    private HudRenderer.ScreenMode screenMode = HudRenderer.ScreenMode.MAIN_MENU;
    private boolean escapeWasDown;
    private boolean loadPending;
    private boolean preloadingWorld;
    private int launchSplashFrames=2;
    private boolean savePending;
    private boolean quitAfterSave;
    private boolean returnToMainMenuAfterSave;
    private long savingScreenStartedAtNanos;
    private boolean worldLoaded;
    private int activeSaveSlot = -1;
    private String statusMessage = "";
    private final StringBuilder seedEntry = new StringBuilder();
    private Long requestedNewSeed;
    private final StringBuilder worldNameEntry = new StringBuilder("New World");
    private boolean editingWorldName = false;
    private GameMode selectedNewGameMode=GameMode.SURVIVAL;
    private GameMode pendingCreateGameMode;
    private boolean selectedNewCheats = false;
    private boolean pendingNewCheats = false;
    private boolean activeWorldCheats = false;
    private final StringBuilder creativeSearch = new StringBuilder();
    private int creativePage = 0;
    private int creativeCategory = 0;
    private com.wildforge.item.ItemType creativeDragging;
    private int creativeDraggingCount=64;
    private String newWorldNamePending;
    private boolean focusRetryPending = true;
    private static long stableSeed(String text){
        long hash=0xcbf29ce484222325L;
        for(int i=0;i<text.length();i++){hash^=text.charAt(i);hash*=0x100000001b3L;}
        return hash;
    }
    private void updateSeedText(){statusMessage="";}
    private void createWorldFromMenu(){
        if (saveManager.nextAvailableSlot() < 0) { statusMessage="NO FREE WORLD SLOTS"; return; }
        String input=seedEntry.toString().trim();
        try{requestedNewSeed=input.isEmpty()?new java.security.SecureRandom().nextLong():Long.parseLong(input);}
        catch(NumberFormatException ex){requestedNewSeed=stableSeed(input);}
        System.out.println("Creating new unique world seed: " + requestedNewSeed);
        newWorldNamePending=worldNameEntry.toString().trim();
        pendingCreateGameMode=selectedNewGameMode;
        pendingNewCheats=selectedNewCheats;
        beginLoadingSave(saveManager.nextAvailableSlot());
    }


    public static void main(String[] args) {
        new Main().run();
    }

    private void run() {
        try {
            ContentLoader.Content content = ContentLoader.loadBuiltIn();
            ContentLoader.validateLegacyIds(content);
            gameContent = content;
            LanguageManager.install(LanguageManager.loadBuiltIn());
            System.out.println("Content loaded: " + content.blocks().size()
                    + " blocks, " + content.items().size() + " items, " + content.tags().blockTagCount()
                    + " block tags, " + content.tags().itemTagCount() + " item tags");
        } catch (IOException ex) {
            throw new IllegalStateException("Unable to load Wildforge content definitions", ex);
        }

        GLFWErrorCallback errorCallback = GLFWErrorCallback.createPrint(System.err);
        errorCallback.set();

        try {
            initialize();
            loop();
        } finally {
            if (renderer != null) {
                renderer.close();
            }
            if (window != 0) {
                glfwDestroyWindow(window);
            }
            glfwTerminate();
            errorCallback.free();
        }
    }

    private void initialize() {
        if (!glfwInit()) {
            throw new IllegalStateException("Unable to initialize GLFW");
        }

        glfwDefaultWindowHints();
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3);
        glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
        glfwWindowHint(GLFW_OPENGL_FORWARD_COMPAT, GLFW_TRUE);
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);

        GLFWVidMode videoMode = glfwGetVideoMode(glfwGetPrimaryMonitor());
        // A stored window resolution may be larger than the user's current monitor.
        int initialWidth = videoMode == null ? settings.resolutionWidth()
                : Math.min(settings.resolutionWidth(), videoMode.width());
        int initialHeight = videoMode == null ? settings.resolutionHeight()
                : Math.min(settings.resolutionHeight(), videoMode.height());
        window = glfwCreateWindow(initialWidth, initialHeight, "Wildforge", 0, 0);
        if (window == 0) {
            throw new IllegalStateException("Failed to create GLFW window");
        }

        if (videoMode != null) {
            glfwSetWindowPos(window,
                    Math.max(0, (videoMode.width() - initialWidth) / 2),
                    Math.max(0, (videoMode.height() - initialHeight) / 2));
        }
        displayController.rememberWindowed(window, initialWidth, initialHeight);

        glfwMakeContextCurrent(window);
        glfwSwapInterval(settings.vsync() ? 1 : 0);
        GL.createCapabilities();
        if (settings.displayMode() != GameSettings.DisplayMode.WINDOWED) {
            displayController.apply(window, settings);
        }

        terrain = new TerrainMesh();
        terrain.setDistances(settings.renderDistance(),settings.simulationDistance());
        player = new Player(terrain, worldItems, gameContent);
        player.setKeyBindings(settings.keyBindings());
        renderer = new Renderer(terrain);
        renderer.setFov(settings.fov());
        renderer.setShowCrosshair(settings.crosshair());
        renderer.setShowFps(settings.showFps());
        renderer.setUiScale(settings.uiScale());
        player.releaseMouse(window);
        refreshSaveSlots();
        screenMode=HudRenderer.ScreenMode.LOADING;
        statusMessage="STARTING WILDFORGE";

        inputController = new InputController(window, this);
        inputController.install();
        glfwSetCharCallback(window,(handle, codepoint) -> {
            if(consoleOpen && screenMode==HudRenderer.ScreenMode.PLAYING) {
                if(skipConsoleTriggerCharacter) {skipConsoleTriggerCharacter=false;return;}
                if (codepoint>=32 && codepoint<127 && consoleInput.length()<128)
                    consoleInput.appendCodePoint(codepoint);
                return;
            }
            if(screenMode==HudRenderer.ScreenMode.PLAYING && player.isInventoryOpen() && player.getGameMode()==GameMode.CREATIVE){
                if(creativeSearch.length()<28 && codepoint>=32 && codepoint<127){creativeSearch.appendCodePoint(codepoint);creativePage=0;}
                return;
            }
            if(screenMode != HudRenderer.ScreenMode.WORLD_CREATE)return;
            if(suppressWorldCreateCharacter){suppressWorldCreateCharacter=false;return;}
            StringBuilder target = editingWorldName ? worldNameEntry : seedEntry;
            if(target.length()<32 && codepoint>=32 && codepoint<127){
                target.appendCodePoint(codepoint);updateSeedText();
            }
        });

        glfwShowWindow(window);
        glfwPollEvents();
        glfwFocusWindow(window);
        bringWindowToFront();
        glfwPollEvents();
        player.releaseMouse(window);

        System.out.println("Wildforge controls:");
        System.out.println("WASD: Move");
        System.out.println("Mouse: Look");
        System.out.println("Space: Jump");
        System.out.println("Left click: Mine block");
        System.out.println("Right click: Place block");
        System.out.println("1-9: Select hotbar slot");
        System.out.println("E: Open/close inventory");
        System.out.println("Inventory: Hold left click and drag items");
        System.out.println("Inventory right click: Take half or place one");
        System.out.println("Escape: Pause/resume");
        System.out.println("F3: Debug screen; / or T: Developer console; /help for commands");
    }

    @Override
    public void onKey(long handle, int key, int scancode, int action, int mods) {
        if(consoleOpen && screenMode==HudRenderer.ScreenMode.PLAYING) {
            if(action==GLFW_PRESS || action==GLFW_REPEAT) {
                if(key==GLFW_KEY_ENTER || key==GLFW_KEY_KP_ENTER) {
                    String command=consoleInput.toString().trim();
                    if(!command.isEmpty()) {
                        consoleFeedback=DeveloperCommands.execute(command,player,terrain,activeWorldCheats);
                        consoleFeedbackNanos=System.nanoTime();
                        consoleHistory.addFirst(command);consoleHistoryCursor=-1;
                        while(consoleHistory.size()>16)consoleHistory.removeLast();
                    }
                    closeConsole();
                } else if(key==GLFW_KEY_ESCAPE) {closeConsole();escapeWasDown=true;}
                else if(key==GLFW_KEY_BACKSPACE && consoleInput.length()>0)
                    consoleInput.deleteCharAt(consoleInput.length()-1);
                else if(key==GLFW_KEY_TAB) {
                    var options=DeveloperCommands.suggestions(consoleInput.toString(),activeWorldCheats);
                    if(!options.isEmpty()){
                        consoleInput.setLength(0);consoleInput.append(options.get(0));
                    }
                } else if(key==GLFW_KEY_UP && !consoleHistory.isEmpty()) {
                    consoleHistoryCursor=Math.min(consoleHistoryCursor+1,consoleHistory.size()-1);
                    consoleInput.setLength(0);
                    consoleInput.append(new java.util.ArrayList<>(consoleHistory).get(consoleHistoryCursor));
                } else if(key==GLFW_KEY_DOWN && consoleHistoryCursor>=0){
                    consoleHistoryCursor--;
                    consoleInput.setLength(0);
                    if(consoleHistoryCursor>=0)
                        consoleInput.append(new java.util.ArrayList<>(consoleHistory).get(consoleHistoryCursor));
                }
            }
            return;
        }
        if(screenMode==HudRenderer.ScreenMode.PLAYING && action==GLFW_PRESS &&
           (key==GLFW_KEY_SLASH || key==GLFW_KEY_T) && !player.isInventoryOpen()) {
            consoleOpen=true;consoleInput.setLength(0);
            // T opens an empty chat input; slash opens command entry with '/' visible.
            if(key==GLFW_KEY_SLASH)consoleInput.append('/');
            skipConsoleTriggerCharacter=true;consoleHistoryCursor=-1;
            // GLFW delivers a character callback after the slash/T keypress.
            player.releaseMouse(window);
            return;
        }
        if (key == GLFW_KEY_F3 && action == GLFW_PRESS
                && screenMode == HudRenderer.ScreenMode.PLAYING) {
            debugOverlay = !debugOverlay;
            renderer.setDebugOverlay(debugOverlay);
            return;
        }
        if(screenMode == HudRenderer.ScreenMode.WORLD_CREATE){
            if(action == GLFW_PRESS || action == GLFW_REPEAT){
                if(key==GLFW_KEY_BACKSPACE){StringBuilder entry=editingWorldName?worldNameEntry:seedEntry;if(entry.length()>0){entry.deleteCharAt(entry.length()-1);updateSeedText();}}
                else if(key==GLFW_KEY_ESCAPE){screenMode=HudRenderer.ScreenMode.SAVE_SELECT;statusMessage="";escapeWasDown=true;}
                else if(key==GLFW_KEY_TAB){editingWorldName=!editingWorldName;}
                else if(key==GLFW_KEY_F5){
                    seedEntry.setLength(0);
                    seedEntry.append(new java.security.SecureRandom().nextLong());
                    editingWorldName=false;updateSeedText();
                }
                
                
                else if(key==GLFW_KEY_ENTER || key==GLFW_KEY_KP_ENTER){createWorldFromMenu();}
            }
            return;
        }
        if(screenMode==HudRenderer.ScreenMode.PLAYING && player.isInventoryOpen() && player.getGameMode()==GameMode.CREATIVE) {
            if(action==GLFW_PRESS || action==GLFW_REPEAT) {
                if(key==GLFW_KEY_BACKSPACE && creativeSearch.length()>0) {creativeSearch.deleteCharAt(creativeSearch.length()-1);creativePage=0;}
                else if(key==GLFW_KEY_TAB) {creativeCategory=(creativeCategory+1)%com.wildforge.ui.CreativeCatalog.CATEGORY_COUNT;creativePage=0;}
                else if(key==GLFW_KEY_PAGE_DOWN || key==GLFW_KEY_RIGHT) creativePage=Math.min(creativeMaxPage(),creativePage+1);
                else if(key==GLFW_KEY_PAGE_UP || key==GLFW_KEY_LEFT) creativePage=Math.max(0,creativePage-1);
            }
            return;
        }
        if (bindingToCapture == null || screenMode != HudRenderer.ScreenMode.KEYBINDS
                || action != GLFW_PRESS) return;
        if (key == GLFW_KEY_ESCAPE) {
            bindingToCapture = null;
            statusMessage = "BINDING CANCELLED";
            escapeWasDown = true;
            return;
        }
        if (settings.keyBindings().bind(bindingToCapture, key)) {
            String success = bindingToCapture.label() + " SET TO " + KeyBindings.keyName(key);
            bindingToCapture = null;
            persistSettings();
            statusMessage = success;
        } else {
            KeyBindings.Action existing = settings.keyBindings().conflict(bindingToCapture, key);
            statusMessage = existing == null ? "KEY NOT AVAILABLE" : "KEY USED BY " + existing.label();
        }
    }

    @Override
    public void onCursorPosition(long handle, double x, double y) {
        mouseX = x;
        mouseY = y;
        if (renderDistanceSliderDragging && screenMode == HudRenderer.ScreenMode.OPTIONS) updateRenderDistanceSliderFromCursor(handle);
        if (fovSliderDragging && screenMode == HudRenderer.ScreenMode.OPTIONS) {
            updateFovSliderFromCursor(handle);
        }
        if (uiScaleSliderDragging && screenMode == HudRenderer.ScreenMode.OPTIONS) {
            updateUiScaleSliderFromCursor(handle);
        }
        if (glfwGetWindowAttrib(handle, GLFW_FOCUSED) == GLFW_TRUE
                && screenMode == HudRenderer.ScreenMode.PLAYING) {
            player.onMouseMove(x, y);
        }
    }

    @Override
    public void onScroll(long handle, double xOffset, double yOffset) {
        if(screenMode==HudRenderer.ScreenMode.PLAYING && player.isInventoryOpen() && player.getGameMode()==GameMode.CREATIVE){
            creativePage=Math.max(0,Math.min(creativeMaxPage(),creativePage+(yOffset<0?1:-1)));return;
        }
        if (screenMode == HudRenderer.ScreenMode.PLAYING
                && !player.isInventoryOpen()
                && player.isMouseCaptured()) {
            player.scrollHotbar(yOffset);
        }
    }

    @Override
    public void onWindowFocusChanged(long handle, boolean focused) {
        if (focused) {
            // Inventory is a cursor-driven screen even when ScreenMode is PLAYING.
            // Returning from another monitor must not grab the pointer again.
            if (screenMode == HudRenderer.ScreenMode.PLAYING && !player.isInventoryOpen() && !consoleOpen) {
                player.captureMouse(handle);
            } else {
                player.releaseMouse(handle);
            }
        } else {
            if (fovSliderDragging || uiScaleSliderDragging || renderDistanceSliderDragging) {
                fovSliderDragging = false;
                renderDistanceSliderDragging = false;
                uiScaleSliderDragging = false;
                uiScaleDragTrack = null;
                persistSettings();
            }
            cancelInventoryDrag();
            player.releaseMouse(handle);
        }
    }

    @Override
    public void onWindowCloseRequested(long handle) {
        if (screenMode == HudRenderer.ScreenMode.SAVING) {
            glfwSetWindowShouldClose(handle, false);
            return;
        }

        if (worldLoaded) {
            glfwSetWindowShouldClose(handle, false);
            beginSaveAndQuit();
        } else {
            glfwSetWindowShouldClose(handle, true);
        }
    }

    @Override
    public void onMouseButton(long handle, int button, int action, int mods) {
        if (glfwGetWindowAttrib(handle, GLFW_FOCUSED) != GLFW_TRUE) {
            return;
        }
        if (button != GLFW_MOUSE_BUTTON_LEFT && button != GLFW_MOUSE_BUTTON_RIGHT) {
            return;
        }

        if (button == GLFW_MOUSE_BUTTON_LEFT && action == GLFW_RELEASE
                && (fovSliderDragging || uiScaleSliderDragging || renderDistanceSliderDragging)) {
            if (renderDistanceSliderDragging) updateRenderDistanceSliderFromCursor(handle);
            if (fovSliderDragging) {
                updateFovSliderFromCursor(handle);
            }
            if (uiScaleSliderDragging) {
                updateUiScaleSliderFromCursor(handle);
            }
            fovSliderDragging = false;
                renderDistanceSliderDragging = false;
            uiScaleSliderDragging = false;
            uiScaleDragTrack = null;
            persistSettings();
            return;
        }

        if (screenMode == HudRenderer.ScreenMode.WORLD_CREATE && button==GLFW_MOUSE_BUTTON_LEFT && action==GLFW_PRESS) {
            glfwGetWindowSize(handle,windowWidth,windowHeight);
            if(windowWidth[0]>0 && windowHeight[0]>0){
                double nx=mouseX/windowWidth[0],ny=mouseY/windowHeight[0];
                if(nx>0.25 && nx<0.75){
                    if(ny>0.35 && ny<0.46){editingWorldName=true;return;}
                    if(ny>0.48 && ny<0.60){editingWorldName=false;return;}
                    if(ny>0.605 && ny<0.655){selectedNewGameMode=GameMode.values()[(selectedNewGameMode.ordinal()+1)%GameMode.values().length];return;}
                    if(ny>=0.655 && ny<0.715){selectedNewCheats=!selectedNewCheats;return;}
                    if(ny>0.73 && ny<0.84){
                        if(nx<0.5){createWorldFromMenu();}else{screenMode=HudRenderer.ScreenMode.SAVE_SELECT;statusMessage="";}
                        return;
                    }
                }
            }
            return;
        }
        if (screenMode == HudRenderer.ScreenMode.PLAYING && player.isInventoryOpen()) {
            if(player.getGameMode()==GameMode.CREATIVE) { handleCreativeMouseButton(handle,button,action); return; }
            handleInventoryMouseButton(handle, button, action);
            return;
        }

        if (screenMode == HudRenderer.ScreenMode.MAIN_MENU
                || screenMode == HudRenderer.ScreenMode.SAVE_SELECT
                || screenMode == HudRenderer.ScreenMode.OPTIONS
                || screenMode == HudRenderer.ScreenMode.KEYBINDS
                || screenMode == HudRenderer.ScreenMode.DELETE_CONFIRM
                || screenMode == HudRenderer.ScreenMode.PAUSED) {
            if (button == GLFW_MOUSE_BUTTON_LEFT && action == GLFW_PRESS) {
                HudRenderer.MenuButton clicked = getMenuButtonAt(handle);
                if (screenMode == HudRenderer.ScreenMode.OPTIONS
                        && clicked == HudRenderer.MenuButton.FOV) {
                    if (updateFovSliderFromCursor(handle)) {
                        fovSliderDragging = true;
                    }
                } else if (screenMode == HudRenderer.ScreenMode.OPTIONS
                        && clicked == HudRenderer.MenuButton.RENDER_DISTANCE) {
                    if(updateRenderDistanceSliderFromCursor(handle))renderDistanceSliderDragging=true;
                } else if (screenMode == HudRenderer.ScreenMode.OPTIONS
                        && clicked == HudRenderer.MenuButton.UI_SCALE) {
                    glfwGetFramebufferSize(handle, framebufferWidth, framebufferHeight);
                    uiScaleDragTrack = renderer.uiScaleSliderTrack(
                            framebufferWidth[0], framebufferHeight[0], optionsPage);
                    uiScaleSliderDragging = uiScaleDragTrack != null;
                    if (uiScaleSliderDragging) {
                        updateUiScaleSliderFromCursor(handle);
                    }
                } else {
                    handleMenuClick(handle);
                }
            }
            return;
        }

        if (screenMode != HudRenderer.ScreenMode.PLAYING
                || action != GLFW_PRESS
                || !player.isMouseCaptured()) {
            return;
        }

        if (button == GLFW_MOUSE_BUTTON_LEFT) {
            player.mineBlock(terrain);
        } else if (button == GLFW_MOUSE_BUTTON_RIGHT) {
            player.placeBlock(terrain);
        }
    }

    private void closeConsole() {
        consoleOpen=false;
        skipConsoleTriggerCharacter=false;
        if(screenMode==HudRenderer.ScreenMode.PLAYING && !player.isInventoryOpen())
            player.captureMouse(window);
    }
    private void handleEscapeKey() {
        boolean escapeDown = glfwGetKey(window, GLFW_KEY_ESCAPE) == GLFW_PRESS;

        if (escapeDown && !escapeWasDown) {
            if(consoleOpen) { closeConsole(); escapeWasDown=true; return; }
            if (screenMode == HudRenderer.ScreenMode.PLAYING) {
                if (player.isInventoryOpen()) {
                    player.setInventoryOpen(false);
                    cancelInventoryDrag();creativeDragging=null;
                } else {
                    screenMode = HudRenderer.ScreenMode.PAUSED;
                    player.releaseMouse(window);
                }
            } else if (screenMode == HudRenderer.ScreenMode.PAUSED) {
                resumeGame();
            } else if (screenMode == HudRenderer.ScreenMode.WORLD_CREATE) {
                screenMode = HudRenderer.ScreenMode.SAVE_SELECT;
                statusMessage = "";
            } else if (screenMode == HudRenderer.ScreenMode.SAVE_SELECT) {
                screenMode = HudRenderer.ScreenMode.MAIN_MENU;
                statusMessage = "";
            } else if (screenMode == HudRenderer.ScreenMode.OPTIONS) {
                closeOptions();
            } else if (screenMode == HudRenderer.ScreenMode.KEYBINDS) {
                if (bindingToCapture != null) {
                    bindingToCapture = null;
                    statusMessage = "BINDING CANCELLED";
                } else {
                    screenMode = HudRenderer.ScreenMode.OPTIONS;
                    optionsPage = 1;
                    statusMessage = "";
                }
            } else if (screenMode == HudRenderer.ScreenMode.DELETE_CONFIRM) {
                cancelDelete();
            }
        }
        escapeWasDown = escapeDown;
    }

    private void handleMenuClick(long handle) {
        HudRenderer.MenuButton button = getMenuButtonAt(handle);

        if (screenMode == HudRenderer.ScreenMode.MAIN_MENU) {
            if (button == HudRenderer.MenuButton.PLAY) {
                refreshSaveSlots();
                saveSelection.goToFirstPage();
                screenMode = HudRenderer.ScreenMode.SAVE_SELECT;
                statusMessage = "";
            } else if (button == HudRenderer.MenuButton.OPTIONS) {
                openOptions();
            } else if (button == HudRenderer.MenuButton.QUIT) {
                glfwSetWindowShouldClose(handle, true);
            }
            return;
        }

        if (screenMode == HudRenderer.ScreenMode.SAVE_SELECT) {
            switch (button) {
                case SAVE_ONE -> beginLoadingVisibleSave(0);
                case SAVE_TWO -> beginLoadingVisibleSave(1);
                case SAVE_THREE -> beginLoadingVisibleSave(2);
                case DELETE_ONE -> confirmDeleteVisibleSave(0);
                case DELETE_TWO -> confirmDeleteVisibleSave(1);
                case DELETE_THREE -> confirmDeleteVisibleSave(2);
                case NEW_GAME -> {
                    seedEntry.setLength(0);worldNameEntry.setLength(0);worldNameEntry.append("New World");editingWorldName=false;selectedNewGameMode=GameMode.SURVIVAL;selectedNewCheats=false;updateSeedText();
                    screenMode=HudRenderer.ScreenMode.WORLD_CREATE;
                }
                case PREVIOUS_PAGE -> {
                    saveSelection.previousPage();
                }
                case NEXT_PAGE -> {
                    saveSelection.nextPage();
                }
                case BACK -> {
                    screenMode = HudRenderer.ScreenMode.MAIN_MENU;
                    statusMessage = "";
                }
                default -> {
                }
            }
            return;
        }

        if (screenMode == HudRenderer.ScreenMode.PAUSED) {
            if (button == HudRenderer.MenuButton.CONTINUE) {
                resumeGame();
            } else if (button == HudRenderer.MenuButton.OPTIONS) {
                openOptions();
            } else if (button == HudRenderer.MenuButton.QUIT) {
                beginSaveAndReturnToMainMenu();
            }
            return;
        }

        if (screenMode == HudRenderer.ScreenMode.OPTIONS) {
            switch (button) {
                case VIDEO_TAB -> optionsPage = 0;
                case KEYBINDS_TAB -> {
                    optionsPage = 2;
                    screenMode = HudRenderer.ScreenMode.KEYBINDS;
                    statusMessage = "";
                }
                case CONTROLS_TAB -> optionsPage = 1;
                case DISPLAY_MODE -> {
                    settings.nextDisplayMode();
                    displayController.apply(window, settings);
                    persistSettings();
                }
                case RESOLUTION -> {
                    if (settings.allowsResolutionChange()) {
                        GLFWVidMode mode = glfwGetVideoMode(displayController.monitorForWindow(window));
                        if (mode != null) {
                            settings.nextResolution(mode.width(), mode.height());
                            displayController.apply(window, settings);
                            persistSettings();
                        }
                    }
                }
                case VSYNC -> {
                    settings.toggleVsync();
                    glfwSwapInterval(settings.vsync() ? 1 : 0);
                    persistSettings();
                }
                case FPS_LIMIT -> {
                    settings.nextFpsLimit();
                    persistSettings();
                }
                case RENDER_DISTANCE -> {
                    int next = settings.renderDistance() >= 24 ? 2 : settings.renderDistance() + 1;
                    settings.setRenderDistance(next);
                    terrain.setDistances(settings.renderDistance(), settings.simulationDistance());
                    persistSettings();
                }
                case CROSSHAIR -> {
                    settings.toggleCrosshair();
                    renderer.setShowCrosshair(settings.crosshair());
                    persistSettings();
                }
                case SHOW_FPS -> {
                    settings.toggleShowFps();
                    renderer.setShowFps(settings.showFps());
                    persistSettings();
                }
                case BACK -> closeOptions();
                default -> { }
            }
            return;
        }

        if (screenMode == HudRenderer.ScreenMode.KEYBINDS) {
            if (bindingToCapture != null) return;
            switch (button) {
                case BIND_1, BIND_2, BIND_3, BIND_4, BIND_5, BIND_6 -> {
                    int index = (optionsPage - 2) * 6 + (button.ordinal() - HudRenderer.MenuButton.BIND_1.ordinal());
                    bindingToCapture = KeyBindings.Action.at(index);
                    if (bindingToCapture != null) statusMessage = "PRESS A KEY OR ESC TO CANCEL";
                }
                case BIND_PREVIOUS -> { if (optionsPage > 2) optionsPage--; statusMessage = ""; }
                case BIND_NEXT -> { if (optionsPage < 4) optionsPage++; statusMessage = ""; }
                case RESET_BINDS -> {
                    settings.keyBindings().resetDefaults();
                    persistSettings();
                    statusMessage = "DEFAULT KEYBINDINGS RESTORED";
                }
                case BACK -> {
                    screenMode = HudRenderer.ScreenMode.OPTIONS;
                    optionsPage = 1;
                    statusMessage = "";
                }
                default -> { }
            }
            return;
        }

        if (screenMode == HudRenderer.ScreenMode.DELETE_CONFIRM) {
            if (button == HudRenderer.MenuButton.CONFIRM_DELETE) {
                deleteConfirmedWorld();
            } else if (button == HudRenderer.MenuButton.CANCEL_DELETE) {
                cancelDelete();
            }
        }
    }

    private void openOptions() {
        optionsReturnScreen = screenMode;
        optionsPage = 0;
        screenMode = HudRenderer.ScreenMode.OPTIONS;
        statusMessage = "";
        player.releaseMouse(window);
    }

    private void closeOptions() {
        if (fovSliderDragging || uiScaleSliderDragging || renderDistanceSliderDragging) {
            fovSliderDragging = false;
                renderDistanceSliderDragging = false;
            uiScaleSliderDragging = false;
            uiScaleDragTrack = null;
            persistSettings();
        }
        bindingToCapture = null;
        screenMode = optionsReturnScreen;
        statusMessage = "";
    }

    /** Changes the FOV while dragging, without writing settings on every mouse event. */
    private boolean updateFovSliderFromCursor(long handle) {
        glfwGetWindowSize(handle, windowWidth, windowHeight);
        glfwGetFramebufferSize(handle, framebufferWidth, framebufferHeight);
        if (windowWidth[0] <= 0 || windowHeight[0] <= 0
                || framebufferWidth[0] <= 0 || framebufferHeight[0] <= 0) {
            return false;
        }
        float x = (float) (mouseX * framebufferWidth[0] / windowWidth[0]);
        float y = (float) (mouseY * framebufferHeight[0] / windowHeight[0]);
        int value = renderer.fovSliderValueAt(x, y,
                framebufferWidth[0], framebufferHeight[0], optionsPage);
        if (value < 0) {
            // While dragging, clamp off-track X positions against the slider endpoints.
            if (!fovSliderDragging) return false;
            value = renderer.fovSliderValueClamped(x,
                    framebufferWidth[0], framebufferHeight[0], optionsPage);
        }
        if (value >= 0 && value != settings.fov()) {
            settings.setFov(value);
            renderer.setFov(settings.fov());
        }
        return value >= 0;
    }

    private boolean updateRenderDistanceSliderFromCursor(long handle){
        glfwGetWindowSize(handle,windowWidth,windowHeight);
        glfwGetFramebufferSize(handle,framebufferWidth,framebufferHeight);
        if(windowWidth[0]<=0||windowHeight[0]<=0||framebufferWidth[0]<=0||framebufferHeight[0]<=0)return false;
        float x=(float)(mouseX*framebufferWidth[0]/windowWidth[0]);
        float y=(float)(mouseY*framebufferHeight[0]/windowHeight[0]);
        int value=renderer.renderDistanceSliderValueAt(x,y,framebufferWidth[0],framebufferHeight[0],optionsPage);
        if(value<0){if(!renderDistanceSliderDragging)return false;
            value=renderer.renderDistanceSliderValueClamped(x,framebufferWidth[0],framebufferHeight[0],optionsPage);}
        if(value>=0&&value!=settings.renderDistance()){
            settings.setRenderDistance(value);
            terrain.setDistances(settings.renderDistance(),settings.simulationDistance());
        }
        return value>=0;
    }

    /** Update the global scale using the track captured at mouse-down. */
    private void updateUiScaleSliderFromCursor(long handle) {
        if (!uiScaleSliderDragging || uiScaleDragTrack == null || uiScaleDragTrack[2] <= 0f) {
            return;
        }
        glfwGetWindowSize(handle, windowWidth, windowHeight);
        glfwGetFramebufferSize(handle, framebufferWidth, framebufferHeight);
        if (windowWidth[0] <= 0 || framebufferWidth[0] <= 0) {
            return;
        }
        float x = (float) (mouseX * framebufferWidth[0] / windowWidth[0]);
        float ratio = Math.max(0f, Math.min(1f,
                (x - uiScaleDragTrack[0]) / uiScaleDragTrack[2]));
        int range = GameSettings.MAX_UI_SCALE - GameSettings.MIN_UI_SCALE;
        int steps = Math.round(ratio * range / GameSettings.UI_SCALE_STEP);
        int percent = GameSettings.MIN_UI_SCALE + steps * GameSettings.UI_SCALE_STEP;
        if (percent != settings.uiScalePercent()) {
            settings.setUiScalePercent(percent);
            renderer.setUiScale(settings.uiScale());
        }
    }

    private void persistSettings() {
        try {
            settings.save();
            statusMessage = "";
        } catch (IOException ex) {
            System.err.println("Could not save settings: " + ex.getMessage());
            statusMessage = "SETTINGS SAVE FAILED";
        }
    }

    private void confirmDeleteVisibleSave(int index) {
        SaveManager.SaveSlotSummary summary = saveSelection.visibleAt(index);
        if (summary == null || !summary.exists()) return;
        pendingDeleteSlot = summary.slot();
        pendingDeleteLabel = summary.displayName();
        screenMode = HudRenderer.ScreenMode.DELETE_CONFIRM;
        statusMessage = "";
    }

    private void cancelDelete() {
        pendingDeleteSlot = -1;
        pendingDeleteLabel = "";
        screenMode = HudRenderer.ScreenMode.SAVE_SELECT;
        statusMessage = "";
    }

    private void deleteConfirmedWorld() {
        if (pendingDeleteSlot < 0) {
            cancelDelete();
            return;
        }
        try {
            saveManager.deleteWorld(pendingDeleteSlot);
            cancelDelete();
            refreshSaveSlots();
            statusMessage = "WORLD DELETED";
        } catch (IOException ex) {
            System.err.println("Could not delete world: " + ex.getMessage());
            cancelDelete();
            refreshSaveSlots();
            statusMessage = "DELETE FAILED";
        }
    }

    private void beginLoadingVisibleSave(int localIndex) {
        SaveManager.SaveSlotSummary summary = saveSelection.visibleAt(localIndex);
        if (summary != null) {
            beginLoadingSave(summary.slot());
        }
    }

    private void beginLoadingSave(int slot) {
        if (slot < 0) {
            return;
        }
        activeSaveSlot = slot;
        screenMode = HudRenderer.ScreenMode.LOADING;
        statusMessage = "";
        loadPending = true;
    }

    private void refreshSaveSlots() {
        saveSelection.refresh();
    }

    private HudRenderer.MenuButton getMenuButtonAt(long handle) {
        glfwGetWindowSize(handle, windowWidth, windowHeight);
        glfwGetFramebufferSize(handle, framebufferWidth, framebufferHeight);

        if (windowWidth[0] <= 0 || windowHeight[0] <= 0
                || framebufferWidth[0] <= 0 || framebufferHeight[0] <= 0) {
            return HudRenderer.MenuButton.NONE;
        }

        float x = (float) (mouseX * framebufferWidth[0] / windowWidth[0]);
        float y = (float) (mouseY * framebufferHeight[0] / windowHeight[0]);
        return renderer.menuButtonAt(x, y, framebufferWidth[0],
                framebufferHeight[0], screenMode, optionsPage);
    }

    private void resumeGame() {
        screenMode = HudRenderer.ScreenMode.PLAYING;
        statusMessage = "";
        if(!consoleOpen) player.captureMouse(window);
    }

    private void beginSaveAndQuit() {
        if (!worldLoaded) {
            glfwSetWindowShouldClose(window, true);
            return;
        }
        beginSaving(true, false);
    }

    private void beginSaveAndReturnToMainMenu() {
        if (!worldLoaded) {
            screenMode = HudRenderer.ScreenMode.MAIN_MENU;
            return;
        }
        beginSaving(false, true);
    }

    private void beginSaving(boolean closeAfterSave, boolean returnToMainMenuAfterSave) {
        cancelInventoryDrag();creativeDragging=null;
        player.releaseMouse(window);
        screenMode = HudRenderer.ScreenMode.SAVING;
        statusMessage = "";
        quitAfterSave = closeAfterSave;
        this.returnToMainMenuAfterSave = returnToMainMenuAfterSave;
        savingScreenStartedAtNanos = System.nanoTime();
        savePending = true;
    }

    private void loadWorld() {
        loadPending = false;
        consoleOpen=false; consoleFeedback=""; consoleInput.setLength(0);
        try {
            if(requestedNewSeed != null){
                terrain.newWorld(requestedNewSeed);
                requestedNewSeed=null;
            } else saveManager.loadTerrain(activeSaveSlot, terrain);
            worldItems.clear();
            player = new Player(terrain, worldItems, gameContent);
            player.setKeyBindings(settings.keyBindings());
            saveManager.loadPlayer(activeSaveSlot, player, terrain);
            if(pendingCreateGameMode!=null){
                player.setGameMode(pendingCreateGameMode);pendingCreateGameMode=null;
                activeWorldCheats=pendingNewCheats;
                saveManager.saveCheatsEnabled(activeSaveSlot,activeWorldCheats);
            } else activeWorldCheats=saveManager.cheatsEnabled(activeSaveSlot);
            terrain.streamAround(player.getX(),player.getZ());
            worldLoaded = true;
            preloadingWorld = true;
            statusMessage = "PREPARING NEARBY CHUNKS";
            // Keep the LOADING screen visible during initial chunk generation.

        } catch (IOException e) {
            System.err.println("Could not load world: " + e.getMessage());
            statusMessage = "LOAD FAILED";
            screenMode = HudRenderer.ScreenMode.SAVE_SELECT;
            if (player != null) {
                player.releaseMouse(window);
            }
            refreshSaveSlots();
        }
    }

    private void saveWorld() {
        savePending = false;
        try {
            if (!saveManager.isValidSlot(activeSaveSlot)) {
                throw new IOException("No save slot is selected");
            }
            saveManager.save(activeSaveSlot, terrain, player);
            worldLoaded = true;
            if(newWorldNamePending!=null){saveManager.saveWorldName(activeSaveSlot,newWorldNamePending);newWorldNamePending=null;}
            refreshSaveSlots();

            if (quitAfterSave) {
                glfwSetWindowShouldClose(window, true);
            } else if (returnToMainMenuAfterSave) {
                screenMode = HudRenderer.ScreenMode.MAIN_MENU;
                statusMessage = "";
                worldLoaded = false;
                activeSaveSlot = -1;
                returnToMainMenuAfterSave = false;
            } else {
                screenMode = HudRenderer.ScreenMode.PAUSED;
            }
        } catch (IOException e) {
            System.err.println("Could not save world: " + e.getMessage());
            statusMessage = player != null && player.hasCursorStack()
                    ? "PUT HELD ITEMS INTO A SLOT BEFORE SAVING" : "SAVE FAILED";
            screenMode = HudRenderer.ScreenMode.PAUSED;
            quitAfterSave = false;
            returnToMainMenuAfterSave = false;
        }
    }

    private int creativeMaxPage() {
        return Math.max(0,(com.wildforge.ui.CreativeCatalog.items(creativeSearch.toString(),creativeCategory).size()-1)/36);
    }
    private void handleCreativeMouseButton(long handle, int button, int action) {
        glfwGetWindowSize(handle,windowWidth,windowHeight);
        glfwGetFramebufferSize(handle,framebufferWidth,framebufferHeight);
        if(windowWidth[0]<=0 || windowHeight[0]<=0)return;
        float px=(float)(mouseX*framebufferWidth[0]/windowWidth[0]);
        float py=(float)(mouseY*framebufferHeight[0]/windowHeight[0]);
        int hit=renderer.creativeInventoryHit(px,py,framebufferWidth[0],framebufferHeight[0]);
        if(button==GLFW_MOUSE_BUTTON_LEFT && action==GLFW_RELEASE){
            if(creativeDragging!=null){
                int hotbar=hit<=-100 && hit>=-108 ? -100-hit : player.getSelectedSlot();
                player.selectHotbarSlot(hotbar);
                player.creativeSelect(creativeDragging,creativeDraggingCount);
                creativeDragging=null;
            }
            return;
        }
        if(action!=GLFW_PRESS)return;
        if(hit==-2)creativePage=Math.max(0,creativePage-1);
        else if(hit==-3)creativePage=Math.min(creativeMaxPage(),creativePage+1);
        else if(hit<=-10 && hit>-10-com.wildforge.ui.CreativeCatalog.CATEGORY_COUNT){
            creativeCategory=-10-hit;creativePage=0;
        } else if(hit<=-100 && hit>=-108){
            player.selectHotbarSlot(-100-hit);
        } else if(hit>=0){
            var items=com.wildforge.ui.CreativeCatalog.items(creativeSearch.toString(),creativeCategory);
            creativePage=Math.min(creativePage,Math.max(0,(items.size()-1)/36));
            int at=creativePage*36+hit;
            if(at<items.size()){
                if(button==GLFW_MOUSE_BUTTON_RIGHT)player.creativeSelect(items.get(at),1);
                else if(button==GLFW_MOUSE_BUTTON_LEFT){
                    creativeDragging=items.get(at);
                    creativeDraggingCount=Math.min(64,creativeDragging.getMaxStackSize());
                }
            }
        }
    }

    private void handleInventoryMouseButton(long handle, int button, int action) {
        if (button == GLFW_MOUSE_BUTTON_LEFT) {
            if (action == GLFW_PRESS) {
                beginInventoryDrag(handle);
            } else if (action == GLFW_RELEASE) {
                finishInventoryDrag(handle);
            }
        } else if (button == GLFW_MOUSE_BUTTON_RIGHT && action == GLFW_PRESS) {
            int slot = getInventorySlotAtMouse(handle);
            if (slot >= 0) {
                player.clickInventorySlot(slot, true);
            }
        }
    }

    private void beginInventoryDrag(long handle) {
        if (inventoryDragActive) {
            return;
        }
        int slot = getInventorySlotAtMouse(handle);
        if (slot < 0) {
            return;
        }

        if (player.getCursorStack() == null) {
            if (player.getInventoryStack(slot) == null) {
                return;
            }
            player.clickInventorySlot(slot, false);
            if (player.getCursorStack() != null) {
                inventoryDragActive = true;
                inventoryDragSourceSlot = slot;
            }
        } else {
            inventoryDragActive = true;
            inventoryDragSourceSlot = -1;
        }
    }

    private void finishInventoryDrag(long handle) {
        if (!inventoryDragActive) {
            return;
        }
        int targetSlot = getInventorySlotAtMouse(handle);
        if (targetSlot >= 0) {
            player.clickInventorySlot(targetSlot, false);
        } else if (inventoryDragSourceSlot >= 0) {
            player.clickInventorySlot(inventoryDragSourceSlot, false);
        }
        inventoryDragActive = false;
        inventoryDragSourceSlot = -1;
    }

    private void cancelInventoryDrag() {
        if (player != null && player.getCursorStack() != null) {
            player.returnCursorStackToInventory();
        }
        inventoryDragActive = false;
        inventoryDragSourceSlot = -1;
    }

    private int getInventorySlotAtMouse(long handle) {
        glfwGetWindowSize(handle, windowWidth, windowHeight);
        glfwGetFramebufferSize(handle, framebufferWidth, framebufferHeight);
        if (windowWidth[0] <= 0 || windowHeight[0] <= 0
                || framebufferWidth[0] <= 0 || framebufferHeight[0] <= 0) {
            return -1;
        }

        float x = (float) (mouseX * framebufferWidth[0] / windowWidth[0]);
        float y = (float) (mouseY * framebufferHeight[0] / windowHeight[0]);
        return inventorySlotAt(x, y, framebufferWidth[0], framebufferHeight[0]);
    }

    private int inventorySlotAt(float mouseX, float mouseY, int width, int height) {
        return HudRenderer.inventorySlotAt(mouseX, mouseY, width, height);
    }

    private void bringWindowToFront() {
        try {
            long nativeWindow = GLFWNativeWin32.glfwGetWin32Window(window);
            HWND hwnd = new HWND(new Pointer(nativeWindow));
            User32.INSTANCE.ShowWindow(hwnd, 5);
            User32.INSTANCE.BringWindowToTop(hwnd);
            User32.INSTANCE.SetForegroundWindow(hwnd);
        } catch (Throwable ignored) {
            // GLFW focus remains the portable best-effort fallback.
        }
    }

    private void loop() {
        double lastFrameTime = glfwGetTime();

        while (!glfwWindowShouldClose(window)) {
            long frameStartNanos = System.nanoTime();
            double now = glfwGetTime();
            float deltaTime = (float) (now - lastFrameTime);
            lastFrameTime = now;
            deltaTime = Math.max(0.0f, Math.min(deltaTime, MAX_FRAME_DELTA_SECONDS));

            glfwPollEvents();
            if(focusRetryPending && launchSplashFrames==0){
                focusRetryPending=false;
                glfwShowWindow(window);glfwRestoreWindow(window);glfwFocusWindow(window);
                bringWindowToFront();
            }
            handleEscapeKey();

            if (screenMode == HudRenderer.ScreenMode.PLAYING) {
                terrain.setDistances(settings.renderDistance(),settings.simulationDistance());
                terrain.streamAround(player.getX(), player.getZ());
                terrain.tickFluids(deltaTime); // fixed 20 Hz fluid simulation, independent of FPS
                player.update(window, terrain, deltaTime);
                if (screenMode == HudRenderer.ScreenMode.PLAYING) {
                    worldItems.update(deltaTime, player, terrain);
                }
            }

            glfwGetFramebufferSize(window, framebufferWidth, framebufferHeight);
            glfwGetWindowSize(window, windowWidth, windowHeight);

            renderer.render(
                    framebufferWidth[0],
                    framebufferHeight[0],
                    player.getCamera(),
                    player,
                    screenMode,
                    !worldLoaded
            );

            float hudMouseX = (float) mouseX;
            float hudMouseY = (float) mouseY;
            if (windowWidth[0] > 0 && windowHeight[0] > 0) {
                hudMouseX *= (float) framebufferWidth[0] / windowWidth[0];
                hudMouseY *= (float) framebufferHeight[0] / windowHeight[0];
            }

            renderer.setDeveloperOverlay(player,terrain,
                    consoleOpen,consoleInput.toString(),
                    System.nanoTime()-consoleFeedbackNanos<8_000_000_000L?consoleFeedback:"");
            renderer.setCreativeInventoryState(creativeSearch.toString(),creativePage,creativeCategory);
            if(!player.isInventoryOpen())creativeDragging=null;
            renderer.setCreativeDragItem(creativeDragging,creativeDraggingCount);
            renderer.setConsoleSuggestions(consoleOpen?
                DeveloperCommands.suggestions(consoleInput.toString(),activeWorldCheats):java.util.List.of());
            renderer.renderUi(
                    framebufferWidth[0],
                    framebufferHeight[0],
                    player,
                    hudMouseX,
                    hudMouseY,
                    screenMode,
                    statusMessage,
                    saveSelection.visibleLabels(),
                    saveSelection.visibleExists(),
                    saveSelection.page(),
                    saveSelection.hasPreviousPage(),
                    saveSelection.hasNextPage(),
                    settings,
                    pendingDeleteLabel,
                    optionsPage,
                    worldNameEntry.toString(),seedEntry.toString(),editingWorldName,selectedNewGameMode,selectedNewCheats
            );

            glfwSwapBuffers(window);
            frameStats.sample(System.nanoTime());
            int latestFps = frameTiming.recordFrame(System.nanoTime());
            if (latestFps >= 0) renderer.setMeasuredFps(latestFps);
            if (debugOverlay) renderer.setDebugFrameStats(frameStats.averageFps(), frameStats.averageMs(), frameStats.p95Ms(), frameStats.worstMs());
            if (debugOverlay && terrain != null) {
                renderer.setDebugStreaming(terrain.loadedChunkCount(),
                        terrain.pendingGenerationCount(), terrain.renderDistance());
                renderer.setDebugWorkload(terrain.pendingMeshCount(),terrain.pendingLodCount(),terrain.readyLodCount(),terrain.residentBlockPayloadBytes());
            }
            FrameTiming.limitFrameRate(frameStartNanos, settings.fpsLimit());

            if(launchSplashFrames>0){
                launchSplashFrames--;
                if(launchSplashFrames==0 && !loadPending){
                    screenMode=HudRenderer.ScreenMode.MAIN_MENU;statusMessage="";
                }
            }
            if (loadPending) {
                loadWorld();
            }
            if(preloadingWorld){
                terrain.streamAround(player.getX(),player.getZ());
                int loaded=terrain.loadedChunkCount();
                statusMessage="GENERATING TERRAIN " + loaded + " CHUNKS";
                if(loaded>=25){
                    preloadingWorld=false;statusMessage="";
                    screenMode=HudRenderer.ScreenMode.PLAYING;
                    player.captureMouse(window);
                }
            }
            if (savePending
                    && System.nanoTime() - savingScreenStartedAtNanos >= MIN_SAVING_SCREEN_NANOS) {
                saveWorld();
            }
        }
    }
}
