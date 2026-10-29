package com.wildforge.core;

import com.wildforge.player.GameMode;
import java.security.SecureRandom;
import java.util.Objects;
import java.util.function.LongSupplier;

/** Converts UI draft fields into a validated, immutable world creation request.
 * The game session receives the request; GLFW and menu input don't own seed rules. */
public record NewWorldRequest(long seed, String name, GameMode gameMode, boolean cheats) {
    public NewWorldRequest {
        Objects.requireNonNull(gameMode,"gameMode");
        if(name==null || name.isBlank())name="New World";
        name=name.trim();
        if(name.length()>80)name=name.substring(0,80);
    }
    public static NewWorldRequest fromDraft(String seedText, String name, GameMode mode, boolean cheats) {
        return fromDraft(seedText,name,mode,cheats,()->new SecureRandom().nextLong());
    }
    /** Injectable seed generator permits deterministic unit verification. */
    static NewWorldRequest fromDraft(String seedText, String name, GameMode mode,
                                     boolean cheats, LongSupplier randomSeed) {
        Objects.requireNonNull(randomSeed,"randomSeed");
        String value=seedText==null?"":seedText.trim();
        long seed;
        if(value.isBlank())seed=randomSeed.getAsLong();
        else {
            try {seed=Long.parseLong(value);} catch(NumberFormatException e){seed=textSeed(value);}
        }
        return new NewWorldRequest(seed,name,mode,cheats);
    }
    /** Stable FNV-1a hashing of user-entered text seeds, compatible with v6 UI. */
    public static long textSeed(String value) {
        long hash=0xcbf29ce484222325L;
        for(int i=0;i<value.length();i++){hash^=value.charAt(i);hash*=0x100000001b3L;}
        return hash;
    }
}
