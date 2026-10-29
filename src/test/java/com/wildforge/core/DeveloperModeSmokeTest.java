package com.wildforge.core;

import com.wildforge.player.GameMode;
import com.wildforge.player.Player;
import com.wildforge.world.TerrainMesh;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/** Isolated world/player regression; no window or OpenGL context required. */
public final class DeveloperModeSmokeTest {
    private DeveloperModeSmokeTest() {}
    public static void main(String[] args) throws Exception {
        check(GameMode.parse("creative") == GameMode.CREATIVE, "creative parse");
        check(GameMode.parse("3") == GameMode.SPECTATOR, "spectator parse");
        check(GameMode.parse("survival") == GameMode.SURVIVAL, "survival parse");
        check(GameMode.parse("nonsense") == null, "invalid mode rejected");
        TerrainMesh terrain = new TerrainMesh();
        check(DeveloperCommands.execute("/gamemode creative",new Player(terrain),terrain)
                .contains("CHEATS DISABLED"),"default cheat restriction");
        Player p = new Player(terrain);
        check(p.getGameMode() == GameMode.SURVIVAL, "default survival");
        check(DeveloperCommands.execute("/gamemode creative",p,terrain,true).contains("CREATIVE"), "switch mode");
        check(DeveloperCommands.execute("/fly on",p,terrain,true).contains("ENABLED"), "enable flight");
        check(DeveloperCommands.execute("/tp ~4 280 ~-2",p,terrain,true).contains("TELEPORTED"), "relative tp");
        check(p.getX() == 20f && p.getZ() == 25f && p.getFeetY() == 280f, "teleport coordinates");
        check(DeveloperCommands.execute("/tp 20 9999 20",p,terrain,true).contains("OUTSIDE"), "bad teleport rejected");
        Path save = Files.createTempFile("wf-mode-v2", ".wfp");
        Path old = Files.createTempFile("wf-mode-v1", ".wfp");
        try {
            p.saveTo(save);
            Player reload = new Player(terrain);
            reload.loadFrom(save,terrain);
            check(reload.getGameMode() == GameMode.CREATIVE && reload.isFlying(), "v2 game mode and flight round trip");
            byte[] legacy=Files.readAllBytes(save);
            ByteBuffer.wrap(legacy).putInt(4,1);
            Files.write(old, Arrays.copyOf(legacy, legacy.length-2));
            Player backCompat = new Player(terrain);
            backCompat.loadFrom(old,terrain);
            check(backCompat.getGameMode() == GameMode.SURVIVAL, "v1 defaults to survival");
            reload.setGameMode(GameMode.SPECTATOR);
            check(reload.isFlying(), "spectator always flies");
            check(DeveloperCommands.execute("/gamemode survival",reload,terrain,true).contains("SURVIVAL"), "back to survival");
            check(!reload.isFlying(), "survival flight disabled");
        } finally {
            Files.deleteIfExists(save);Files.deleteIfExists(old);
        }
        System.out.println("Wildforge developer modes/commands/save tests passed");
    }
    private static void check(boolean ok,String label) {
        if(!ok)throw new AssertionError(label);
    }
}
