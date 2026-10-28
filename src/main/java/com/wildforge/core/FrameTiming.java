package com.wildforge.core;

import java.util.concurrent.locks.LockSupport;

/** FPS measurement and optional frame pacing, independent of rendering and UI. */
public final class FrameTiming {
    private long windowStartedNanos = System.nanoTime();
    private int framesInWindow;

    /** Returns the latest measured FPS, or -1 until a one-second sample is complete. */
    public int recordFrame(long nowNanos) {
        framesInWindow++;
        long elapsed = nowNanos - windowStartedNanos;
        if (elapsed < 1_000_000_000L) return -1;
        int fps = Math.round(framesInWindow * 1_000_000_000f / elapsed);
        framesInWindow = 0;
        windowStartedNanos = nowNanos;
        return fps;
    }

    /** Frame cap is independent of VSync. A cap of zero disables this limiter. */
    public static void limitFrameRate(long frameStartNanos, int fpsLimit) {
        if (fpsLimit <= 0) return;
        long targetNanos = frameStartNanos + 1_000_000_000L / fpsLimit;
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
