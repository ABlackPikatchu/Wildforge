package com.wildforge.lang;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.wildforge.content.ResourceId;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * UTF-8 language resources in assets/<namespace>/lang/<locale>.json.
 * Item/block text belongs in language files, not in content definitions.
 */
public final class LanguageManager {
    private static final Pattern LOCALE = Pattern.compile("[a-z]{2}_[a-z]{2}");
    private static volatile LanguageManager active = new LanguageManager(Map.of());

    private final Map<String, String> translations;

    private LanguageManager(Map<String, String> translations) {
        this.translations = Map.copyOf(translations);
    }

    public static LanguageManager loadBuiltIn() throws IOException {
        return load(LanguageManager.class.getClassLoader(), "wildforge", "en_us");
    }

    /** Load a language file without mutating the installed language (useful for tests). */
    public static LanguageManager load(ClassLoader classLoader, String namespace, String locale)
            throws IOException {
        Objects.requireNonNull(classLoader, "classLoader");
        if (!ResourceId.parse(namespace + ":check").getNamespace().equals(namespace)
                || locale == null || !LOCALE.matcher(locale).matches()) {
            throw new IOException("Invalid language namespace or locale");
        }
        String path = "assets/" + namespace + "/lang/" + locale + ".json";
        try (InputStream stream = classLoader.getResourceAsStream(path)) {
            if (stream == null) {
                throw new IOException("Missing language file: " + path);
            }
            try (Reader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
                JsonElement root = JsonParser.parseReader(reader);
                if (!root.isJsonObject()) {
                    throw new IOException("Language file must be a JSON object: " + path);
                }
                Map<String, String> values = new LinkedHashMap<>();
                JsonObject object = root.getAsJsonObject();
                for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
                    if (!entry.getValue().isJsonPrimitive()
                            || !entry.getValue().getAsJsonPrimitive().isString()
                            || entry.getValue().getAsString().isBlank()) {
                        throw new IOException("Invalid translation for " + entry.getKey());
                    }
                    values.put(entry.getKey(), entry.getValue().getAsString());
                }
                return new LanguageManager(values);
            }
        } catch (RuntimeException e) {
            throw new IOException("Invalid language JSON: " + path, e);
        }
    }

    /** Switch languages after they have been fully loaded. */
    public static void install(LanguageManager language) {
        active = Objects.requireNonNull(language, "language");
    }

    public static String translate(String key, String fallback) {
        return active.get(key, fallback);
    }

    public static String itemKey(ResourceId id) {
        return "item." + id.getNamespace() + "." + id.getPath().replace('/', '.');
    }

    public static String blockKey(ResourceId id) {
        return "block." + id.getNamespace() + "." + id.getPath().replace('/', '.');
    }

    public static String itemName(ResourceId id, String fallback) {
        return translate(itemKey(id), fallback);
    }

    public static String blockName(ResourceId id, String fallback) {
        return translate(blockKey(id), fallback);
    }

    public String get(String key, String fallback) {
        return translations.getOrDefault(key, fallback);
    }

    public boolean contains(String key) {
        return translations.containsKey(key);
    }

    public int size() {
        return translations.size();
    }
}
