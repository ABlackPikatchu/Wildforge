package com.wildforge.core;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/** Local user preferences, independent of world saves. */
public final class GameSettings {
    private static final Path FILE = Path.of("config", "wildforge_settings.json");

    public static final int MIN_FOV = 60;
    public static final int MAX_FOV = 110;
    private static final int[] FPS_LIMITS = {0, 30, 45, 60, 75, 90, 120, 144, 165, 240, 360};
    private static final float[] SENSITIVITY_VALUES = {0.06f, 0.09f, 0.12f, 0.16f, 0.20f, 0.25f};
    private static final int[][] RESOLUTIONS = {
            {854, 480}, {1024, 576}, {1280, 720}, {1366, 768},
            {1600, 900}, {1920, 1080}, {2560, 1440}, {3840, 2160}
    };

    private final KeyBindings keyBindings = new KeyBindings();
    public static final int MIN_UI_SCALE = 75;
    public static final int MAX_UI_SCALE = 200;
    public static final int UI_SCALE_STEP = 5;
    private int uiScalePercent = 125;
    private boolean vsync = true;
    private boolean crosshair = true;
    private boolean fullscreen = false;
    private boolean showFps = false;
    private int fov = 70;
    private int fpsLimit = 0; // 0 = unlimited (VSync may still limit FPS)
    private int resolutionWidth = 1600;
    private int resolutionHeight = 900;
    private float mouseSensitivity = 0.12f;

    public static GameSettings load() {
        GameSettings settings = new GameSettings();
        if (!Files.isRegularFile(FILE)) return settings;
        try (Reader reader = Files.newBufferedReader(FILE, StandardCharsets.UTF_8)) {
            JsonElement root = JsonParser.parseReader(reader);
            if (!root.isJsonObject()) throw new IOException("Settings must be a JSON object");
            JsonObject json = root.getAsJsonObject();
            if (json.has("ui_scale")) settings.uiScalePercent = normalizeUiScale(json.get("ui_scale").getAsInt());
            if (json.has("keybindings")) {
                JsonObject bindings = json.getAsJsonObject("keybindings");
                // Validate the complete saved layout atomically: otherwise a valid
                // keyboard swap could fail due to the order its entries are loaded.
                java.util.EnumMap<KeyBindings.Action, Integer> saved =
                        new java.util.EnumMap<>(KeyBindings.Action.class);
                for (KeyBindings.Action action : KeyBindings.Action.values()) {
                    String field = action.name().toLowerCase(java.util.Locale.ROOT);
                    if (bindings.has(field)) saved.put(action, bindings.get(field).getAsInt());
                }
                if (!settings.keyBindings.loadOverrides(saved)) {
                    System.err.println("Invalid saved keybindings; using defaults");
                }
            }
            if (json.has("vsync")) settings.vsync = json.get("vsync").getAsBoolean();
            if (json.has("crosshair")) settings.crosshair = json.get("crosshair").getAsBoolean();
            if (json.has("fullscreen")) settings.fullscreen = json.get("fullscreen").getAsBoolean();
            if (json.has("show_fps")) settings.showFps = json.get("show_fps").getAsBoolean();
            if (json.has("fov")) settings.setFov(json.get("fov").getAsInt());
            if (json.has("mouse_sensitivity")) {
                settings.mouseSensitivity = closestSensitivity(json.get("mouse_sensitivity").getAsFloat());
            }
            if (json.has("fps_limit")) settings.fpsLimit = normalizeFpsLimit(json.get("fps_limit").getAsInt());
            if (json.has("resolution_width") && json.has("resolution_height")) {
                int w = json.get("resolution_width").getAsInt();
                int h = json.get("resolution_height").getAsInt();
                if (w >= 640 && h >= 360 && w <= 7680 && h <= 4320) {
                    settings.resolutionWidth = w;
                    settings.resolutionHeight = h;
                }
            }
        } catch (IOException | RuntimeException exception) {
            System.err.println("Invalid settings; using defaults: " + exception.getMessage());
            return new GameSettings();
        }
        return settings;
    }

