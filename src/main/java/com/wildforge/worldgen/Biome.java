package com.wildforge.worldgen;

import java.util.Locale;

/** The twelve surface biomes. Ocean water is a hydrology layer, not a thirteenth biome. */
public enum Biome {
    GREENHEART_FOREST,
    NEEDLEWOOD_TAIGA,
    EMBERLEAF_WOODLAND,
    SUNMEADOW_PLAINS,
    FROSTSPIRE_HIGHLANDS,
    FROSTMOOR_TUNDRA,
    REDSCAR_BADLANDS,
    SUNCRACK_DESERT,
    MISTFEN_WETLANDS,
    WHITECLIFF_COAST,
    CINDERFALL_VOLCANIC_FIELDS,
    HOLLOWSTONE_KARST;

    public String id() {
        return "wildforge:" + name().toLowerCase(Locale.ROOT);
    }
}
