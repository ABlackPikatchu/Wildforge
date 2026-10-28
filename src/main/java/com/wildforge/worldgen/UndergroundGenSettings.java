package com.wildforge.worldgen;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/** Tuning parameters stored in data/wildforge/worldgen/underground_settings.json. */
public record UndergroundGenSettings(
        double regionScale,
        double groundwaterScale,
        double faultScale,
        double mineralScale,
        double volcanicScale,
        double faultThreshold,
        double mineralThreshold,
        double groundwaterThreshold,
        double coldTemperatureThreshold) {

    public static final String RESOURCE = "data/wildforge/worldgen/underground_settings.json";

    public UndergroundGenSettings {
        if (!positive(regionScale) || !positive(groundwaterScale)
                || !positive(faultScale) || !positive(mineralScale)
                || !positive(volcanicScale)
                || !normalized(faultThreshold) || !normalized(mineralThreshold)
                || !normalized(groundwaterThreshold) || !normalized(coldTemperatureThreshold)) {
            throw new IllegalArgumentException("Invalid underground generation settings");
        }
    }

    private static boolean positive(double x) {
        return Double.isFinite(x) && x >= 8;
    }

    private static boolean normalized(double x) {
        return Double.isFinite(x) && x >= 0 && x <= 1;
    }

    public static UndergroundGenSettings loadBuiltIn() throws IOException {
        try (InputStream stream = UndergroundGenSettings.class.getClassLoader().getResourceAsStream(RESOURCE)) {
            if (stream == null) throw new IOException("Missing underground tuning resource " + RESOURCE);
            try (InputStreamReader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
                JsonObject json = JsonParser.parseReader(reader).getAsJsonObject();
                return new UndergroundGenSettings(
                        json.get("region_scale").getAsDouble(),
                        json.get("groundwater_scale").getAsDouble(),
                        json.get("fault_scale").getAsDouble(),
                        json.get("mineral_scale").getAsDouble(),
                        json.get("volcanic_scale").getAsDouble(),
                        json.get("fault_threshold").getAsDouble(),
                        json.get("mineral_threshold").getAsDouble(),
                        json.get("groundwater_threshold").getAsDouble(),
                        json.get("cold_temperature_threshold").getAsDouble());
            }
        } catch (RuntimeException e) {
            throw new IOException("Invalid underground generation settings", e);
        }
    }
}
