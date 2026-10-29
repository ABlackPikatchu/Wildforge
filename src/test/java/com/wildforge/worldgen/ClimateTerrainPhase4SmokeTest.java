package com.wildforge.worldgen;

/** No graphics or JSON dependencies. Terrain climate/spline deterministic checks. */
public final class ClimateTerrainPhase4SmokeTest {
    public static void main(String[] args) {
        WorldGenSettings settings=new WorldGenSettings(0,511,224,1400,180,320,54,1500,300,34,-.15,.3,.25,.6,.6);
        ClimateField a=new ClimateField(738271L,settings);
        ClimateField b=new ClimateField(738271L,settings);
        for(int x=-4096;x<=4096;x+=241)for(int z=-2048;z<=2048;z+=233) {
            ClimateField.Sample one=a.sample(x,z);
            if(!one.equals(b.sample(x,z)))throw new AssertionError("Climate nondeterminism");
            if(one.temperature()<0||one.temperature()>1||one.humidity()<0||one.humidity()>1)
                throw new AssertionError("Unbounded climate");
            double y=TerrainShaper.height(one,settings,.1,-.1,.2);
            if(y<settings.minY()+6||y>settings.maxY()-7)throw new AssertionError("Invalid world height "+y);
        }
        for(double v=-.5;v<=.5;v+=.004) {
            double y=TerrainShaper.spline(v,new double[]{-1,0,1},new double[]{-10,0,20});
            double next=TerrainShaper.spline(v+.00001,new double[]{-1,0,1},new double[]{-10,0,20});
            if(Math.abs(y-next)>.01)throw new AssertionError("Spline discontinuity");
        }
        System.out.println("Phase 4 climate and terrain-shaping smoke test passed");
    }
}
