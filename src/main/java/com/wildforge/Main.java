package com.wildforge;

import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef.HWND;
import com.wildforge.content.ContentLoader;
import com.wildforge.lang.LanguageManager;
import com.wildforge.core.InputController;
import com.wildforge.core.SaveManager;
import com.wildforge.player.Player;
import com.wildforge.render.HudRenderer;
import com.wildforge.render.Renderer;
import com.wildforge.world.TerrainMesh;
import com.wildforge.world.WorldItemManager;
import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.glfw.GLFWVidMode;
import org.lwjgl.glfw.GLFWNativeWin32;
import org.lwjgl.opengl.GL;

import java.io.IOException;

import static org.lwjgl.glfw.GLFW.*;

public final class Main implements InputController.Callbacks {
    private static final int WINDOW_WIDTH = 1600;
    private static final int WINDOW_HEIGHT = 900;

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

    // GLFW writes dimensions into caller-provided arrays. Reuse them rather
    // than allocating four short-lived arrays every frame or mouse click.
    private final int[] framebufferWidth = new int[1];
    private final int[] framebufferHeight = new int[1];
    private final int[] windowWidth = new int[1];
    private final int[] windowHeight = new int[1];

    private double mouseX;
    private double mouseY;

    private boolean inventoryDragActive = false;
    private int inventoryDragSourceSlot = -1;

    private HudRenderer.ScreenMode screenMode =
            HudRenderer.ScreenMode.MAIN_MENU;
    private boolean escapeWasDown;
    private boolean loadPending;
    private boolean savePending;
    private boolean quitAfterSave;
    private boolean returnToMainMenuAfterSave;
    private long savingScreenStartedAtNanos;
    private boolean worldLoaded;
    private int activeSaveSlot = -1;
    private final boolean[] saveSlotsExist =
            new boolean[SaveManager.SLOT_COUNT];
    private String statusMessage = "";

    public static void main(String[] args) {
        new Main().run();
    }

    private void run() {
        // Load metadata before creating a window. No gameplay IDs are changed.
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

        GLFWErrorCallback errorCallback =
                GLFWErrorCallback.createPrint(System.err);
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
        glfwWindowHint(
                GLFW_OPENGL_PROFILE,
                GLFW_OPENGL_CORE_PROFILE
        );
        glfwWindowHint(GLFW_OPENGL_FORWARD_COMPAT, GLFW_TRUE);
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);

        window = glfwCreateWindow(
                WINDOW_WIDTH,
                WINDOW_HEIGHT,
                "Wildforge",
                0,
                0
        );

        if (window == 0) {
            throw new IllegalStateException(
                    "Failed to create GLFW window"
            );
        }

        GLFWVidMode videoMode =
                glfwGetVideoMode(glfwGetPrimaryMonitor());

        if (videoMode != null) {
            glfwSetWindowPos(
                    window,
                    (videoMode.width() - WINDOW_WIDTH) / 2,
                    (videoMode.height() - WINDOW_HEIGHT) / 2
            );
        }

        glfwMakeContextCurrent(window);
        glfwSwapInterval(1);
        GL.createCapabilities();

