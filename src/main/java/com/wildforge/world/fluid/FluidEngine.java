package com.wildforge.world.fluid;

import com.wildforge.world.BlockType;
import com.wildforge.world.ChunkCoordinates;
import java.util.*;

/**
 * Main-thread scheduled fluid simulation. The world owns persisted block/level data;
 * this engine owns only short-lived work, simulation-distance sleep, and tick ordering.
 * Never writes directly to chunk arrays or GPU meshes.
 */
public final class FluidEngine {
    public interface WorldAccess {
        boolean loaded(int x, int z);
        boolean simulating(int x, int z);
        BlockType block(int x, int y, int z);
        int level(int x, int y, int z);
        boolean change(int x, int y, int z, BlockType block);
        boolean flowInto(int x, int y, int z, BlockType block, int strength);
        void weaken(int x, int y, int z, int strength);
    }
    private record Cell(int x,int y,int z) { }
    private record Pending(Cell cell,long readyTick) { }
    private static final int[][] HORIZONTAL={{1,0},{-1,0},{0,1},{0,-1}};
    private static final int MAX_ACTIVE=8192;
    private static final int MAX_PARKED_PER_COLUMN=2048;
    private static final int WORK_PER_TICK=32;
    private final WorldAccess world;
    private final int worldHeight;
    private final PriorityQueue<Pending> active = new PriorityQueue<>(
            Comparator.comparingLong(Pending::readyTick)
                    .thenComparingInt(p->p.cell().x())
                    .thenComparingInt(p->p.cell().y())
                    .thenComparingInt(p->p.cell().z()));
    private final HashSet<Cell> queued = new HashSet<>();
    private final HashMap<Long, LinkedHashSet<Cell>> parked = new HashMap<>();
    private long ticks;
    private float accumulator;

