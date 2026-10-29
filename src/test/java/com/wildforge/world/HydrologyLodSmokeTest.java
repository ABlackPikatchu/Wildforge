package com.wildforge.world;

import com.wildforge.entity.Entity;
import com.wildforge.entity.EntityManager;

/** Headless checks for Patch 3C simulation boundaries and adaptive distant water. */
public final class HydrologyLodSmokeTest {
    private HydrologyLodSmokeTest() {}
    private static void check(boolean condition,String message) {
        if(!condition)throw new AssertionError(message);
    }
    public static void main(String[] args) {
        // Positive/negative world coordinates and inclusive square chunk radius.
        check(TerrainMesh.withinSimulation(0,0,2,0,10),"Center is simulated");
        check(TerrainMesh.withinSimulation(0,0,2,47,-32),"Last radius chunk is simulated");
        check(!TerrainMesh.withinSimulation(0,0,2,48,0),"Chunk outside radius is inactive");
        check(!TerrainMesh.withinSimulation(0,0,2,-49,0),"Negative chunk outside radius is inactive");
        check(TerrainMesh.withinSimulation(-3,-2,2,-17,-1),"Negative center and floorDiv");
        check(!TerrainMesh.withinSimulation(-3,-2,2,0,-1),"Positive side of negative center boundary");

        check(TerrainMesh.shouldActivateFluidSeam(BlockType.WATER,7,BlockType.AIR,0),
                "Source water resumes across newly loaded empty seam");
        check(!TerrainMesh.shouldActivateFluidSeam(BlockType.WATER,7,BlockType.STONE,0),
                "Rock banks do not activate water");
        check(!TerrainMesh.shouldActivateFluidSeam(BlockType.WATER,7,BlockType.WATER,7),
                "Uniform ocean boundaries remain inert");
        check(TerrainMesh.shouldActivateFluidSeam(BlockType.WATER,7,BlockType.FLOWING_WATER,2),
                "Higher water level resumes toward weaker water");
        check(TerrainMesh.shouldActivateFluidSeam(BlockType.LAVA,7,BlockType.WATER,7),
                "Incompatible fluids resume for solidification");

        boolean[] ocean={true,true,true,true};
        int[] height={224,224,224,224};
        int[] level={7,7,7,7};
        check(!DistantWaterLod.needsFineTiles(ocean,height,level),"Uniform ocean uses 2x2");
        check(DistantWaterLod.needsFineTiles(new boolean[]{true,true,false,true},height,level),"Shore gets 1x1");
        check(DistantWaterLod.needsFineTiles(ocean,new int[]{224,224,223,224},level),"Stream drop gets 1x1");
        check(DistantWaterLod.needsFineTiles(ocean,height,new int[]{7,7,4,7}),"Flow slope gets 1x1");
        check(!DistantWaterLod.needsFineTiles(new boolean[]{false,false,false,false},
                new int[]{101,103,99,102},new int[4]),"Dry terrain stays coarse");
        try {
            DistantWaterLod.needsFineTiles(ocean,new int[]{1,2,3},level);
            throw new AssertionError("Malformed patch must fail");
        } catch(IllegalArgumentException expected) { /* okay */ }

        class CounterEntity extends Entity {
            int updates;
            CounterEntity(float x){super(x,20,0);}
            @Override public void update(float dt){updates++;}
        }
        EntityManager manager=new EntityManager();
        CounterEntity near=new CounterEntity(0),far=new CounterEntity(200);
        manager.spawn(near);manager.spawn(far);
        manager.update(.05f,e->e.getX()<100);
        check(near.updates==1 && far.updates==0,"Physics outside simulation radius is paused");
        manager.update(.05f,e->true);
        check(near.updates==2 && far.updates==1,"Physics resumes after reentering radius");
        check(manager.size()==2,"Paused entities must not despawn");
        System.out.println("HydrologyLodSmokeTest PASS: radius boundaries, water LOD and entity pause/resume");
    }
}
