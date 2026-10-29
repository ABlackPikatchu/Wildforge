package com.wildforge.world.mesh;

import java.util.Arrays;

/** Resizable primitive vertex buffer without boxed allocations in hot mesh paths. */
public final class FloatArrayBuilder {
    private float[] values = new float[1024];
    private int size;

    public void add(float value) {
        if (size == values.length) values = Arrays.copyOf(values, values.length * 2);
        values[size++] = value;
    }

    public float[] toArray() { return Arrays.copyOf(values, size); }
}