    public void save() throws IOException {
        JsonObject json = new JsonObject();
        json.addProperty("ui_scale", uiScalePercent);
        JsonObject bindingJson = new JsonObject();
        for (KeyBindings.Action action : KeyBindings.Action.values()) {
            bindingJson.addProperty(action.name().toLowerCase(java.util.Locale.ROOT), keyBindings.get(action));
        }
        json.add("keybindings", bindingJson);
        json.addProperty("vsync", vsync);
        json.addProperty("crosshair", crosshair);
        json.addProperty("fullscreen", fullscreen);
        json.addProperty("show_fps", showFps);
        json.addProperty("fov", fov);
        json.addProperty("mouse_sensitivity", mouseSensitivity);
        json.addProperty("fps_limit", fpsLimit);
        json.addProperty("resolution_width", resolutionWidth);
        json.addProperty("resolution_height", resolutionHeight);
        Files.createDirectories(FILE.getParent());
        Path temporary = FILE.resolveSibling(FILE.getFileName().toString() + ".tmp");
        try {
            Files.writeString(temporary,
                    new GsonBuilder().setPrettyPrinting().create().toJson(json) + "\n",
                    StandardCharsets.UTF_8);
            try {
                Files.move(temporary, FILE, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ex) {
                Files.move(temporary, FILE, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    public KeyBindings keyBindings() { return keyBindings; }
    public int uiScalePercent() { return uiScalePercent; }
    public float uiScale() { return uiScalePercent / 100f; }
    /** Global interface size, shared by every menu, inventory and HUD element. */
    public void setUiScalePercent(int percent) {
        uiScalePercent = normalizeUiScale(percent);
    }

    private static int normalizeUiScale(int percent) {
        int clamped = Math.max(MIN_UI_SCALE, Math.min(MAX_UI_SCALE, percent));
        return MIN_UI_SCALE + Math.round((clamped - MIN_UI_SCALE) / (float) UI_SCALE_STEP)
                * UI_SCALE_STEP;
    }
    public boolean vsync() { return vsync; }
    public boolean crosshair() { return crosshair; }
    public boolean fullscreen() { return fullscreen; }
    public boolean showFps() { return showFps; }
    public int fov() { return fov; }
    public int fpsLimit() { return fpsLimit; }
    public int resolutionWidth() { return resolutionWidth; }
    public int resolutionHeight() { return resolutionHeight; }
    public float mouseSensitivity() { return mouseSensitivity; }
    public int sensitivityPercent() { return Math.round(mouseSensitivity / 0.12f * 100f); }
    public String fpsLimitLabel() { return fpsLimit == 0 ? "UNLIMITED" : fpsLimit + " FPS"; }
    public String resolutionLabel() { return resolutionWidth + " X " + resolutionHeight; }

    public void toggleVsync() { vsync = !vsync; }
    public void toggleCrosshair() { crosshair = !crosshair; }
    public void toggleFullscreen() { fullscreen = !fullscreen; }
    public void toggleShowFps() { showFps = !showFps; }

    /** A continuously adjustable 1-degree FOV slider, not a preset cycle. */
    public void setFov(int degrees) {
        fov = Math.max(MIN_FOV, Math.min(MAX_FOV, degrees));
    }

    public void nextFpsLimit() {
        int current = indexOf(FPS_LIMITS, fpsLimit);
        fpsLimit = FPS_LIMITS[(current + 1) % FPS_LIMITS.length];
    }

    public void nextSensitivity() {
        mouseSensitivity = SENSITIVITY_VALUES[(indexOf(SENSITIVITY_VALUES, mouseSensitivity) + 1)
                % SENSITIVITY_VALUES.length];
    }

    /** Cycle only window sizes that fit the primary display. */
    public void nextResolution(int monitorWidth, int monitorHeight) {
        List<int[]> available = new ArrayList<>();
        for (int[] pair : RESOLUTIONS) {
            if (pair[0] <= monitorWidth && pair[1] <= monitorHeight) {
                available.add(pair);
            }
        }
        if (available.isEmpty()) return;
        int current = -1;
        for (int i = 0; i < available.size(); i++) {
            int[] pair = available.get(i);
            if (pair[0] == resolutionWidth && pair[1] == resolutionHeight) {
                current = i;
                break;
            }
        }
        int[] next = available.get((current + 1) % available.size());
        resolutionWidth = next[0];
        resolutionHeight = next[1];
    }

    private static int normalizeFpsLimit(int limit) {
        for (int option : FPS_LIMITS) if (option == limit) return limit;
        return 0;
    }

    private static int indexOf(int[] values, int current) {
        for (int i = 0; i < values.length; i++) if (values[i] == current) return i;
        return 0;
    }

    private static int indexOf(float[] values, float current) {
        for (int i = 0; i < values.length; i++) if (Math.abs(values[i] - current) < 0.001f) return i;
        return 2;
    }

    private static float closestSensitivity(float candidate) {
        if (!Float.isFinite(candidate)) return 0.12f;
        float best = SENSITIVITY_VALUES[0];
        for (float value : SENSITIVITY_VALUES) {
            if (Math.abs(value - candidate) < Math.abs(best - candidate)) best = value;
        }
        return best;
    }
}
