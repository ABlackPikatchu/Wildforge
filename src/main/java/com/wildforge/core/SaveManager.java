package com.wildforge.core;

import com.wildforge.player.Player;
import com.wildforge.world.TerrainMesh;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Owns save-slot paths and persistence operations for Wildforge. */
public final class SaveManager {
    private final Path saveDirectory;
    private final Path legacySavePath;

    public SaveManager() {
        this(Path.of("saves"));
    }

    /** Injectable directory for isolated tests and future portable installations. */
    public SaveManager(Path saveDirectory) {
        if (saveDirectory == null) throw new IllegalArgumentException("Save directory cannot be null");
        this.saveDirectory = saveDirectory;
        this.legacySavePath = saveDirectory.resolve("wildforge.world");
    }
    private static final Pattern WORLD_FILE = Pattern.compile("world_(\\d+)\\.wfs");
    private static final Pattern PLAYER_FILE = Pattern.compile("player_(\\d+)\\.wfp");

    public record SaveSlotSummary(int slot, boolean hasWorld, boolean hasPlayer) {
        public String displayName() {
            return "WORLD " + (slot + 1);
        }

        public boolean exists() {
            return hasWorld || hasPlayer;
        }
    }

    public boolean isValidSlot(int slot) {
        return slot >= 0 && slot < Integer.MAX_VALUE;
    }

    public void loadTerrain(int slot, TerrainMesh terrain) throws IOException {
        requireValidSlot(slot);
        if (terrain == null) {
            throw new IllegalArgumentException("Terrain cannot be null");
        }

        Path worldPath = worldPath(slot);
        if (Files.isRegularFile(worldPath)) {
            terrain.loadFrom(worldPath);
        } else if (slot == 0 && Files.isRegularFile(legacySavePath)) {
            // Keep compatibility with the original single-world save.
            terrain.loadFrom(legacySavePath);
        } else if (Files.isRegularFile(playerPath(slot))) {
            // A partial save is not the same thing as a newly created world.
            throw new IOException("Incomplete world save: world data is missing for slot " + (slot + 1));
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
        } else if (Files.isRegularFile(worldPath(slot))) {
            // Old single-world saves (wildforge.world) legitimately have no player file.
            // Numbered world saves are always expected to have their matching player file.
            throw new IOException("Incomplete world save: player data is missing for slot " + (slot + 1));
        }
    }

    /** Optional display name stored separately from world and player data (legacy-safe). */
    public void saveWorldName(int slot,String name) throws IOException {
        requireValidSlot(slot);
        Files.createDirectories(saveDirectory);
        String clean=name==null?"":name.replaceAll("[\\r\\n\\t]"," ").trim();
        if(clean.isEmpty())clean="WORLD " +(slot+1);
        Files.writeString(namePath(slot),clean.substring(0,Math.min(clean.length(),32)));
    }
    public String worldName(int slot){
        try {if(Files.isRegularFile(namePath(slot)))return Files.readString(namePath(slot)).trim();}
        catch(IOException ignored){}
        return "WORLD " + (slot+1);
    }
    /** Existing worlds default to cheats disabled. World rules are separate from terrain/player saves. */
    public boolean cheatsEnabled(int slot) {
        try { requireValidSlot(slot);
            return Files.isRegularFile(rulesPath(slot)) &&
                Files.readString(rulesPath(slot)).trim().equals("cheats=true"); }
        catch (IOException ex) { return false; }
    }
    public void saveCheatsEnabled(int slot, boolean enabled) throws IOException {
        requireValidSlot(slot);
        Files.createDirectories(saveDirectory);
        Files.writeString(rulesPath(slot), "cheats=" + enabled + "\n");
    }
    private Path rulesPath(int slot) { return saveDirectory.resolve("world_" + ((long)slot+1) + ".rules"); }
    private Path namePath(int slot){return saveDirectory.resolve("world_"+((long)slot+1)+".name");}

