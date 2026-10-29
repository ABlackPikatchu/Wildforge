package com.wildforge.worldgen;

/** Standalone regression test; no graphics context or game saves needed. */
public final class HydrologyNetworkSmokeTest {
    private HydrologyNetworkSmokeTest() {}
    private static double height(int x,int z) {
        return 245+30*Math.sin(x/340.0)+16*Math.cos(z/400.0)+x*.008;
    }
    public static void main(String[] args) {
        HydrologyNetwork a=new HydrologyNetwork(20261029L,225,HydrologyNetworkSmokeTest::height);
        HydrologyNetwork b=new HydrologyNetwork(20261029L,225,HydrologyNetworkSmokeTest::height);
        int waters=0;
        // Cross both negative and positive tile and chunk boundaries.
        for(int x=-1024;x<=1024;x+=8)for(int z=-1024;z<=1024;z+=8) {
            var one=a.sample(x,z,height(x,z));
            var two=b.sample(x,z,height(x,z));
            if(!one.equals(two))throw new AssertionError("Hydrology depends on sample order");
            if(one.groundY()>height(x,z)+.001)throw new AssertionError("Drainage raised the ground");
            if(one.wet() && one.groundY()>=one.waterY())
                throw new AssertionError("Invalid lake/river surface level");
            if(one.wet())waters++;
        }
        if(waters<5)throw new AssertionError("No water channels produced in test basin");
        for(int x=-100;x<100;x++) {
            var one=a.sample(x,96,height(x,96));
            var two=b.sample(x,96,height(x,96));
            if(!one.equals(two))throw new AssertionError("Tile seam mismatch");
        }
        System.out.println("Hydrology deterministic: "+waters+" river/lake samples, no raised ground");
    }
}
