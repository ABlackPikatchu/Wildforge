package com.wildforge.worldgen;

import java.util.EnumMap;
import java.util.ArrayList;
import java.util.Map;

/**
 * World-coordinate ecology sampler. Unlike sampling treeForCell at a fixed
 * terrain grid, this visits every candidate cell exactly once and reports
 * species and plant counts per square kilometre, with seed-reproducible output.
 */
public final class FeatureDistributionProfiler {
    private FeatureDistributionProfiler() {}
    public static void main(String[] args) throws Exception {
        long seed=args.length>0?Long.parseLong(args[0]):20261029L;
        int radius=args.length>1?Integer.parseInt(args[1]):256;
        if(radius<64||radius>1024)throw new IllegalArgumentException("Radius 64..1024 required");
        WorldGenerator gen=new WorldGenerator(seed,WorldGenConfigLoader.loadBuiltIn());
        var types=new EnumMap<WorldGenerator.TreeType,Integer>(WorldGenerator.TreeType.class);
        var trees=new ArrayList<WorldGenerator.Tree>();
        var plants=new java.util.TreeMap<String,Integer>();
        long start=System.nanoTime();
        int tStep=WorldGenerator.TREE_CELL_SIZE,pStep=WorldGenerator.PLANT_CELL_SIZE;
        for(int x=Math.floorDiv(-radius,tStep);x<=Math.floorDiv(radius,tStep);x++)
            for(int z=Math.floorDiv(-radius,tStep);z<=Math.floorDiv(radius,tStep);z++){
                WorldGenerator.Tree tree=gen.treeForCell(x,z);
                if(tree==null||Math.abs(tree.x())>radius||Math.abs(tree.z())>radius)continue;
                trees.add(tree);
                types.merge(tree.type(),1,Integer::sum);
            }
        long treeDone=System.nanoTime();
        for(int x=Math.floorDiv(-radius,pStep);x<=Math.floorDiv(radius,pStep);x++)
            for(int z=Math.floorDiv(-radius,pStep);z<=Math.floorDiv(radius,pStep);z++){
                WorldGenerator.Plant plant=gen.plantForCell(x,z);
                if(plant!=null&&Math.abs(plant.x())<=radius&&Math.abs(plant.z())<=radius)
                    plants.merge(plant.species(),1,Integer::sum);
            }
        double areaKm2=Math.pow((2*radius+1)/1000d,2);
        System.out.printf("Wildforge revision %d ecology | seed=%d | area=%.3f km2%n",
                WorldGenerator.CURRENT_REVISION,seed,areaKm2);
        System.out.printf("Trees: %d (%.0f/km2) | Plants: %d (%.0f/km2) | candidate time: tree %.1f ms, plant %.1f ms%n",
                trees.size(),trees.size()/areaKm2,plants.values().stream().mapToInt(Integer::intValue).sum(),
                plants.values().stream().mapToInt(Integer::intValue).sum()/areaKm2,
                (treeDone-start)/1e6,(System.nanoTime()-treeDone)/1e6);
        types.forEach((type,count)->System.out.printf("  tree %-12s %d%n",type,count));
        plants.forEach((type,count)->System.out.printf("  plant %-20s %d%n",type,count));
        // Exact closest-pair scan within spatial bins: trees that are far apart
        // cannot violate the minimum-spacing invariant. Avoid an O(N^2) scan
        // when profiling large regions with many thousands of trees.
        int minSq=Integer.MAX_VALUE;
        int cellSize=8;
        var buckets=new java.util.HashMap<Long,java.util.List<WorldGenerator.Tree>>();
        for(var tree:trees){
            int bx=Math.floorDiv(tree.x(),cellSize),bz=Math.floorDiv(tree.z(),cellSize);
            for(int dx=-1;dx<=1;dx++)for(int dz=-1;dz<=1;dz++){
                long id=((long)(bx+dx)<<32)|((bz+dz)&0xffff_ffffL);
                for(var other:buckets.getOrDefault(id,java.util.List.of())){
                    int sx=tree.x()-other.x(),sz=tree.z()-other.z();
                    minSq=Math.min(minSq,sx*sx+sz*sz);
                }
            }
            long id=((long)bx<<32)|(bz&0xffff_ffffL);
            buckets.computeIfAbsent(id,ignore->new ArrayList<>()).add(tree);
        }
        System.out.printf("Nearest tree-anchor separation: %.2f blocks%n",minSq==Integer.MAX_VALUE?0:Math.sqrt(minSq));
        if(minSq<TreeSettings.minTreeSpacingSquared())throw new AssertionError("Tree anchors overlap");
    }
    /** Keep profiler's invariant coupled to the same generated configuration. */
    private static final class TreeSettings {
        static int minTreeSpacingSquared(){
            int spacing=FeaturePlacementEngine.loadBuiltInUnchecked().treeMinSpacing();
            return spacing*spacing;
        }
    }
}
