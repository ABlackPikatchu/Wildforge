package com.wildforge.worldgen;

/** Smooth, parameter-driven regional height functions. No biome type changes
 * terrain directly: shared climate axes cause mountains, shores and ecosystems
 * to align naturally. World-height and sea-level remain configured by Wildforge.
 */
public final class TerrainShaper {
    private TerrainShaper() {}
    private static double clamp(double v, double lo, double hi) { return Math.max(lo, Math.min(hi, v)); }
    private static double lerp(double a, double b, double t) { return a + (b-a)*t; }
    /** Continuously differentiable interpolation between control points. */
    public static double spline(double value, double[] knots, double[] heights) {
        if(knots.length != heights.length || knots.length < 2)
            throw new IllegalArgumentException("Spline knots/values mismatch");
        if (value <= knots[0]) return heights[0];
        for(int i=1;i<knots.length;i++) {
            if(knots[i] <= knots[i-1]) throw new IllegalArgumentException("Spline knots out of order");
            if(value <= knots[i]) {
                double t=(value-knots[i-1])/(knots[i]-knots[i-1]);
                t=t*t*(3-2*t);
                return lerp(heights[i-1],heights[i],t);
            }
        }
        return heights[heights.length-1];
    }

    /** Water-to-inland gradient plus ridges eroded into broad, walkable valleys. */
    public static double height(ClimateField.Sample c, WorldGenSettings settings,
                                double hills, double detail, double upland) {
        double land = spline(c.continentalness(),
                new double[]{-1.0,-.55,-.35,-.23,-.13,.02,.20,.43,.75,1.0},
                new double[]{-84,-60,-31,-7,2,10,19,27,35,39});
        // Damped coastal relief makes usable beaches; deep erosion broadens valleys.
        double inland = smooth(-.17,.14,c.continentalness());
        double rugged = 1.0 - smooth(-.32,.45,c.erosion());
        double uplift = smooth(-.20,.47,upland+c.continentalness()*.35);
        double peaks = Math.pow(clamp(c.peaksValleys(),0,1),3);
        double range = inland * uplift * rugged * (18+105*peaks);
        // Higher erosion generates flat valleys; protected ridges retain some foothills.
        double foothills = inland * uplift * (8+16*rugged);
        double micro = inland * ((3 + uplift*5)*hills + (1.2 + uplift*2.5)*detail);
        return clamp(settings.seaLevel()+land+range+foothills+micro,
                settings.minY()+6, settings.maxY()-7);
    }
    private static double smooth(double a,double b,double x) {
        double t=clamp((x-a)/(b-a),0,1);
        return t*t*(3-2*t);
    }
}
