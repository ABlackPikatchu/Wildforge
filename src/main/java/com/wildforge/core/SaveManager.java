package com.wildforge.core;

import com.wildforge.player.Player;
import com.wildforge.world.TerrainMesh;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Owns save-slot paths and persistence operations for Wildforge. */
public final class SaveManager {
    public static final int SLOT_COUNT = 3;

    private static final Path SAVE_DIRECTORY = Path.of("saves");
    private static final Path LEGACY_SAVE_PATH =
            SAVE_DIRECTORY.resolve("wildforge.world");

    public boolean isValidSlot(int slot) {
        return slot >= 0 && slot < SLOT_COUNT;
    }

    public void loadTerrain(int slot, TerrainMesh terrain) throws IOException {
        requireValidSlot(slot);
        if (terrain == null) {
            throw new IllegalArgumentException("Terrain cannot be null");
        }

        Path worldPath = worldPath(slot);
        if (Files.isRegularFile(worldPath)) {
            terrain.loadFrom(worldPath);
        } else if (slot == 0 && Files.isRegularFile(LEGACY_SAVE_PATH)) {
            // Keep compatibility with the original single-world save.
            terrain.loadFrom(LEGACY_SAVE_PATH);
        } else {
            terrain.resetWorld();
        }
    }

    public void loadPlayer(int slot, Player player, TerrainMesh terrain)
            throws IOException {
        requireValidSlot(slot);
        if (player == null || terrain == null) {
            throw new IllegalArgumentException(
                    "Player and terrain must not be null");
        }

        Path path = playerPath(slot);
        if (Files.isRegularFile(path)) {
            player.loadFrom(path, terrain);
        }
    }

    public void save(int slot, TerrainMesh terrain, Player player)
            throws IOException {
        requireValidSlot(slot);
        if (terrain == null || player == null) {
            throw new IllegalArgumentException(
                    "Terrain and player must not be null");
        }

        Files.createDirectories(SAVE_DIRECTORY);
        terrain.saveTo(worldPath(slot));
        player.saveTo(playerPath(slot));
    }

    /** Updates the caller-owned slot state array. */
    public void refreshSlots(boolean[] destination) {
        if (destination == null || destination.length < SLOT_COUNT) {
            throw new IllegalArgumentException(
                    "Destination must contain at least " + SLOT_COUNT
                            + " save-slot entries");
        }

        for (int slot = 0; slot < SLOT_COUNT; slot++) {
            destination[slot] = Files.isRegularFile(worldPath(slot))
                    || (slot == 0 && Files.isRegularFile(LEGACY_SAVE_PATH));
        }
    }

    private Path worldPath(int slot) {
        return SAVE_DIRECTORY.resolve("world_" + (slot + 1) + ".wfs");
    }

    private Path playerPath(int slot) {
        return SAVE_DIRECTORY.resolve("player_" + (slot + 1) + ".wfp");
    }

    private void requireValidSlot(int slot) throws IOException {
        if (!isValidSlot(slot)) {
            throw new IOException("Invalid save slot: " + slot);
        }
    }
}
