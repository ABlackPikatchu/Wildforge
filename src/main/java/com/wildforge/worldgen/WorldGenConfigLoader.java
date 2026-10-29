package com.wildforge.worldgen;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;

/** Loads generated worldgen profile data from the game classpath. */
public final class WorldGenConfigLoader {
    public static final String RESOURCE = "data/wildforge/worldgen/overworld.json";

    private WorldGenConfigLoader() { }

    public static WorldGenSettings loadBuiltIn() throws IOException {
        try (InputStream stream = WorldGenConfigLoader.class.getClassLoader().getResourceAsStream(RESOURCE)) {
            if (stream == null) throw new IOException("Missing generated worldgen profile: " + RESOURCE);
            try (Reader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
                JsonObject json = JsonParser.parseReader(reader).getAsJsonObject();
                return new WorldGenSettings(
                        json.get("min_y").getAsInt(),
                        511,
                        json.get("sea_level").getAsInt() + 160,
                        json.get("continent_scale").getAsDouble(),
                        json.get("hills_scale").getAsDouble(),
                        json.get("mountains_scale").getAsDouble(),
                        json.get("detail_scale").getAsDouble(),
                        json.get("climate_scale").getAsDouble(),
                        json.get("river_scale").getAsDouble(),
                        json.get("cave_scale").getAsDouble(),
                        json.get("ocean_threshold").getAsDouble(),
                        json.get("mountain_threshold").getAsDouble(),
                        json.get("cave_threshold").getAsDouble(),
                        json.get("tree_density").getAsDouble(),
                        json.get("plant_density").getAsDouble());
            }
        } catch (RuntimeException e) {
            throw new IOException("Invalid generated worldgen profile: " + RESOURCE, e);
        }
    }
}
