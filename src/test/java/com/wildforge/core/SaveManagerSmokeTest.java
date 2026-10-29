package com.wildforge.core;

import com.wildforge.player.Player;
import com.wildforge.ui.SaveSelectionModel;
import com.wildforge.world.TerrainMesh;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

/** Executable save-system regression checks, using only an isolated temp folder. */
public final class SaveManagerSmokeTest {
    private SaveManagerSmokeTest() { }

    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("wildforge-save-audit-");
        try {
            verify(root);
            System.out.println("Wildforge save checks passed: multiple worlds, modern saves, "
                    + "orphan protection, deletion, held inventory stacks, paging");
        } finally {
            try (var paths = Files.walk(root)) {
                for (Path file : paths.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(file);
                }
            }
        }
    }

    private static void verify(Path root) throws Exception {
        SaveManager manager = new SaveManager(root);
        TerrainMesh terrain = new TerrainMesh();
        Player player = new Player(terrain);
        check(manager.listSlots().isEmpty(), "New save directory must be empty");
        check(manager.nextAvailableSlot() == 0, "First world slot must be zero");

        manager.save(0, terrain, player);
        manager.save(3, terrain, player);
        check(manager.listSlots().size() == 2, "Expected two saved worlds");
        check(manager.nextAvailableSlot() == 4, "Next slot should follow highest used ID");
        check(manager.slotExists(0) && manager.slotExists(3), "Saved worlds must be discoverable");

        SaveSelectionModel selection = new SaveSelectionModel(manager);
        selection.refresh();
        check("WORLD 1".equals(selection.visibleLabels()[0]), "World 1 should be visible");
        check("WORLD 4".equals(selection.visibleLabels()[1]), "World 4 should be visible");
        check(!selection.hasNextPage(), "Two worlds should fit on one page");
        manager.save(4, terrain, player);
        manager.save(6, terrain, player);
        selection.refresh();
        check(selection.hasNextPage(), "Four worlds must have a second page");
        selection.nextPage();
        check(selection.page() == 1, "Next should advance page");
        check("WORLD 7".equals(selection.visibleLabels()[0]),
                "Last world should appear on second page");
        selection.previousPage();
        check(selection.page() == 0, "Previous should return to first page");

        manager.loadTerrain(3, new TerrainMesh());
        TerrainMesh loadedTerrain = new TerrainMesh();
        manager.loadTerrain(3, loadedTerrain);
        manager.loadPlayer(3, new Player(loadedTerrain), loadedTerrain);
        manager.deleteWorld(0);
        check(!manager.slotExists(0) && manager.slotExists(3),
                "Deleting World 1 must not delete World 4");

        // A player file without a matching world must never start a blank map.
        Files.writeString(root.resolve("player_9.wfp"), "incomplete");
        expectIOException(() -> manager.loadTerrain(8, terrain),
                "Orphan player file must fail world loading");

        // A numbered world without its player file must never silently reset inventory.
        Files.writeString(root.resolve("world_10.wfs"), "incomplete");
        expectIOException(() -> manager.loadPlayer(9, player, terrain),
                "Orphan numbered world must fail player loading");

        // Obsolete single-world files must not be treated as current save slots.
        Files.copy(root.resolve("world_4.wfs"), root.resolve("wildforge.world"));
        check(!manager.slotExists(0),"Old unnumbered worlds are not imported");
        expectIOException(() -> manager.loadPlayer(9, player, terrain),
                "Fresh saves require a matching player file");

        // The version 1 player format cannot store a held cursor stack.
        player.clickInventorySlot(Player.HOTBAR_START_INDEX, false);
        check(player.hasCursorStack(), "Test must hold an item stack");
        expectIOException(() -> manager.save(11, terrain, player),
                "Refuse to discard a cursor stack during save");
        check(!manager.slotExists(11), "Failed save must not write world data");
        expectIOException(() -> player.saveTo(root.resolve("unsafe.wfp")),
                "Direct player save must also protect held items");

        manager.deleteWorld(3);
        check(Files.isRegularFile(root.resolve("wildforge.world")),
                "Unrecognized old files must not be deleted by new save-slot actions");
    }

    private static void expectIOException(CheckedRunnable action, String explanation)
            throws Exception {
        try {
            action.run();
        } catch (IOException expected) {
            return;
        }
        throw new AssertionError(explanation);
    }

    private static void check(boolean condition, String description) {
        if (!condition) throw new AssertionError(description);
    }

    private interface CheckedRunnable {
        void run() throws Exception;
    }
}
