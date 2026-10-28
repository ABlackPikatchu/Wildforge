package com.wildforge.content;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/** Negative tests for tag loading. Invoked by contentCheck. */
public final class TagValidationSmokeTest {
    private TagValidationSmokeTest() { }

    public static void run() throws IOException {
        assertInvalid("unknown member", Map.of(
                "test/index.json", index("data/wildforge/tags/blocks/a.json"),
                "data/wildforge/tags/blocks/a.json", tag("wildforge:nonexistent")));
        assertInvalid("unknown nested tag", Map.of(
                "test/index.json", index("data/wildforge/tags/blocks/a.json"),
                "data/wildforge/tags/blocks/a.json", tag("#wildforge:missing")));
        assertInvalid("cycle", Map.of(
                "test/index.json", index("data/wildforge/tags/blocks/a.json",
                        "data/wildforge/tags/blocks/b.json"),
                "data/wildforge/tags/blocks/a.json", tag("#wildforge:b"),
                "data/wildforge/tags/blocks/b.json", tag("#wildforge:a")));
    }

    private static String index(String... paths) {
        StringBuilder json = new StringBuilder("{\"blocks\":[],\"items\":[],\"block_tags\":[");
        for (int i = 0; i < paths.length; i++) {
            if (i > 0) json.append(',');
            json.append('"').append(paths[i]).append('"');
        }
        return json.append("]}").toString();
    }

    private static String tag(String value) {
        return "{\"replace\":false,\"values\":[\"" + value + "\"]}";
    }

    private static void assertInvalid(String expectedFailure, Map<String, String> fixtures)
            throws IOException {
        ClassLoader loader = new ClassLoader(TagValidationSmokeTest.class.getClassLoader()) {
            @Override
            public InputStream getResourceAsStream(String name) {
                String content = fixtures.get(name);
                return content == null ? super.getResourceAsStream(name)
                        : new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
            }
        };
        try {
            ContentLoader.load(loader, "test/index.json");
        } catch (IOException expected) {
            return;
        }
        throw new AssertionError("Tag loader must reject " + expectedFailure);
    }
}
