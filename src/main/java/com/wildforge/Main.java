package com.wildforge;

import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef.HWND;
import com.wildforge.content.ContentLoader;
import com.wildforge.core.GameSettings;
import com.wildforge.core.InputController;
import com.wildforge.core.KeyBindings;
import com.wildforge.core.SaveManager;
import com.wildforge.lang.LanguageManager;
import com.wildforge.player.Player;
import com.wildforge.render.HudRenderer;
import com.wildforge.render.Renderer;
import com.wildforge.world.TerrainMesh;
import com.wildforge.world.WorldItemManager;
import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.glfw.GLFWNativeWin32;
import org.lwjgl.glfw.GLFWVidMode;
import org.lwjgl.opengl.GL;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.locks.LockSupport;

import static org.lwjgl.glfw.GLFW.*;

public final class Main implements InputController.Callbacks {
    private static final long MIN_SAVING_SCREEN_NANOS = 400_000_000L;
    private static final float MAX_FRAME_DELTA_SECONDS = 0.1f;
    private static final int SAVES_PER_PAGE = 3;

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
    private boolean uiScaleSliderDragging;
    // Physical track is held constant while the entire UI changes size.
    private float[] uiScaleDragTrack;
    private int measuredFps;
    private int framesThisSecond;
    private long fpsWindowStartedNanos = System.nanoTime();
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
    private boolean savePending;
    private boolean quitAfterSave;
    private boolean returnToMainMenuAfterSave;
    private long savingScreenStartedAtNanos;
    private boolean worldLoaded;
    private int activeSaveSlot = -1;
    private List<SaveManager.SaveSlotSummary> saveSlots = List.of();
    private int savePage = 0;
    private String statusMessage = "";

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

        glfwMakeContextCurrent(window);
        glfwSwapInterval(settings.vsync() ? 1 : 0);
        GL.createCapabilities();
        if (settings.fullscreen()) {
            applyWindowMode();
        }

        terrain = new TerrainMesh();
        player = new Player(terrain, worldItems, gameContent);
        player.setMouseSensitivity(settings.mouseSensitivity());
        player.setKeyBindings(settings.keyBindings());
        renderer = new Renderer(terrain);
        renderer.setFov(settings.fov());
        renderer.setShowCrosshair(settings.crosshair());
        renderer.setShowFps(settings.showFps());
        renderer.setUiScale(settings.uiScale());
        player.releaseMouse(window);
        refreshSaveSlots();

