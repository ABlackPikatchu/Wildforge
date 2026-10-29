package com.wildforge.world;

import com.wildforge.world.fluid.FluidEngine;
import java.util.HashMap;
import java.util.Map;

/** Fluid propagation and source withdrawal using a deterministic in-memory fixture. */
public final class FluidEngineSmokeTest {
    private static final class FakeWorld implements FluidEngine.WorldAccess {
        private record Position(int x,int y,int z) { }
        private final Map<Position,BlockType> blocks=new HashMap<>();
        private final Map<Position,Integer> strengths=new HashMap<>();
        private FluidEngine engine;
        private boolean simulate=true;
        private boolean loadEast=false;
        private Position pos(int x,int y,int z){return new Position(x,y,z);}
        @Override public boolean loaded(int x,int z){return x>=0 && x<(loadEast?32:16) && z>=0&&z<16;}
        @Override public boolean simulating(int x,int z){return simulate;}
        @Override public BlockType block(int x,int y,int z){
            if(y<=1)return BlockType.STONE;
            return blocks.getOrDefault(pos(x,y,z),BlockType.AIR);
        }
        @Override public int level(int x,int y,int z){
            BlockType b=block(x,y,z);
            if(b==BlockType.WATER||b==BlockType.LAVA)return 7;
            return strengths.getOrDefault(pos(x,y,z),6);
        }
        @Override public boolean change(int x,int y,int z,BlockType next){
            if(!loaded(x,z))return false;
            if(block(x,y,z)==next)return false;
            Position p=pos(x,y,z);
            blocks.put(p,next);strengths.remove(p);
            notifyNeighbors(x,y,z);
            return true;
        }
        private void notifyNeighbors(int x,int y,int z){
            engine.schedule(x+1,y,z);engine.schedule(x-1,y,z);
            engine.schedule(x,y,z+1);engine.schedule(x,y,z-1);
            engine.schedule(x,y+1,z);engine.schedule(x,y-1,z);
            engine.schedule(x,y,z);
        }
        @Override public boolean flowInto(int x,int y,int z,BlockType flow,int strength){
            if(!loaded(x,z)||y<2||strength<1)return false;
            BlockType existing=block(x,y,z);
            if(existing!=BlockType.AIR && existing!=flow)return false;
            if(existing==flow && level(x,y,z)>=strength)return false;
            blocks.put(pos(x,y,z),flow);strengths.put(pos(x,y,z),strength);
            engine.schedule(x,y,z);
            return true;
        }
        @Override public void weaken(int x,int y,int z,int level){strengths.put(pos(x,y,z),level);}
        long flowing(){return blocks.values().stream().filter(b->b==BlockType.FLOWING_WATER).count();}
    }
    public static void main(String[] args){
        FakeWorld world=new FakeWorld();
        FluidEngine fluid=new FluidEngine(world,512);world.engine=fluid;
        world.change(8,2,8,BlockType.WATER);
        for(int n=0;n<130;n++)fluid.step();
        check(world.flowing()>0,"source spreads across supporting floor");
        world.change(8,2,8,BlockType.AIR);
        for(int n=0;n<1300;n++)fluid.step();
        check(world.flowing()==0,"removing the only source drains downstream flows");
        fluid.reset();
        check(fluid.ticks()==0&&fluid.activeCount()==0&&fluid.sleepingCount()==0,"reset clears transient work");
        world.change(15,2,8,BlockType.WATER);
        fluid.schedule(15,2,8);
        for(int n=0;n<40;n++)fluid.step();
        check(world.block(16,2,8)==BlockType.AIR,"flow cannot cross unloaded column");
        world.loadEast=true;
        fluid.schedule(15,2,8);
        for(int n=0;n<40;n++)fluid.step();
        check(world.block(16,2,8)==BlockType.FLOWING_WATER,"loaded neighbor may receive flow");
        world.simulate=false;
        fluid.reclassify();
        check(fluid.activeCount()==0,"out-of-range work is no longer active");
        world.simulate=true;fluid.wake();
        check(fluid.activeCount()>0||fluid.sleepingCount()==0,"sleepers resume when simulation returns");
        System.out.println("Fluid engine: propagation, withdrawal, seams, sleeping and reset passed");
    }
    private static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
}
