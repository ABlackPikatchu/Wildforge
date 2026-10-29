package com.wildforge.core;

import java.util.Objects;

/**
 * Frame-time independent simulation accumulator. Uses a bounded catch-up budget
 * to prevent a rendering stall from triggering an unbounded physics backlog.
 * Run it only on the game thread; input and OpenGL are never called on workers.
 */
public final class FixedStepClock {
    public static final int PHYSICS_HZ = 60;
    public static final int MAX_CATCHUP_STEPS = 6;
    public static final double MAX_FRAME_SECONDS = 0.10;
    private final double secondsPerTick;
    private final int maxCatchup;
    private double accumulated;
    private long tickCount;
    private double discardedSeconds;

    public FixedStepClock(int ticksPerSecond, int maxCatchup) {
        if (ticksPerSecond <= 0 || ticksPerSecond > 1000 || maxCatchup <= 0)
            throw new IllegalArgumentException("Invalid fixed-step configuration");
        this.secondsPerTick = 1.0 / ticksPerSecond;
        this.maxCatchup = maxCatchup;
    }

    public static FixedStepClock playerPhysics() { return new FixedStepClock(PHYSICS_HZ, MAX_CATCHUP_STEPS); }

    public int advance(double frameSeconds, Runnable fixedStep) {
        Objects.requireNonNull(fixedStep, "fixedStep");
        if (!Double.isFinite(frameSeconds) || frameSeconds < 0)
            throw new IllegalArgumentException("Invalid frame duration");
        double bounded = Math.min(frameSeconds, MAX_FRAME_SECONDS);
        discardedSeconds += frameSeconds - bounded;
        accumulated += bounded;
        double cap = secondsPerTick * maxCatchup;
        if (accumulated > cap) {
            discardedSeconds += accumulated - cap;
            accumulated = cap;
        }
        int steps = 0;
        // Epsilon absorbs binary rounding at exact 60/20 Hz boundaries.
        while (accumulated + 1e-12 >= secondsPerTick && steps < maxCatchup) {
            fixedStep.run();
            accumulated = Math.max(0, accumulated - secondsPerTick);
            tickCount++;
            steps++;
        }
        return steps;
    }

    /** Called on pause/loading/world switch to prevent a catch-up burst on resume. */
    public void reset() { accumulated = 0; }
    public float fixedDeltaSeconds() { return (float)secondsPerTick; }
    public double interpolationAlpha() { return accumulated / secondsPerTick; }
    public long tickCount() { return tickCount; }
    public double discardedSeconds() { return discardedSeconds; }
}
