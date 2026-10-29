package com.wildforge.core;

import com.wildforge.content.ContentLoader;
import com.wildforge.player.GameMode;
import com.wildforge.player.Player;
import com.wildforge.world.TerrainMesh;
import com.wildforge.world.WorldItemManager;
import java.io.IOException;
import java.util.Objects;

/**
 * Main-thread owner of the active playable world, player, and save slot.
 * Main decides which screen to display; the session performs world lifecycle
 * transitions and coordinates persistence through SaveManager.
 */
public final class GameSession {
    private final TerrainMesh terrain;
    private final WorldItemManager items;
    private final SaveManager saves;
    private final ContentLoader.Content content;
    private Player player;
    private int slot=-1;
    private boolean cheats;

    public GameSession(TerrainMesh terrain,WorldItemManager items,SaveManager saves,
                       ContentLoader.Content content) {
        this.terrain=Objects.requireNonNull(terrain);
        this.items=Objects.requireNonNull(items);
        this.saves=Objects.requireNonNull(saves);
        this.content=Objects.requireNonNull(content);
    }
    /**
     * The new-seed path is the only way to request fresh worldgen. All other
     * loads first resolve the slot's recovery journal.
     */
    public Player open(int slot,Long newSeed,GameMode newGameMode,
                       boolean newCheats,KeyBindings bindings) throws IOException {
        if(!saves.isValidSlot(slot))throw new IOException("Invalid save slot");
        if(newSeed!=null)terrain.newWorld(newSeed);
        else saves.loadTerrain(slot,terrain);
        items.clear();
        Player loaded=new Player(terrain,items,content);
        loaded.setKeyBindings(Objects.requireNonNull(bindings));
        if(newSeed==null) {
            saves.loadPlayer(slot,loaded,terrain);
            saves.loadItems(slot,items,terrain);
        }
        boolean enabled;
        if(newGameMode!=null){
            loaded.setGameMode(newGameMode);
            enabled=newCheats;
            saves.saveCheatsEnabled(slot,enabled);
        } else enabled=saves.cheatsEnabled(slot);
        terrain.streamAround(loaded.getX(),loaded.getZ());
        // Commit the active identity only after every load operation succeeds.
        player=loaded;
        this.slot=slot;
        cheats=enabled;
        return player;
    }
    public void save(String worldName) throws IOException {
        if(slot<0||player==null)throw new IOException("No active game session");
        saves.save(slot,terrain,player,items);
        if(worldName!=null)saves.saveWorldName(slot,worldName);
    }
    public void leave(){player=null;slot=-1;cheats=false;}
    public int slot(){return slot;}
    public boolean cheatsEnabled(){return cheats;}
    public Player player(){return player;}
    public TerrainMesh terrain(){return terrain;}
    public WorldItemManager items(){return items;}
}
