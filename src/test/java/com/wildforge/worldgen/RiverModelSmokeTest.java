package com.wildforge.worldgen;

import com.wildforge.world.BlockModels;
import com.wildforge.world.BlockState;
import com.wildforge.world.BlockType;

/** No OpenGL context: deterministic river incision + tall foliage model invariants. */
public final class RiverModelSmokeTest {
    private RiverModelSmokeTest() {}
    private static void check(boolean ok, String why) {
        if (!ok) throw new AssertionError(why);
    }

    public static void main(String[] args) {
        for (BlockType type : new BlockType[]{BlockType.TALL_GRASS_PLANT,
                BlockType.CATTAIL_PLANT, BlockType.REED_PLANT}) {
            check(BlockModels.modelFor(BlockState.fromLegacy(type)).shape()
                    == BlockModels.Shape.TALL_CROSS_PLANT,
                    "Tall model missing for " + type);
            check(!type.isSolid(), "Tall plant must be non-colliding: " + type);
        }
        HydrologyNetwork.HeightSampler hill = (x,z) -> 170.0 - x * 0.017 - z * 0.009
                + Math.sin(x / 110.0) * 4.0 + Math.cos(z / 145.0) * 3.0;
        HydrologyNetwork one = new HydrologyNetwork(0x4a11f45L, 64, hill);
        HydrologyNetwork two = new HydrologyNetwork(0x4a11f45L, 64, hill);
        int riverCells=0;
        for(int x=-500; x<=500; x+=17) for(int z=-500; z<=500; z+=19) {
            double original=hill.height(x,z);
            HydrologyNetwork.Sample a=one.sample(x,z,original);
            HydrologyNetwork.Sample b=two.sample(x,z,original);
            check(a.equals(b), "Order-independent hydrology changed at " + x + "," + z);
            check(a.groundY()<=original+1e-7, "Channel raised ground");
            if(a.kind()==HydrologyNetwork.Kind.RIVER && a.wet()) {
                riverCells++;
                check(a.waterY()<=original+1.01, "Elevated floating river column");
                check(a.waterY()-a.groundY()<=3.9,
                        "Unexpectedly deep river column: " + a);
            }
        }
        check(riverCells>0,"Synthetic slopes should produce at least one wet river cell");
        System.out.println("Patch 4.5: tall models and deterministic downhill channels passed ("
                + riverCells + " river samples)");
    }
}
