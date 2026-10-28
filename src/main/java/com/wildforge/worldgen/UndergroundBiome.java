package com.wildforge.worldgen;

import java.util.Locale;

/** Twelve underground biome identities. Caves may cross surface-biome boundaries. */
public enum UndergroundBiome {
    ROOTLACE_HOLLOWS,
    SEEPSTONE_GROTTOS,
    UNDERFLOW_CHANNELS,
    HOLLOWSTONE_VAULTS,
    COALSHADE_GALLERIES,
    SALTVEIL_CHAMBERS,
    FROSTVAULT_CREVASSES,
    IRONFAULT_CHASMS,
    CRYSTAL_SEAM_CHAMBERS,
    SPORELIGHT_HOLLOWS,
    CINDERVEIN_TUBES,
    STILLWATER_ABYSS;

    public String id() {
        return "wildforge:" + name().toLowerCase(Locale.ROOT);
    }
}
