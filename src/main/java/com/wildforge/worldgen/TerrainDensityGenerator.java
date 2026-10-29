package com.wildforge.worldgen;

/**
 * Seeded three-dimensional terrain density: broad climate height is the base,
 * while steep uplands gain 3D ridges, exposed ledges and small undercuts.
 * The transition is smooth in absolute world coordinates, including chunk seams.
 * This is NOT Minecraft's exact density-function graph.
 */
public final class TerrainDensityGenerator {
    private static final long RIDGE = 0x6d2e31a9L;
    private static final long DETAIL = 0x7bff42c1L;
    private static final long WARP = 0x716f75746572L;
    private final SeededNoise noise;
    private final int seaLevel;
    public TerrainDensityGenerator(long seed,int seaLevel) {
        this.noise=new SeededNoise(seed);
        this.seaLevel=seaLevel;
    }
    private static double smooth(double lo,double hi,double x) {
        double t=Math.max(0.0,Math.min(1.0,(x-lo)/(hi-lo)));
        return t*t*(3-2*t);
    }
    /** Sampling by y creates solid ledges and undersides as well as cliffs. */
    public double sample(int x,int y,int z,int surfaceY,int slope) {
        double depth=surfaceY+0.5-y;
        if(depth>32 || depth< -26)return depth;
        double rugged=smooth(3.0,11.0,slope)*smooth(seaLevel+16,seaLevel+78,surfaceY);
        if(rugged<.02)return depth;
        double broad=noise.noise3(x/54.0,y/37.0,z/54.0,RIDGE);
        double fine=noise.noise3(x/20.0,y/25.0,z/20.0,DETAIL);
        double warp=noise.noise3(x/83.0,y/58.0,z/83.0,WARP);
        double taper=1.0-smooth(13,32,Math.abs(depth));
        // A moderate offset protects topsoil and vegetation support, but allows
        // rock overhangs and recessed cliff faces in the upper 20 blocks.
        double displacement=(broad*12+fine*3+warp*4)*rugged*taper;
        // Exposed cliff faces are more variable than shallow grasslands.
        return depth+displacement;
    }
}
