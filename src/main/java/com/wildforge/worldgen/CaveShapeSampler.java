package com.wildforge.worldgen;

/** Deterministic three-mode density carving: intersecting winding tunnels,
 * scattered large cheese caverns, and rare thin noodle passages. Region-scale
 * geology-specific tunnels are added later by CaveCarvers. All cave cells must
 * remain beneath an unbroken natural roof and above the bedrock gradient.
 */
public final class CaveShapeSampler {
    private final SeededNoise noise;
    private final WorldGenSettings settings;
    public CaveShapeSampler(long seed,WorldGenSettings settings){
        noise=new SeededNoise(seed);this.settings=settings;
    }
    public boolean carved(int x,int y,int z,int surfaceY){
        int depth=surfaceY-y;
        if(y<settings.minY()+5||depth<14)return false;
        double taper=clamp((depth-14)/20.0);
        taper=taper*taper*(3-2*taper);
        double threshold=settings.caveThreshold()*(.45+.55*taper);
        double main=noise.noise3(x/settings.caveScale(),y/(settings.caveScale()*.73),z/settings.caveScale(),0x80abcdL);
        double breakup=noise.noise3(x/(settings.caveScale()*.68),y/(settings.caveScale()*.59),z/(settings.caveScale()*.68),0x90abcdL);
        // Spaghetti: two independent near-zero ridges intersect into tube networks.
        if(Math.abs(main)<threshold && Math.abs(breakup)<threshold*(1.5+.65*taper))return true;
        // Cheese: broad, rare chambers in the deep crust; the second field prevents
        // continuous giant air sheets across regional boundaries.
        if(depth>=48&&main>.35&&breakup>.18)return true;
        // Noodles: extremely narrow branches concentrated away from shallow roofs.
        return depth>=28 && Math.abs(main)<.024 && Math.abs(breakup+.21)<.045;
    }
    private static double clamp(double value){return Math.max(0,Math.min(1,value));}
}