        inputController = new InputController(window, this);
        inputController.install();

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
    }

    @Override
    public void onKey(long handle, int key, int scancode, int action, int mods) {
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
        if (screenMode == HudRenderer.ScreenMode.PLAYING
                && !player.isInventoryOpen()
                && player.isMouseCaptured()) {
            player.scrollHotbar(yOffset);
        }
    }

    @Override
    public void onWindowFocusChanged(long handle, boolean focused) {
        if (focused) {
            if (screenMode == HudRenderer.ScreenMode.PLAYING) {
                player.captureMouse(handle);
            } else {
                player.releaseMouse(handle);
            }
        } else {
            if (fovSliderDragging || uiScaleSliderDragging) {
                fovSliderDragging = false;
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
                && (fovSliderDragging || uiScaleSliderDragging)) {
            if (fovSliderDragging) {
                updateFovSliderFromCursor(handle);
            }
            if (uiScaleSliderDragging) {
                updateUiScaleSliderFromCursor(handle);
            }
            fovSliderDragging = false;
            uiScaleSliderDragging = false;
            uiScaleDragTrack = null;
            persistSettings();
            return;
        }

        if (screenMode == HudRenderer.ScreenMode.PLAYING && player.isInventoryOpen()) {
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

    private void handleEscapeKey() {
        boolean escapeDown = glfwGetKey(window, GLFW_KEY_ESCAPE) == GLFW_PRESS;

        if (escapeDown && !escapeWasDown) {
            if (screenMode == HudRenderer.ScreenMode.PLAYING) {
                if (player.isInventoryOpen()) {
                    player.setInventoryOpen(false);
                    cancelInventoryDrag();
                } else {
                    screenMode = HudRenderer.ScreenMode.PAUSED;
                    player.releaseMouse(window);
                }
            } else if (screenMode == HudRenderer.ScreenMode.PAUSED) {
                resumeGame();
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
                savePage = 0;
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
                case NEW_GAME -> beginLoadingSave(saveManager.nextAvailableSlot());
                case PREVIOUS_PAGE -> {
                    if (hasPreviousSavePage()) {
                        savePage--;
                    }
                }
                case NEXT_PAGE -> {
                    if (hasNextSavePage()) {
                        savePage++;
                    }
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
                case FULLSCREEN -> {
                    settings.toggleFullscreen();
                    applyWindowMode();
                    persistSettings();
                }
                case RESOLUTION -> {
                    GLFWVidMode mode = glfwGetVideoMode(glfwGetPrimaryMonitor());
                    if (mode != null) {
                        settings.nextResolution(mode.width(), mode.height());
                        if (!settings.fullscreen()) applyWindowMode();
                        persistSettings();
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
                case SENSITIVITY -> {
                    settings.nextSensitivity();
                    player.setMouseSensitivity(settings.mouseSensitivity());
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
        if (fovSliderDragging || uiScaleSliderDragging) {
            fovSliderDragging = false;
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

    /** Exclusive monitor fullscreen uses the monitor's current mode. */
    private void applyWindowMode() {
        long monitor = glfwGetPrimaryMonitor();
        GLFWVidMode mode = monitor == 0 ? null : glfwGetVideoMode(monitor);
        if (mode == null) return;
        if (settings.fullscreen()) {
            glfwSetWindowMonitor(window, monitor, 0, 0,
                    mode.width(), mode.height(), mode.refreshRate());
        } else {
            int width = Math.min(settings.resolutionWidth(), mode.width());
            int height = Math.min(settings.resolutionHeight(), mode.height());
            glfwSetWindowMonitor(window, 0L,
                    Math.max(0, (mode.width() - width) / 2),
                    Math.max(0, (mode.height() - height) / 2),
                    width, height, GLFW_DONT_CARE);
        }
        glfwSwapInterval(settings.vsync() ? 1 : 0);
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
        SaveManager.SaveSlotSummary summary = visibleSaveAt(index);
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
        SaveManager.SaveSlotSummary summary = visibleSaveAt(localIndex);
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
        saveSlots = saveManager.listSlots();
        int maxPage = Math.max(0, (saveSlots.size() - 1) / SAVES_PER_PAGE);
        if (savePage > maxPage) {
            savePage = maxPage;
        }
    }

    private SaveManager.SaveSlotSummary visibleSaveAt(int localIndex) {
        int absoluteIndex = savePage * SAVES_PER_PAGE + localIndex;
        if (localIndex < 0 || localIndex >= SAVES_PER_PAGE
                || absoluteIndex < 0 || absoluteIndex >= saveSlots.size()) {
            return null;
        }
        return saveSlots.get(absoluteIndex);
    }

    private String[] visibleSaveLabels() {
        String[] labels = new String[SAVES_PER_PAGE];
        for (int i = 0; i < SAVES_PER_PAGE; i++) {
            SaveManager.SaveSlotSummary summary = visibleSaveAt(i);
            labels[i] = summary == null ? "EMPTY" : summary.displayName();
        }
        return labels;
    }

    private boolean[] visibleSaveExists() {
        boolean[] exists = new boolean[SAVES_PER_PAGE];
        for (int i = 0; i < SAVES_PER_PAGE; i++) {
            SaveManager.SaveSlotSummary summary = visibleSaveAt(i);
            exists[i] = summary != null && summary.exists();
        }
        return exists;
    }

    private boolean hasPreviousSavePage() {
        return savePage > 0;
    }

    private boolean hasNextSavePage() {
        return (savePage + 1) * SAVES_PER_PAGE < saveSlots.size();
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
        player.captureMouse(window);
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
        cancelInventoryDrag();
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
        try {
            saveManager.loadTerrain(activeSaveSlot, terrain);
            worldItems.clear();
            player = new Player(terrain, worldItems, gameContent);
            player.setMouseSensitivity(settings.mouseSensitivity());
        player.setKeyBindings(settings.keyBindings());
            saveManager.loadPlayer(activeSaveSlot, player, terrain);
            worldLoaded = true;
            statusMessage = "";
            screenMode = HudRenderer.ScreenMode.PLAYING;
            player.captureMouse(window);
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
            statusMessage = "SAVE FAILED";
            screenMode = HudRenderer.ScreenMode.PAUSED;
            quitAfterSave = false;
            returnToMainMenuAfterSave = false;
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
            handleEscapeKey();

            if (screenMode == HudRenderer.ScreenMode.PLAYING) {
                player.update(window, terrain, deltaTime);
                if (screenMode == HudRenderer.ScreenMode.PLAYING) {
                    worldItems.update(deltaTime, player);
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

            renderer.renderUi(
                    framebufferWidth[0],
                    framebufferHeight[0],
                    player,
                    hudMouseX,
                    hudMouseY,
                    screenMode,
                    statusMessage,
                    visibleSaveLabels(),
                    visibleSaveExists(),
                    savePage,
                    hasPreviousSavePage(),
                    hasNextSavePage(),
                    settings,
                    pendingDeleteLabel,
                    optionsPage
            );

            glfwSwapBuffers(window);
            trackFps(System.nanoTime());
            limitFrameRate(frameStartNanos);

            if (loadPending) {
                loadWorld();
            }
            if (savePending
                    && System.nanoTime() - savingScreenStartedAtNanos >= MIN_SAVING_SCREEN_NANOS) {
                saveWorld();
            }
        }
    }
    private void trackFps(long now) {
        framesThisSecond++;
        long elapsed = now - fpsWindowStartedNanos;
        if (elapsed >= 1_000_000_000L) {
            measuredFps = Math.round(framesThisSecond * 1_000_000_000f / elapsed);
            renderer.setMeasuredFps(measuredFps);
            framesThisSecond = 0;
            fpsWindowStartedNanos = now;
        }
    }

    /** An optional frame cap independent of VSync. Applies across menus and gameplay. */
    private void limitFrameRate(long frameStartNanos) {
        int cap = settings.fpsLimit();
        if (cap <= 0) return;
        long targetNanos = frameStartNanos + 1_000_000_000L / cap;
        while (true) {
            long remaining = targetNanos - System.nanoTime();
            if (remaining <= 0L) return;
            if (remaining > 1_500_000L) {
                LockSupport.parkNanos(remaining - 600_000L);
            } else if (remaining > 250_000L) {
                LockSupport.parkNanos(remaining / 2L);
            } else {
                Thread.onSpinWait();
            }
        }
    }
}
