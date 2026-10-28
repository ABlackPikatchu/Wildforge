package com.wildforge.worldgen;

/** Rock formation is sampled independently of climate and surface biome. */
public enum Geology {
    GRANITE("granite"),
    LIMESTONE("limestone"),
    SANDSTONE("sandstone"),
    SHALE("shale"),
    BASALT("basalt"),
    VOLCANIC("scoria");

    private final String baseRockTexture;

    Geology(String baseRockTexture) {
        this.baseRockTexture = baseRockTexture;
    }

    public String baseRockTexture() {
        return baseRockTexture;
    }
}
