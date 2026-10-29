package com.wildforge.player;

/** Persisted player rules. Survival is the default for older worlds. */
public enum GameMode {
    SURVIVAL, CREATIVE, SPECTATOR;
    public static GameMode parse(String text) {
        if (text == null) return null;
        return switch (text.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "0", "s", "survival" -> SURVIVAL;
            case "1", "c", "creative" -> CREATIVE;
            case "3", "sp", "spectator" -> SPECTATOR;
            default -> null;
        };
    }
}
