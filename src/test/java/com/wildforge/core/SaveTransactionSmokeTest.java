package com.wildforge.core;

import com.wildforge.core.save.SaveTransaction;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

/** Headless commit/rollback checks, with no OpenGL or game world required. */
public final class SaveTransactionSmokeTest {
    private SaveTransactionSmokeTest() { }
    public static void main(String[] ignored) throws Exception {
        Path directory = Files.createTempDirectory("wildforge-transaction-");
        try {
            Path terrain=directory.resolve("world_1.wfs");
            Path player=directory.resolve("player_1.wfp");
            Path items=directory.resolve("items_1.wfi");
            Path journal=directory.resolve("world_1.journal");
            Files.writeString(terrain,"old terrain");
            Files.writeString(player,"old player");
            List<Path> paths=List.of(terrain,player,items);
            // Failure during staging must preserve old files, not create item data.
            try {
                SaveTransaction.write(journal,List.of(
                        new SaveTransaction.Entry(terrain,p->Files.writeString(p,"new terrain")),
                        new SaveTransaction.Entry(player,p->{throw new IOException("simulated disk error");}),
                        new SaveTransaction.Entry(items,p->Files.writeString(p,"items"))));
                throw new AssertionError("Expected staging failure");
            } catch (IOException expected) { }
            check(Files.readString(terrain).equals("old terrain"),"terrain unchanged on staging error");
            check(Files.readString(player).equals("old player"),"player unchanged on staging error");
            check(!Files.exists(items),"no orphan item save");
            SaveTransaction.write(journal,List.of(
                    new SaveTransaction.Entry(terrain,p->Files.writeString(p,"new terrain")),
                    new SaveTransaction.Entry(player,p->Files.writeString(p,"new player")),
                    new SaveTransaction.Entry(items,p->Files.writeString(p,"new items"))));
            check(Files.readString(terrain).equals("new terrain"),"terrain commit");
            check(Files.readString(player).equals("new player"),"player commit");
            check(Files.readString(items).equals("new items"),"item commit");
            // Simulate a crash after world replacement but before player replacement.
            Files.copy(terrain,terrain.resolveSibling("world_1.wfs.backup"));
            Files.copy(player,player.resolveSibling("player_1.wfp.backup"));
            Files.writeString(terrain,"partial world");
            Files.writeString(journal,"110");
            SaveTransaction.recover(journal,paths);
            check(Files.readString(terrain).equals("new terrain"),"roll back partial terrain commit");
            check(Files.readString(player).equals("new player"),"preserve matching player");
            check(!Files.exists(items),"restore originally missing item file");
            check(!Files.exists(journal),"clear recovery journal");
            System.out.println("Save transaction checks passed: staged failure, 3-file commit, crash recovery");
        } finally {
            try (var paths=Files.walk(directory)) {
                for (Path file:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(file);
            }
        }
    }
    private static void check(boolean ok,String reason) { if (!ok)throw new AssertionError(reason); }
}
