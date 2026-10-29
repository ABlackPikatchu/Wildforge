package com.wildforge.world;

import java.lang.reflect.Method;

/** Lightweight headless verification: fluid surfaces must rise with flow strength. */
public final class FluidGeometrySmokeTest {
    public static void main(String[] args) throws Exception {
        Method height = TerrainMesh.class.getDeclaredMethod("fluidSurfaceHeight", int.class);
        height.setAccessible(true);
        float previous = 0f;
        for(int strength=1; strength<=7; strength++) {
            float current=(float)height.invoke(null,strength);
            if(!Float.isFinite(current) || current<=previous || current>1f)
                throw new AssertionError("Non-monotonic fluid height at " + strength + ": " + current);
            previous=current;
        }
        System.out.println("Fluid surface heights: PASS (1..7 increasing)");
    }
}
