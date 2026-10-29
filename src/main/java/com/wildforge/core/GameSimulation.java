package com.wildforge.core;

import com.wildforge.player.Player;
import com.wildforge.world.TerrainMesh;
import com.wildforge.world.WorldItemManager;
import java.util.Objects;

/**
 * Deterministic ordering on the game's main thread: 60 Hz player/items,
 * 20 Hz fluid world ticks. Streaming and mesh pumping are handled separately
 * once per rendered frame so worker results continue installing while idle.
 */
public final class GameSimulation {
    private final SimulationTickSchedule cadence = new SimulationTickSchedule();

    public void step(long window, TerrainMesh terrain, Player player, WorldItemManager items) {
        Objects.requireNonNull(terrain, "terrain");
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(items, "items");
        if (cadence.nextStepRunsWorldTick()) terrain.stepFluids();
        float dt = 1.0f / FixedStepClock.PHYSICS_HZ;
        player.beginPhysicsStep();
        player.update(window, terrain, dt);
        items.update(dt, player, terrain);
    }

    public void reset() { cadence.reset(); }
    public long physicsTicks() { return cadence.physicsTicks(); }
}
