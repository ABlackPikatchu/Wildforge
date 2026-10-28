package com.wildforge.core;

import org.lwjgl.PointerBuffer;
import org.lwjgl.glfw.GLFWVidMode;

import static org.lwjgl.glfw.GLFW.*;

/**
 * Applies windowed, borderless fullscreen and exclusive fullscreen modes.
 * Tracks the last ordinary window geometry so switching modes is reversible.
 * The GLFW window and its OpenGL context are never recreated.
 */
public final class DisplayModeController {
    private int windowedX;
    private int windowedY;
    private int windowedWidth;
    private int windowedHeight;
    private GameSettings.DisplayMode appliedDisplayMode = GameSettings.DisplayMode.WINDOWED;

    public void rememberWindowed(long window, int width, int height) {
        int[] positionX = {0};
        int[] positionY = {0};
        glfwGetWindowPos(window, positionX, positionY);
        windowedX = positionX[0];
        windowedY = positionY[0];
        windowedWidth = width;
        windowedHeight = height;
    }

    /** Find the monitor nearest the window center (not necessarily the primary display). */
    public long monitorForWindow(long window) {
        long fullscreenMonitor = glfwGetWindowMonitor(window);
        if (fullscreenMonitor != 0L) return fullscreenMonitor;
        PointerBuffer monitors = glfwGetMonitors();
        long fallback = glfwGetPrimaryMonitor();
        if (monitors == null || monitors.remaining() == 0) return fallback;

        int[] windowX = {0};
        int[] windowY = {0};
        int[] sizeW = {0};
        int[] sizeH = {0};
        glfwGetWindowPos(window, windowX, windowY);
        glfwGetWindowSize(window, sizeW, sizeH);
        long centerX = (long) windowX[0] + sizeW[0] / 2;
        long centerY = (long) windowY[0] + sizeH[0] / 2;
        long closest = fallback;
        double distance = Double.POSITIVE_INFINITY;

        for (int i = monitors.position(); i < monitors.limit(); i++) {
            long candidate = monitors.get(i);
            GLFWVidMode videoMode = glfwGetVideoMode(candidate);
            if (videoMode == null) continue;
            int[] x = {0};
            int[] y = {0};
            glfwGetMonitorPos(candidate, x, y);
            long dx = Math.max(x[0] - centerX,
                    Math.max(0L, centerX - ((long) x[0] + videoMode.width())));
            long dy = Math.max(y[0] - centerY,
                    Math.max(0L, centerY - ((long) y[0] + videoMode.height())));
            double candidateDistance = (double) dx * dx + (double) dy * dy;
            if (candidateDistance < distance) {
                distance = candidateDistance;
                closest = candidate;
            }
        }
        return closest;
    }

    public void apply(long window, GameSettings settings) {
        long monitor = monitorForWindow(window);
        GLFWVidMode mode = monitor == 0 ? null : glfwGetVideoMode(monitor);
        if (mode == null) return;

        GameSettings.DisplayMode target = settings.displayMode();
        if (appliedDisplayMode == GameSettings.DisplayMode.WINDOWED
                && target != GameSettings.DisplayMode.WINDOWED) {
            int[] x = {0};
            int[] y = {0};
            int[] w = {0};
            int[] h = {0};
            glfwGetWindowPos(window, x, y);
            glfwGetWindowSize(window, w, h);
            windowedX = x[0];
            windowedY = y[0];
            windowedWidth = w[0];
            windowedHeight = h[0];
        }

        if (target == GameSettings.DisplayMode.FULLSCREEN) {
            glfwSetWindowAttrib(window, GLFW_DECORATED, GLFW_TRUE);
            glfwSetWindowMonitor(window, monitor, 0, 0,
                    mode.width(), mode.height(), mode.refreshRate());
        } else if (target == GameSettings.DisplayMode.BORDERLESS) {
            int[] x = {0};
            int[] y = {0};
            glfwGetMonitorPos(monitor, x, y);
            glfwSetWindowAttrib(window, GLFW_DECORATED, GLFW_FALSE);
            glfwSetWindowAttrib(window, GLFW_RESIZABLE, GLFW_FALSE);
            glfwSetWindowMonitor(window, 0L, x[0], y[0],
                    mode.width(), mode.height(), GLFW_DONT_CARE);
            glfwSetWindowPos(window, x[0], y[0]);
        } else {
            glfwSetWindowAttrib(window, GLFW_DECORATED, GLFW_TRUE);
            glfwSetWindowAttrib(window, GLFW_RESIZABLE, GLFW_TRUE);
            boolean restoring = appliedDisplayMode != GameSettings.DisplayMode.WINDOWED;
            int requestedWidth = restoring ? windowedWidth : settings.resolutionWidth();
            int requestedHeight = restoring ? windowedHeight : settings.resolutionHeight();
            int w = Math.max(640, Math.min(requestedWidth, mode.width()));
            int h = Math.max(360, Math.min(requestedHeight, mode.height()));
            int[] mx = {0};
            int[] my = {0};
            glfwGetMonitorPos(monitor, mx, my);
            int x = restoring ? windowedX : mx[0] + (mode.width() - w) / 2;
            int y = restoring ? windowedY : my[0] + (mode.height() - h) / 2;
            glfwSetWindowMonitor(window, 0L, x, y, w, h, GLFW_DONT_CARE);
            windowedX = x;
            windowedY = y;
            windowedWidth = w;
            windowedHeight = h;
        }
        appliedDisplayMode = target;
        glfwSwapInterval(settings.vsync() ? 1 : 0);
    }
}
