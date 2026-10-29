package com.wildforge.core;

/** Headless fixed-step timing regression without requiring GLFW/OpenGL. */
public final class FixedStepClockSmokeTest {
    public static void main(String[] args) {
        FixedStepClock clock = FixedStepClock.playerPhysics();
        int[] ticks = {0};
        int[] worldTicks = {0};
        SimulationTickSchedule cadence = new SimulationTickSchedule();
        Runnable step = () -> {
            ticks[0]++;
            if (cadence.nextStepRunsWorldTick()) worldTicks[0]++;
        };
        for (int f = 0; f < 600; f++)
            clock.advance(1.0 / 120.0, step);
        require(ticks[0] == 300, "five seconds at 120 FPS must yield 300 physics steps");
        require(worldTicks[0] == 100, "five seconds at 60 Hz physics yields 100 world ticks");
        require(clock.tickCount() == ticks[0], "tick counter tracks successful steps");
        require(clock.interpolationAlpha() >= 0 && clock.interpolationAlpha() < 1,
            "alpha bounded after exact updates");
        require(clock.advance(0, () -> ticks[0]++) == 0, "no tick when no time elapsed");

        // Frame spikes cap simulation debt so the game cannot spiral into catch-up.
        int caughtUp = clock.advance(5.0, () -> ticks[0]++);
        require(caughtUp <= FixedStepClock.MAX_CATCHUP_STEPS, "catchup budget");
        require(clock.discardedSeconds() >= 4.89, "stall time is explicitly accounted for");

        clock.advance(0.5 / FixedStepClock.PHYSICS_HZ, () -> ticks[0]++);
        clock.reset();
        cadence.reset();
        require(cadence.physicsTicks() == 0, "world tick cadence resets on world swap");
        int before = ticks[0];
        clock.advance(0.5 / FixedStepClock.PHYSICS_HZ, () -> ticks[0]++);
        require(ticks[0] == before, "pause/reset cannot leak partial movement ticks");

        boolean rejected = false;
        try { clock.advance(Double.NaN, () -> {}); }
        catch (IllegalArgumentException expected) { rejected = true; }
        require(rejected, "invalid frame times rejected");
        System.out.println("FixedStepClock passed: 120 FPS input -> 60 Hz simulation, bounded stalls/pause");
    }
    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