        terrain = new TerrainMesh();
        player = new Player(terrain, worldItems, gameContent);
        renderer = new Renderer(terrain);
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
    public void onCursorPosition(long handle, double x, double y) {
        mouseX = x;
        mouseY = y;

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
    public void onMouseButton(
            long handle, int button, int action, int mods) {
        if (glfwGetWindowAttrib(handle, GLFW_FOCUSED) != GLFW_TRUE) {
            return;
        }

        if (button != GLFW_MOUSE_BUTTON_LEFT
                && button != GLFW_MOUSE_BUTTON_RIGHT) {
            return;
        }

        if (screenMode == HudRenderer.ScreenMode.PLAYING
                && player.isInventoryOpen()) {
            handleInventoryMouseButton(handle, button, action);
            return;
        }

        if (screenMode == HudRenderer.ScreenMode.MAIN_MENU
                || screenMode == HudRenderer.ScreenMode.SAVE_SELECT
                || screenMode == HudRenderer.ScreenMode.PAUSED) {
            if (button == GLFW_MOUSE_BUTTON_LEFT
                    && action == GLFW_PRESS) {
                handleMenuClick(handle);
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
            }
        }

        escapeWasDown = escapeDown;
    }

    private void handleMenuClick(long handle) {
        HudRenderer.MenuButton button = getMenuButtonAt(handle);

        if (screenMode == HudRenderer.ScreenMode.MAIN_MENU) {
            if (button == HudRenderer.MenuButton.PLAY) {
                refreshSaveSlots();
                screenMode = HudRenderer.ScreenMode.SAVE_SELECT;
                statusMessage = "";
            } else if (button == HudRenderer.MenuButton.QUIT) {
                glfwSetWindowShouldClose(handle, true);
            }
            // Options is a visual placeholder for now.
            return;
        }

        if (screenMode == HudRenderer.ScreenMode.SAVE_SELECT) {
            switch (button) {
                case SAVE_ONE -> beginLoadingSave(0);
                case SAVE_TWO -> beginLoadingSave(1);
                case SAVE_THREE -> beginLoadingSave(2);
                case BACK -> {
                    screenMode = HudRenderer.ScreenMode.MAIN_MENU;
                    statusMessage = "";
                }
                default -> {
                    // No action for the options placeholder or empty space.
                }
            }
            return;
        }

        if (screenMode == HudRenderer.ScreenMode.PAUSED) {
            if (button == HudRenderer.MenuButton.CONTINUE) {
                resumeGame();
            } else if (button == HudRenderer.MenuButton.QUIT) {
                beginSaveAndReturnToMainMenu();
            }
            // Options is a visual placeholder for now.
        }
    }

    private void beginLoadingSave(int slot) {
        if (slot < 0 || slot >= SaveManager.SLOT_COUNT) {
            return;
        }

        activeSaveSlot = slot;
        screenMode = HudRenderer.ScreenMode.LOADING;
        statusMessage = "";
        loadPending = true;
    }

    private void refreshSaveSlots() {
        saveManager.refreshSlots(saveSlotsExist);
    }

    private HudRenderer.MenuButton getMenuButtonAt(long handle) {
        glfwGetWindowSize(handle, windowWidth, windowHeight);
        glfwGetFramebufferSize(
                handle, framebufferWidth, framebufferHeight);

        if (windowWidth[0] <= 0 || windowHeight[0] <= 0
                || framebufferWidth[0] <= 0
                || framebufferHeight[0] <= 0) {
            return HudRenderer.MenuButton.NONE;
        }

        float x = (float) (
                mouseX * framebufferWidth[0] / windowWidth[0]);
        float y = (float) (
                mouseY * framebufferHeight[0] / windowHeight[0]);

        return renderer.menuButtonAt(
                x, y,
                framebufferWidth[0], framebufferHeight[0],
                screenMode
        );
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

    private void beginSaving(
            boolean closeAfterSave,
            boolean returnToMainMenuAfterSave) {
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

    private void handleInventoryMouseButton(
            long handle, int button, int action) {

        if (button == GLFW_MOUSE_BUTTON_LEFT) {
            if (action == GLFW_PRESS) {
                beginInventoryDrag(handle);
            } else if (action == GLFW_RELEASE) {
                finishInventoryDrag(handle);
            }
        } else if (button == GLFW_MOUSE_BUTTON_RIGHT
                && action == GLFW_PRESS) {
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
        glfwGetFramebufferSize(
                handle, framebufferWidth, framebufferHeight);

        if (windowWidth[0] <= 0 || windowHeight[0] <= 0
                || framebufferWidth[0] <= 0
                || framebufferHeight[0] <= 0) {
            return -1;
        }

        float x = (float) (
                mouseX * framebufferWidth[0] / windowWidth[0]);
        float y = (float) (
                mouseY * framebufferHeight[0] / windowHeight[0]);

        return inventorySlotAt(
                x, y, framebufferWidth[0], framebufferHeight[0]);
    }

    private int inventorySlotAt(
            float mouseX, float mouseY, int width, int height) {
        return HudRenderer.inventorySlotAt(
                mouseX, mouseY, width, height);
    }

    private void bringWindowToFront() {
        try {
            long nativeWindow =
                    GLFWNativeWin32.glfwGetWin32Window(window);

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
            double now = glfwGetTime();
            float deltaTime = (float) (now - lastFrameTime);
            lastFrameTime = now;
            // Avoid a large physics step after a debugger pause, window drag,
            // or OS scheduling stall. Player also applies its own safety cap.
            deltaTime = Math.max(0.0f,
                    Math.min(deltaTime, MAX_FRAME_DELTA_SECONDS));

            glfwPollEvents();
            handleEscapeKey();

            if (screenMode == HudRenderer.ScreenMode.PLAYING) {
                player.update(window, terrain, deltaTime);

                if (screenMode == HudRenderer.ScreenMode.PLAYING) {
                    worldItems.update(deltaTime, player);
                }
            }

            glfwGetFramebufferSize(
                    window, framebufferWidth, framebufferHeight);
            glfwGetWindowSize(window, windowWidth, windowHeight);

            renderer.render(
                    framebufferWidth[0],
                    framebufferHeight[0],
                    player.getCamera(),
                    player
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
                    saveSlotsExist
            );

            glfwSwapBuffers(window);

            // Swap the status screen before doing file I/O so users see
            // that the world is loading or saving.
            if (loadPending) {
                loadWorld();
            }
            if (savePending
                    && System.nanoTime() - savingScreenStartedAtNanos
                            >= MIN_SAVING_SCREEN_NANOS) {
                saveWorld();
            }
        }
    }
}
