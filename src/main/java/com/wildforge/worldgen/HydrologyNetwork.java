package com.wildforge.worldgen;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Bounded, deterministic downhill drainage paths in world coordinates. Hydrology
 * describes geography only; it never schedules individual fluid ticks. A tile
 * gathers streams seeded up to one maximum path-length away, so chunk load order
 * cannot change a river or truncate it at a chunk border.
 */
public final class HydrologyNetwork {
    @FunctionalInterface public interface HeightSampler { double height(int x, int z); }
    public enum Kind { NONE, RIVER, LAKE }
    /** Surface height (after incision), fluid surface Y, and water coverage. */
    public record Sample(double groundY, int waterY, Kind kind, double drainage) {
        public boolean wet() { return kind != Kind.NONE && groundY < waterY; }
    }

    private static final int CELL = 320, STEP = 40, MAX_STEPS = 23, TILE = 96;
    private static final int MAX_REACH = STEP * MAX_STEPS + CELL;
    private static final long SOURCE_SALT = 0x4c616e6457617465L;
    private static final long LAKE_SALT = 0x536565704c616b65L;
    private final SeededNoise noise;
    private final HeightSampler heights;
    private final int sea;
    private final Map<Long, List<Shape>> tileCache = lru(384);
    private final Map<Long, List<Shape>> pathCache = lru(512);

    private interface Shape {}
    private record Segment(double ax, double az, double bx, double bz,
                           double aY, double bY, double width) implements Shape {}
    private record Lake(double x, double z, int level, double radius) implements Shape {}
    private static <T> Map<Long,T> lru(int max) {
        return new LinkedHashMap<>(128,.75f,true) {
            @Override protected boolean removeEldestEntry(Map.Entry<Long,T> eldest) {
                return size() > max;
            }
        };
    }
    private static long key(int x,int z) { return ((long)x << 32) ^ (z & 0xffffffffL); }
    public HydrologyNetwork(long seed, int sea, HeightSampler heights) {
        this.noise=new SeededNoise(seed);this.sea=sea;this.heights=heights;
    }
    private static double clamp(double value,double low,double high) {
        return Math.max(low,Math.min(high,value));
    }
    private static double smooth(double t) { return t*t*(3-2*t); }

    /** Sample once per terrain column. All geometry is read-only after construction. */
    public synchronized Sample sample(int x,int z,double baseHeight) {
        int tx=Math.floorDiv(x,TILE),tz=Math.floorDiv(z,TILE);
        long id=key(tx,tz);
        List<Shape> shapes=tileCache.get(id);
        if(shapes==null) { shapes=collect(tx,tz);tileCache.put(id,shapes); }
        Sample best=new Sample(baseHeight,sea,Kind.NONE,0);
        double strongest=0;
        for(Shape shape:shapes) {
            double distance, waterY, core, bank;
            Kind kind;
            if(shape instanceof Segment seg) {
                double vx=seg.bx-seg.ax,vz=seg.bz-seg.az;
                double t=clamp(((x-seg.ax)*vx+(z-seg.az)*vz)/(vx*vx+vz*vz),0,1);
                distance=Math.hypot(x-(seg.ax+t*vx),z-(seg.az+t*vz));
                core=seg.width;bank=core+8;
                if(distance>bank)continue;
                // The path's downhill water line is primary. Limit raised water
                // where small-scale relief drops away from the traced centerline;
                // free water physics, not worldgen, handles actual cliff falls.
                double routedLevel = seg.aY+(seg.bY-seg.aY)*t-1.65;
                waterY=Math.floor(Math.min(routedLevel,baseHeight+0.9));
                kind=Kind.RIVER;
            } else {
                Lake lake=(Lake)shape;
                distance=Math.hypot(x-lake.x,z-lake.z);
                core=lake.radius;bank=core+7;
                if(distance>bank)continue;
                waterY=lake.level;
                kind=Kind.LAKE;
            }
            // A river cannot float above its banks; suppress disconnected raised water.
            if(waterY>baseHeight+1.0 || waterY<sea-1)continue;
            double contribution=1.0-smooth(clamp((distance-core)/(bank-core),0,1));
            // Round-bottom channels: a deeper center, shallow shoulders and a
            // smooth bank. Avoid excavating a flat 3-block-deep trench everywhere.
            double channelDepth=kind==Kind.RIVER
                    ? 1.7 + 1.0*(1.0-smooth(clamp(distance/Math.max(1.0,core),0,1)))
                    : 2.7;
            double target=Math.min(baseHeight,waterY-channelDepth);
            double incised=baseHeight+(target-baseHeight)*contribution;
            double weight=contribution*(1.0-distance/bank);
            if(weight>strongest) {
                strongest=weight;
                int level=(int)waterY;
                boolean wetted=distance<=core && incised<level;
                best=new Sample(incised,level,wetted?kind:Kind.NONE,contribution);
            }
        }
        return best;
    }

