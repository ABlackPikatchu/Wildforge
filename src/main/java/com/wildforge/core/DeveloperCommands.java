package com.wildforge.core;

import com.wildforge.item.ItemType;
import com.wildforge.player.GameMode;
import com.wildforge.player.Player;
import com.wildforge.world.TerrainMesh;
import java.util.Locale;

/** Local single-player developer console. No network or filesystem commands. */
public final class DeveloperCommands {
    private DeveloperCommands() {}
    public static String execute(String input, Player player, TerrainMesh terrain) {
        return execute(input, player, terrain, false);
    }
    /** World commands that change the game require permission saved at world creation. */
    public static String execute(String input, Player player, TerrainMesh terrain, boolean cheatsEnabled) {
        if (input == null || player == null || terrain == null) return "NO WORLD LOADED";
        String raw=input.trim();
        if(raw.startsWith("/"))raw=raw.substring(1).trim();
        if(raw.isEmpty())return "EMPTY COMMAND - TYPE /HELP";
        String[] parts=raw.split("\s+");
        String command=parts[0].toLowerCase(Locale.ROOT);
        if (!cheatsEnabled && !(command.equals("help") || command.equals("?") ||
                command.equals("pos") || command.equals("position") || command.equals("seed")))
            return "CHEATS DISABLED FOR THIS WORLD";
        try {
            return switch(command) {
                case "help", "?" -> "COMMANDS: /GAMEMODE /FLY /TP /POS /SEED /GIVE";
                case "gamemode", "gm" -> {
                    if(parts.length!=2)yield "USAGE: /GAMEMODE SURVIVAL|CREATIVE|SPECTATOR";
                    GameMode mode=GameMode.parse(parts[1]);
                    if(mode==null)yield "UNKNOWN GAME MODE";
                    player.setGameMode(mode);
                    yield "GAME MODE: " + mode;
                }
                case "fly" -> {
                    if(player.getGameMode()==GameMode.SURVIVAL) yield "FLYING REQUIRES CREATIVE OR SPECTATOR";
                    boolean enabled=parts.length<2 ? !player.isFlying() :
                        switch(parts[1].toLowerCase(Locale.ROOT)){
                            case "on", "true", "1" -> true;
                            case "off", "false", "0" -> false;
                            default -> throw new IllegalArgumentException("USAGE: /FLY ON|OFF");
                        };
                    player.setFlying(enabled);
                    yield player.isFlying() ? "FLIGHT ENABLED" : "FLIGHT DISABLED";
                }
                case "tp", "teleport" -> {
                    if(parts.length!=4) yield "USAGE: /TP X Y Z";
                    float x=coordinate(parts[1],player.getX());
                    float y=coordinate(parts[2],player.getFeetY());
                    float z=coordinate(parts[3],player.getZ());
                    player.teleport(x,y,z);
                    yield String.format(Locale.ROOT,"TELEPORTED TO %.1f %.1f %.1f",x,y,z);
                }
                case "pos", "position" -> String.format(Locale.ROOT,
                        "XYZ %.1f / %.1f / %.1f",player.getX(),player.getFeetY(),player.getZ());
                case "seed" -> "WORLD SEED: " + terrain.seed();
                case "give" -> {
                    if(parts.length<2 || parts.length>3)yield "USAGE: /GIVE ITEM_ID [COUNT]";
                    ItemType item;
                    try {item=ItemType.valueOf(parts[1].toUpperCase(Locale.ROOT));}
                    catch(IllegalArgumentException ex){yield "UNKNOWN ITEM - USE ITEM ENUM NAME";}
                    int amount=parts.length==3 ? Integer.parseInt(parts[2]) : 1;
                    if(amount<1 || amount>64)yield "COUNT MUST BE 1-64";
                    yield player.addItem(item,amount) ? "GIVEN " + amount + " " + item.name() : "INVENTORY FULL";
                }
                default -> "UNKNOWN COMMAND - TYPE /HELP";
            };
        } catch(NumberFormatException ex) {
            return "INVALID NUMBER";
        } catch(IllegalArgumentException | IllegalStateException ex) {
            return ex.getMessage() == null ? "COMMAND ERROR" : ex.getMessage().toUpperCase(Locale.ROOT);
        }
    }
    private static float coordinate(String text, float base) {
        if(text.startsWith("~")) return base + (text.length()==1 ? 0f : Float.parseFloat(text.substring(1)));
        return Float.parseFloat(text);
    }
}
