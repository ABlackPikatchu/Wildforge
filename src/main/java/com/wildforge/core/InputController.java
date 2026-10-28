package com.wildforge.core;

import static org.lwjgl.glfw.GLFW.*;

/**
 * Owns GLFW callback registration and forwards raw window input to the game.
 * Game-state decisions remain in Main so this extraction preserves behavior.
 */
public final class InputController {

    public interface Callbacks {
        void onCursorPosition(long window, double x, double y);

        void onScroll(long window, double xOffset, double yOffset);

        void onWindowFocusChanged(long window, boolean focused);

        void onWindowCloseRequested(long window);

        void onMouseButton(long window, int button, int action, int mods);

        void onKey(long window, int key, int scancode, int action, int mods);
    }

    private final long window;
    private final Callbacks callbacks;
    private boolean installed;

    public InputController(long window, Callbacks callbacks) {
        if (window == 0L) {
            throw new IllegalArgumentException("A valid GLFW window is required");
        }
        if (callbacks == null) {
            throw new IllegalArgumentException("Input callbacks are required");
        }

        this.window = window;
        this.callbacks = callbacks;
    }

    /** Installs callbacks once for the lifetime of this window. */
    public void install() {
        if (installed) {
            return;
        }

        glfwSetCursorPosCallback(window,
                (handle, x, y) -> callbacks.onCursorPosition(handle, x, y));
        glfwSetScrollCallback(window,
                (handle, xOffset, yOffset) ->
                        callbacks.onScroll(handle, xOffset, yOffset));
        glfwSetWindowFocusCallback(window,
                (handle, focused) ->
                        callbacks.onWindowFocusChanged(handle, focused));
        glfwSetWindowCloseCallback(window,
                callbacks::onWindowCloseRequested);
        glfwSetMouseButtonCallback(window,
                (handle, button, action, mods) ->
                        callbacks.onMouseButton(handle, button, action, mods));
        glfwSetKeyCallback(window, (handle, key, scancode, action, mods) ->
                callbacks.onKey(handle, key, scancode, action, mods));

        installed = true;
    }
}
