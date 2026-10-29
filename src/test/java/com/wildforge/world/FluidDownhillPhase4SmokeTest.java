package com.wildforge.world;

import com.wildforge.world.fluid.FluidEngine;
import java.util.HashMap;
import java.util.Map;

/** Flow should seek a nearby drop rather than making identical horizontal sheets. */
public final class FluidDownhillPhase4SmokeTest {
    private record Pos(int x,int y,int z) {}
    private static final class World implements FluidEngine.WorldAccess {
        final Map<Pos,BlockType> blocks=new HashMap<>();
        final Map<Pos,Integer> levels=new HashMap<>();
        FluidEngine engine;
        public boolean loaded(int x,int z){return x>=0&&x<32&&z>=0&&z<32;}
        public boolean simulating(int x,int z){return true;}
        public BlockType block(int x,int y,int z){
            if(y<=2 && !(x==10&&y==2&&z==8))return BlockType.STONE;
            return blocks.getOrDefault(new Pos(x,y,z),BlockType.AIR);
        }
        public int level(int x,int y,int z){
            var b=block(x,y,z);
            return b==BlockType.WATER?7:levels.getOrDefault(new Pos(x,y,z),6);
        }
        public boolean change(int x,int y,int z,BlockType b){
            var p=new Pos(x,y,z);if(block(x,y,z)==b)return false;
            blocks.put(p,b);engine.schedule(x,y,z);return true;
        }
        public boolean flowInto(int x,int y,int z,BlockType flow,int strength){
            if(!loaded(x,z)||block(x,y,z)!=BlockType.AIR)return false;
            var p=new Pos(x,y,z);blocks.put(p,flow);levels.put(p,strength);engine.schedule(x,y,z);return true;
        }
        public void weaken(int x,int y,int z,int strength){levels.put(new Pos(x,y,z),strength);}
    }
    public static void main(String[] args) {
        World world=new World();FluidEngine engine=new FluidEngine(world,512);world.engine=engine;
        world.change(8,3,8,BlockType.WATER);
        for(int i=0;i<12;i++)engine.step();
        if(world.block(9,3,8)!=BlockType.FLOWING_WATER)
            throw new AssertionError("Fluid did not choose the nearby downhill opening");
        if(world.block(7,3,8)==BlockType.FLOWING_WATER)
            throw new AssertionError("Fluid ignored nearer downhill opening");
        System.out.println("Phase 4 downhill fluid preference passed");
    }
}
