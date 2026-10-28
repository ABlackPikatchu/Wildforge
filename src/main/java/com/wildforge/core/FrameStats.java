package com.wildforge.core;

import java.util.Arrays;

/** Rolling, allocation-free-per-frame timing sample for the F3 diagnostics. */
public final class FrameStats {
    private static final int WINDOW = 240;
    private final float[] ms = new float[WINDOW];
    private final float[] scratch = new float[WINDOW];
    private int count, next;
    private long lastNanos;
    private float sum;
    private int averageFps;
    private float averageMs, p95Ms, worstMs;
    private int refreshCounter;

    public void sample(long nowNanos) {
        if (lastNanos != 0L) {
            float duration = (nowNanos - lastNanos) / 1_000_000f;
            if (duration > 0f && duration < 1000f) {
                if (count == WINDOW) sum -= ms[next]; else count++;
                ms[next] = duration;
                sum += duration;
                next = (next + 1) % WINDOW;
                if (++refreshCounter >= 15) {
                    refreshCounter = 0;
                    averageMs = sum / count;
                    averageFps = Math.round(1000f / averageMs);
                    System.arraycopy(ms, 0, scratch, 0, count);
                    Arrays.sort(scratch, 0, count);
                    p95Ms = scratch[Math.min(count - 1, (int) Math.ceil(count * .95) - 1)];
                    worstMs = scratch[count-1];
                }
            }
        }
        lastNanos = nowNanos;
    }

    public int averageFps() { return averageFps; }
    public float averageMs() { return averageMs; }
    public float p95Ms() { return p95Ms; }
    public float worstMs() { return worstMs; }
}
