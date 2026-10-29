package com.wildforge.world.mesh;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * CPU render cache for one resident chunk column. Not world storage: the mesh
 * may be evicted or rebuilt without changing any blocks, edits, or save data.
 * GPU buffer ownership stays in Renderer.
 */
public final class ChunkRenderData {
    // GPU meshes are keyed by coordinates; a reloaded column must NEVER reuse
    // the old column's revision or the renderer can keep stale geometry.
    private static final AtomicInteger NEXT_REVISION = new AtomicInteger();
    private static int newRevision() { return NEXT_REVISION.incrementAndGet(); }
    private final float[][] terrain;
    private final float[][] fluids;
    private final int[] terrainRevision;
    private final int[] fluidRevision;
    private float[] distantTerrain;
    private float[] distantFluids;
    private int distantRevision = newRevision();

    public ChunkRenderData(int sectionCount) {
        if (sectionCount <= 0) throw new IllegalArgumentException("Invalid section count");
        terrain = new float[sectionCount][];
        fluids = new float[sectionCount][];
        terrainRevision = new int[sectionCount];
        fluidRevision = new int[sectionCount];
        for (int i = 0; i < sectionCount; i++) {
            terrain[i] = new float[0];
            fluids[i] = new float[0];
        }
    }

    public int sectionCount() { return terrain.length; }
    public void installSection(int section, float[] solid, float[] liquid) {
        terrain[section] = java.util.Objects.requireNonNull(solid, "solid mesh");
        fluids[section] = java.util.Objects.requireNonNull(liquid, "fluid mesh");
        terrainRevision[section] = newRevision();
        fluidRevision[section] = newRevision();
    }
    public float[] terrain(int section) { return terrain[section]; }
    public float[] fluid(int section) { return fluids[section]; }
    public int terrainRevision(int section) { return terrainRevision[section]; }
    public int fluidRevision(int section) { return fluidRevision[section]; }

    public void invalidateDistant() {
        distantTerrain = null;
        distantFluids = null;
        distantRevision = newRevision();
    }
    public void installDistant(float[] solid, float[] liquid) {
        distantTerrain = java.util.Objects.requireNonNull(solid, "distant terrain mesh");
        distantFluids = java.util.Objects.requireNonNull(liquid, "distant fluid mesh");
    }
    public float[] distantTerrain() { return distantTerrain; }
    public float[] distantFluids() { return distantFluids; }
    public int distantRevision() { return distantRevision; }
    public boolean hasDistant() { return distantTerrain != null; }
    public int floatCount() {
        int count = 0;
        for (int i = 0; i < terrain.length; i++) count += terrain[i].length + fluids[i].length;
        return count;
    }
    public void copyFloats(float[] dest, int startingOffset) {
        int at = startingOffset;
        for (int i = 0; i < terrain.length; i++) {
            System.arraycopy(terrain[i], 0, dest, at, terrain[i].length);
            at += terrain[i].length;
        }
        for (int i = 0; i < fluids.length; i++) {
            System.arraycopy(fluids[i], 0, dest, at, fluids[i].length);
            at += fluids[i].length;
        }
    }
}
