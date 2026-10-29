package com.wildforge.worldgen;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/** Reads the single authoritative new-world height profile, no offsets or legacy revisions. */
public final class WorldGenConfigLoader {
    public static final String RESOURCE = "data/wildforge/worldgen/overworld.json";
    private WorldGenConfigLoader() {}
    public static WorldGenSettings loadBuiltIn() throws IOException {
        try (InputStream stream=WorldGenConfigLoader.class.getClassLoader().getResourceAsStream(RESOURCE)) {
            if(stream==null)throw new IOException("Missing generated worldgen profile: "+RESOURCE);
            JsonObject j=JsonParser.parseReader(new InputStreamReader(stream,StandardCharsets.UTF_8)).getAsJsonObject();
            WorldGenSettings settings=new WorldGenSettings(j.get("min_y").getAsInt(), j.get("max_y").getAsInt(),
                    j.get("sea_level").getAsInt(), j.get("continent_scale").getAsDouble(),
                    j.get("hills_scale").getAsDouble(),j.get("mountains_scale").getAsDouble(),
                    j.get("detail_scale").getAsDouble(),j.get("climate_scale").getAsDouble(),
                    j.get("river_scale").getAsDouble(),j.get("cave_scale").getAsDouble(),
                    j.get("ocean_threshold").getAsDouble(),j.get("mountain_threshold").getAsDouble(),
                    j.get("cave_threshold").getAsDouble(),j.get("tree_density").getAsDouble(),
                    j.get("plant_density").getAsDouble());
            if(settings.minY()!=0||settings.maxY()!=511)
                throw new IOException("Runtime chunk storage expects Y 0..511, got "+settings.minY()+".."+settings.maxY());
            return settings;
        }catch(RuntimeException ex){throw new IOException("Invalid worldgen settings",ex);}
    }
}
