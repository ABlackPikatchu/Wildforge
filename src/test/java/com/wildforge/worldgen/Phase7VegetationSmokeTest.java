package com.wildforge.worldgen;

import java.util.ArrayList;

/** Checks deterministic, chunk-border-independent species placement with generated catalogs. */
public final class Phase7VegetationSmokeTest {
    public static void main(String[] args) throws Exception {
        WorldGenSettings settings=WorldGenConfigLoader.loadBuiltIn();
        int spacing=FeaturePlacementEngine.loadBuiltIn().treeMinSpacing();
        if(spacing<2)throw new AssertionError("Expected generated minimum tree spacing");
        for(long seed:new long[]{20261029L,-2953407489294701774L}){
            var gen=new WorldGenerator(seed,settings);
            var trees=new ArrayList<WorldGenerator.Tree>();
            for(int x=-15;x<=15;x++)for(int z=-15;z<=15;z++){
                var t=gen.treeForCell(x,z);
                if(t!=null){
                    if(!t.equals(gen.treeForCell(x,z)))throw new AssertionError("Nondeterministic tree candidate");
                    for(var prev:trees){
                        int dx=t.x()-prev.x(),dz=t.z()-prev.z();
                        if(dx*dx+dz*dz<spacing*spacing)
                            throw new AssertionError("Tree collision across feature cells");
                    }
                    trees.add(t);
                }
            }
        }
        System.out.println("Phase 7 vegetation passed: generated spacing and deterministic cross-cell anchors");
    }
}