    public FluidEngine(WorldAccess world,int worldHeight) {
        this.world=Objects.requireNonNull(world);
        if(worldHeight<4)throw new IllegalArgumentException("Invalid world height");
        this.worldHeight=worldHeight;
    }
    public void reset() { active.clear();queued.clear();parked.clear();ticks=0;accumulator=0f; }
    public int activeCount() { return active.size(); }
    public int sleepingCount() { int count=0;for(var c:parked.values())count+=c.size();return count; }
    public long ticks() { return ticks; }
    private static long key(int x,int z) { return ChunkCoordinates.pack(Math.floorDiv(x,16),Math.floorDiv(z,16)); }
    private void park(Cell c) {
        var set=parked.computeIfAbsent(key(c.x,c.z),unused->new LinkedHashSet<>());
        if(set.size()<MAX_PARKED_PER_COLUMN)set.add(c);
    }
    public void discardColumn(long key) {
        parked.remove(key);
        for(Iterator<Pending> it=active.iterator();it.hasNext();) {
            Pending p=it.next();
            if(key(p.cell.x,p.cell.z)==key) {it.remove();queued.remove(p.cell);}
        }
    }
    public void reclassify() {
        for(Iterator<Pending> it=active.iterator();it.hasNext();) {
            Pending p=it.next();Cell c=p.cell;
            if(world.simulating(c.x,c.z)&&world.loaded(c.x,c.z))continue;
            it.remove();queued.remove(c);
            if(world.loaded(c.x,c.z))park(c);
        }
    }
    public void wake() {
        var awake=new ArrayList<Cell>();
        for(Iterator<Map.Entry<Long,LinkedHashSet<Cell>>> it=parked.entrySet().iterator();it.hasNext();) {
            var entry=it.next();
            Cell first=entry.getValue().iterator().next();
            if(!world.loaded(first.x,first.z)||!world.simulating(first.x,first.z))continue;
            awake.addAll(entry.getValue());it.remove();
        }
        for(Cell c:awake)schedule(c.x,c.y,c.z);
    }
    public void schedule(int x,int y,int z) {
        if(y<=0||y>=worldHeight-1||!world.loaded(x,z))return;
        BlockType b=world.block(x,y,z);
        if(!b.isFluid())return;
        Cell c=new Cell(x,y,z);
        if(!world.simulating(x,z)||active.size()>=MAX_ACTIVE){park(c);return;}
        if(queued.add(c))active.add(new Pending(c,ticks+FluidRules.delay(b)));
    }
    public void advance(float seconds) {
        if(!Float.isFinite(seconds)||seconds<=0f)return;
        accumulator+=Math.min(seconds,.20f);
        int count=0;
        while(accumulator>=FluidRules.TICK_SECONDS&&count++<4){accumulator-=FluidRules.TICK_SECONDS;step();}
    }
    public void step() {
        ticks++;
        int processed=0;
        while(processed<WORK_PER_TICK&&!active.isEmpty()&&active.peek().readyTick<=ticks) {
            Pending p=active.remove();queued.remove(p.cell);processed++;
            Cell c=p.cell;
            if(!world.loaded(c.x,c.z))continue;
            if(!world.simulating(c.x,c.z)){park(c);continue;}
            update(c.x,c.y,c.z);
        }
        if((ticks&15L)==0&&active.size()<MAX_ACTIVE/2&&!parked.isEmpty())wake();
    }
    private void notifyAdjacent(int x,int y,int z) {
        schedule(x+1,y,z);schedule(x-1,y,z);schedule(x,y,z+1);schedule(x,y,z-1);
        schedule(x,y+1,z);schedule(x,y-1,z);
    }
    /** Determines whether flowing fluid still has a connected source of supply. */
    private int supportedStrength(int x,int y,int z,BlockType fluid) {
        boolean water=fluid.isWater();
        int best=0;
        BlockType above=world.block(x,y+1,z);
        if(above.isFluid()&&above.isWater()==water)
            best=Math.max(best,world.level(x,y+1,z)); // falling fluids do not decay
        for(int[] d:HORIZONTAL) {
            int nx=x+d[0],nz=z+d[1];
            if(!world.loaded(nx,nz)) {
                // An unloaded chunk is unknown, not empty: do not erase seam flows.
                return Math.max(best,world.level(x,y,z));
            }
            BlockType next=world.block(nx,y,nz);
            if(next.isFluid()&&next.isWater()==water)
                best=Math.max(best,world.level(nx,y,nz)-1);
        }
        return best;
    }
    private void update(int x,int y,int z) {
        BlockType type=world.block(x,y,z);
        if(!type.isFluid())return;
        if(type==BlockType.FLOWING_WATER||type==BlockType.FLOWING_LAVA) {
            // A removed source must drain its descendants, not leave permanent
            // disconnected shallow water. Sources themselves never withdraw.
            if(type==BlockType.FLOWING_WATER&&canFormSource(x,y,z)) {
                if(world.change(x,y,z,BlockType.WATER)){schedule(x,y,z);return;}
            }
            int current=world.level(x,y,z);
            int strength=supportedStrength(x,y,z,type);
            if(strength<=0) {
                if(world.change(x,y,z,BlockType.AIR))notifyAdjacent(x,y,z);
                return;
            }
            if(strength<current) {
                world.weaken(x,y,z,strength);
                notifyAdjacent(x,y,z);
            }
        }
        int strength=world.level(x,y,z);
        BlockType flow=type.isWater()?BlockType.FLOWING_WATER:BlockType.FLOWING_LAVA;
        if(world.flowInto(x,y-1,z,flow,strength))return;
        if(strength<=1)return;
        int next=strength-1;
        // Minecraft-like water spread preference: a flow sitting above solid
        // ground seeks a nearby downward opening before spreading sideways.
        // When no route exists within 3 steps it spreads in all directions.
        // The bounded search is deterministic and never enters unloaded chunks.
        int[] distances=new int[HORIZONTAL.length];
        int best=Integer.MAX_VALUE;
        for(int i=0;i<HORIZONTAL.length;i++) {
            int nx=x+HORIZONTAL[i][0],nz=z+HORIZONTAL[i][1];
            distances[i]=downhillDistance(nx,y,nz,3, x,z);
            best=Math.min(best,distances[i]);
        }
        for(int i=0;i<HORIZONTAL.length;i++) {
            if(best!=Integer.MAX_VALUE && distances[i]!=best)continue;
            int nx=x+HORIZONTAL[i][0],nz=z+HORIZONTAL[i][1];
            world.flowInto(nx,y,nz,flow,next);
        }
    }

    /** Bounded downhill search; shallow pools find their nearest spillway.
     * We only route across air or same-fluid cells, never through terrain. */
    private int downhillDistance(int x,int y,int z,int remaining,int originX,int originZ) {
        if(!world.loaded(x,z))return Integer.MAX_VALUE;
        BlockType current=world.block(x,y,z);
        if(current!=BlockType.AIR && !current.isFluid())return Integer.MAX_VALUE;
        if(world.block(x,y-1,z)==BlockType.AIR) return 0;
        if(remaining<=0)return Integer.MAX_VALUE;
        int shortest=Integer.MAX_VALUE;
        for(int[] d:HORIZONTAL) {
            int nx=x+d[0],nz=z+d[1];
            if(nx==originX && nz==originZ)continue;
            // Cycle-safe within the short local radius via Manhattan constraint.
            if(Math.abs(nx-originX)+Math.abs(nz-originZ)>4)continue;
            // Avoid unbounded recursion and stale unknown chunk edges.
            BlockType next=world.loaded(nx,nz)?world.block(nx,y,nz):BlockType.STONE;
            if(next!=BlockType.AIR)continue;
            int dist=downhillDistance(nx,y,nz,remaining-1,originX,originZ);
            if(dist!=Integer.MAX_VALUE)shortest=Math.min(shortest,dist+1);
        }
        return shortest;
    }
    private boolean canFormSource(int x,int y,int z) {
        if(y<1)return false;
        return FluidRules.formsWaterSource(world.block(x,y-1,z),world.block(x+1,y,z),
                world.block(x-1,y,z),world.block(x,y,z+1),world.block(x,y,z-1));
    }
}