    private List<Shape> collect(int tileX,int tileZ) {
        int minX=tileX*TILE,minZ=tileZ*TILE;
        // All sources whose maximum-length paths may reach the requested tile.
        int gx0=Math.floorDiv(minX-MAX_REACH,CELL),gx1=Math.floorDiv(minX+TILE+MAX_REACH,CELL);
        int gz0=Math.floorDiv(minZ-MAX_REACH,CELL),gz1=Math.floorDiv(minZ+TILE+MAX_REACH,CELL);
        List<Shape> candidates=new ArrayList<>();
        for(int gx=gx0;gx<=gx1;gx++)for(int gz=gz0;gz<=gz1;gz++) {
            long id=key(gx,gz);
            List<Shape> path=pathCache.get(id);
            if(path==null) {path=trace(gx,gz);pathCache.put(id,path);}
            for(Shape shape:path)if(intersects(shape,minX,minZ,TILE))candidates.add(shape);
        }
        return List.copyOf(candidates);
    }
    private static boolean intersects(Shape shape,int x,int z,int size) {
        double minX,maxX,minZ,maxZ;
        if(shape instanceof Segment s) {
            minX=Math.min(s.ax,s.bx)-s.width-9;maxX=Math.max(s.ax,s.bx)+s.width+9;
            minZ=Math.min(s.az,s.bz)-s.width-9;maxZ=Math.max(s.az,s.bz)+s.width+9;
        } else {
            Lake l=(Lake)shape;
            minX=l.x-l.radius-8;maxX=l.x+l.radius+8;
            minZ=l.z-l.radius-8;maxZ=l.z+l.radius+8;
        }
        return maxX>=x && minX<x+size && maxZ>=z && minZ<z+size;
    }
    private List<Shape> trace(int gx,int gz) {
        if(noise.unit(gx,0,gz,SOURCE_SALT)>.52)return List.of();
        int x=gx*CELL+40+(int)(noise.unit(gx,1,gz,SOURCE_SALT)*240);
        int z=gz*CELL+40+(int)(noise.unit(gx,2,gz,SOURCE_SALT)*240);
        double height=heights.height(x,z);
        if(height<sea+13 || height>sea+235)return List.of();
        List<Shape> path=new ArrayList<>();
        double px=x,pz=z;
        int lastDirection=-1;
        final int[] dx={1,1,0,-1,-1,-1,0,1},dz={0,1,1,1,0,-1,-1,-1};
        for(int step=0;step<MAX_STEPS;step++) {
            double lowest=height-0.12;
            int choice=-1;
            for(int dir=0;dir<8;dir++) {
                int nx=(int)Math.round(px)+dx[dir]*STEP;
                int nz=(int)Math.round(pz)+dz[dir]*STEP;
                double h=heights.height(nx,nz);
                // A little directional continuity, but never choose an uphill route.
                double cost=h+(lastDirection==dir?-0.20:0);
                if(h<height-.12 && cost<lowest) {lowest=cost;choice=dir;}
            }
            if(choice<0)break;
            double nx=px+dx[choice]*STEP,nz=pz+dz[choice]*STEP;
            double nextHeight=heights.height((int)nx,(int)nz);
            double width=3.2+Math.min(2.4,step*.11);
            path.add(new Segment(px,pz,nx,nz,height,nextHeight,width));
            px=nx;pz=nz;height=nextHeight;lastDirection=choice;
            if(height<=sea+3)break;
        }
        // Terminal closed basins may hold small inland lakes. Oceans are filled
        // by sea level separately, so no lake is placed on submerged terrain.
        if(path.size()>=2 && height>sea+4 && noise.unit(gx,3,gz,LAKE_SALT)<.60) {
            double r=10+noise.unit(gx,4,gz,LAKE_SALT)*16;
            path.add(new Lake(px,pz,(int)Math.floor(height-1),r));
        }
        return List.copyOf(path);
    }
}
