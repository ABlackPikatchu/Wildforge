package com.wildforge.core;

/** Fixed 60 Hz physics to 20 Hz world (fluids) cadence. */
public final class SimulationTickSchedule {
    private long physicsTicks;
    public boolean nextStepRunsWorldTick() {
        physicsTicks++;
        return physicsTicks % 3 == 0;
    }
    public long physicsTicks() { return physicsTicks; }
    public void reset() { physicsTicks = 0; }
}
