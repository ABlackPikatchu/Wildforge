package com.wildforge.core;

import org.lwjgl.glfw.GLFW;

import java.util.EnumMap;
import java.util.Map;

/** Saved, conflict-free gameplay bindings. Escape remains reserved for navigation. */
public final class KeyBindings {
    public enum Action {
        FORWARD("MOVE FORWARD", GLFW.GLFW_KEY_W),
        BACKWARD("MOVE BACKWARD", GLFW.GLFW_KEY_S),
        LEFT("STRAFE LEFT", GLFW.GLFW_KEY_A),
        RIGHT("STRAFE RIGHT", GLFW.GLFW_KEY_D),
        JUMP("JUMP", GLFW.GLFW_KEY_SPACE),
        INVENTORY("INVENTORY", GLFW.GLFW_KEY_E),
        HOTBAR_1("HOTBAR 1", GLFW.GLFW_KEY_1),
        HOTBAR_2("HOTBAR 2", GLFW.GLFW_KEY_2),
        HOTBAR_3("HOTBAR 3", GLFW.GLFW_KEY_3),
        HOTBAR_4("HOTBAR 4", GLFW.GLFW_KEY_4),
        HOTBAR_5("HOTBAR 5", GLFW.GLFW_KEY_5),
        HOTBAR_6("HOTBAR 6", GLFW.GLFW_KEY_6),
        HOTBAR_7("HOTBAR 7", GLFW.GLFW_KEY_7),
        HOTBAR_8("HOTBAR 8", GLFW.GLFW_KEY_8),
        HOTBAR_9("HOTBAR 9", GLFW.GLFW_KEY_9);

        private final String label;
        private final int defaultKey;
        Action(String label, int defaultKey) {
            this.label = label;
            this.defaultKey = defaultKey;
        }
        public String label() { return label; }
        public int defaultKey() { return defaultKey; }
        public static Action at(int index) {
            return index < 0 || index >= values().length ? null : values()[index];
        }
    }

    private final EnumMap<Action, Integer> keys = new EnumMap<>(Action.class);

    public KeyBindings() { resetDefaults(); }

    public void resetDefaults() {
        for (Action action : Action.values()) keys.put(action, action.defaultKey());
    }

    public int get(Action action) { return keys.get(action); }

    /** Returns false for invalid, reserved, or already-used keys. */
    public boolean bind(Action action, int key) {
        if (action == null || !validKey(key)) {
            return false;
        }
        for (Map.Entry<Action, Integer> entry : keys.entrySet()) {
            if (entry.getKey() != action && entry.getValue() == key) return false;
        }
        keys.put(action, key);
        return true;
    }

    /** Apply a complete saved mapping atomically; an invalid file cannot partially corrupt controls. */
    public boolean loadOverrides(Map<Action, Integer> overrides) {
        if (overrides == null) return false;
        EnumMap<Action, Integer> candidate = new EnumMap<>(keys);
        candidate.putAll(overrides);
        java.util.HashSet<Integer> seen = new java.util.HashSet<>();
        for (Action action : Action.values()) {
            Integer key = candidate.get(action);
            if (key == null || !validKey(key) || !seen.add(key)) return false;
        }
        keys.clear();
        keys.putAll(candidate);
        return true;
    }

    private static boolean validKey(int key) {
        return key >= GLFW.GLFW_KEY_SPACE && key <= GLFW.GLFW_KEY_LAST
                && key != GLFW.GLFW_KEY_ESCAPE && key != GLFW.GLFW_KEY_UNKNOWN;
    }

    public Action conflict(Action action, int key) {
        for (Action candidate : Action.values()) {
            if (candidate != action && get(candidate) == key) return candidate;
        }
        return null;
    }

    public String keyLabel(Action action) { return keyName(get(action)); }

    public static String keyName(int key) {
        if (key == GLFW.GLFW_KEY_SPACE) return "SPACE";
        if (key == GLFW.GLFW_KEY_LEFT_SHIFT) return "L SHIFT";
        if (key == GLFW.GLFW_KEY_RIGHT_SHIFT) return "R SHIFT";
        if (key == GLFW.GLFW_KEY_LEFT_CONTROL) return "L CTRL";
        if (key == GLFW.GLFW_KEY_RIGHT_CONTROL) return "R CTRL";
        if (key == GLFW.GLFW_KEY_TAB) return "TAB";
        if (key == GLFW.GLFW_KEY_ENTER) return "ENTER";
        if (key == GLFW.GLFW_KEY_BACKSPACE) return "BACKSPACE";
        if (key == GLFW.GLFW_KEY_UP) return "UP";
        if (key == GLFW.GLFW_KEY_DOWN) return "DOWN";
        if (key == GLFW.GLFW_KEY_LEFT) return "LEFT";
        if (key == GLFW.GLFW_KEY_RIGHT) return "RIGHT";
        if (key >= GLFW.GLFW_KEY_F1 && key <= GLFW.GLFW_KEY_F25)
            return "F" + (key - GLFW.GLFW_KEY_F1 + 1);
        String printed = GLFW.glfwGetKeyName(key, 0);
        if (printed != null && !printed.isBlank()) return printed.toUpperCase(java.util.Locale.ROOT);
        return "KEY " + key;
    }
}