    public void save(int slot, TerrainMesh terrain, Player player)
            throws IOException {
        requireValidSlot(slot);
        if (terrain == null || player == null) {
            throw new IllegalArgumentException(
                    "Terrain and player must not be null");
        }

        // Inventory cursor stacks aren't part of the current v1 player-save format.
        // Refuse to save rather than silently discarding items when no slot is free.
        if (player.hasCursorStack()) {
            throw new IOException("Return held inventory items before saving");
        }
        Files.createDirectories(saveDirectory);
        terrain.saveTo(worldPath(slot));
        player.saveTo(playerPath(slot));
    }

    /** Sorted list of every discovered save slot, preserving legacy slot 0 compatibility. */
    public List<SaveSlotSummary> listSlots() {
        Set<Integer> slots = new LinkedHashSet<>();
        boolean legacyWorldExists = Files.isRegularFile(legacySavePath);

        if (Files.isDirectory(saveDirectory)) {
            try (var paths = Files.list(saveDirectory)) {
                for (Path path : paths.toList()) {
                    String name = path.getFileName().toString();
                    Matcher world = WORLD_FILE.matcher(name);
                    Matcher player = PLAYER_FILE.matcher(name);
                    try {
                        if (world.matches()) {
                            slots.add(parseSlotIndex(world.group(1)));
                        } else if (player.matches()) {
                            slots.add(parseSlotIndex(player.group(1)));
                        }
                    } catch (NumberFormatException ignored) {
                        // Ignore invalid or oversized filenames.
                    }
                }
            } catch (IOException ignored) {
                // Treat unreadable save directories as empty for menu display.
            }
        }

        if (legacyWorldExists) {
            slots.add(0);
        }

        List<SaveSlotSummary> result = new ArrayList<>();
        for (Integer slot : slots.stream().sorted().toList()) {
            boolean hasWorld = Files.isRegularFile(worldPath(slot))
                    || (slot == 0 && legacyWorldExists);
            boolean hasPlayer = Files.isRegularFile(playerPath(slot));
            result.add(new SaveSlotSummary(slot, hasWorld, hasPlayer));
        }
        result.sort(Comparator.comparingInt(SaveSlotSummary::slot));
        return List.copyOf(result);
    }

    /** Create the next available save-slot index without imposing a hard cap. */
    public int nextAvailableSlot() {
        int highest = -1;
        for (SaveSlotSummary summary : listSlots()) {
            highest = Math.max(highest, summary.slot());
        }
        if (highest >= Integer.MAX_VALUE - 2) {
            throw new IllegalStateException("No supported save-slot numbers remain");
        }
        return highest + 1;
    }

    public boolean slotExists(int slot) {
        if (!isValidSlot(slot)) {
            return false;
        }
        return Files.isRegularFile(worldPath(slot))
                || Files.isRegularFile(playerPath(slot))
                || (slot == 0 && Files.isRegularFile(legacySavePath));
    }


    /** Permanently delete only this world's recognized files; never traverse folders. */
    public void deleteWorld(int slot) throws IOException {
        requireValidSlot(slot);
        if (!slotExists(slot)) {
            throw new IOException("World does not exist: " + (slot + 1));
        }
        // Do not touch any unrelated saves or files in the saves directory.
        Files.deleteIfExists(worldPath(slot));
        Files.deleteIfExists(playerPath(slot));
        Files.deleteIfExists(namePath(slot));
        Files.deleteIfExists(rulesPath(slot));
        if (slot == 0) {
            Files.deleteIfExists(legacySavePath);
        }
    }

    private static int parseSlotIndex(String oneBasedValue) {
        int fileNumber = Integer.parseInt(oneBasedValue);
        if (fileNumber < 1) throw new NumberFormatException("Invalid save number");
        return fileNumber - 1;
    }

    private Path worldPath(int slot) {
        return saveDirectory.resolve("world_" + ((long) slot + 1) + ".wfs");
    }

    private Path playerPath(int slot) {
        return saveDirectory.resolve("player_" + ((long) slot + 1) + ".wfp");
    }

    private void requireValidSlot(int slot) throws IOException {
        if (!isValidSlot(slot)) {
            throw new IOException("Invalid save slot: " + slot);
        }
    }
}
