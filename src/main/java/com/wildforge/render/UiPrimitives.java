package com.wildforge.render;

import java.util.List;

/** CPU-side vertices for solid UI rectangles. Coordinates stay in virtual pixels. */
final class UiPrimitives {
    private UiPrimitives() { }

    static void rect(
            List<Float> data, float x, float y,
            float width, float height,
            float r, float g, float b, float a) {

        vertex(data, x, y, r, g, b, a);
        vertex(data, x + width, y, r, g, b, a);
        vertex(data, x + width, y + height, r, g, b, a);

        vertex(data, x, y, r, g, b, a);
        vertex(data, x + width, y + height, r, g, b, a);
        vertex(data, x, y + height, r, g, b, a);
    }

    private static void vertex(
            List<Float> data, float x, float y,
            float r, float g, float b, float a) {

        data.add(x);
        data.add(y);
        data.add(r);
        data.add(g);
        data.add(b);
        data.add(a);
    }

}
