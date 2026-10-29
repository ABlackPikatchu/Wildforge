package com.wildforge.world.light;

import com.wildforge.world.BlockType;
import java.util.ArrayDeque;

/**
 * Baked dual-channel light levels (0..15) for an immutable 16x16x512 chunk snapshot.
 * Direct skylight descends without attenuation through air. Indirect skylight
 * and lava block-light propagate with 1 level of loss per opaque-free step.
 * Fluid attenuates sky; lava is a light source. No mutable world access occurs
 * in mesh-worker threads. Dynamic cross-chunk relighting is still future work.
 */
public final class LightEngine {
    public static final int SIZE=16, HEIGHT=512, LENGTH=SIZE*HEIGHT*SIZE;
    private static final int[] DX={1,-1,0,0,0,0},DY={0,0,1,-1,0,0},DZ={0,0,0,0,1,-1};
    private final byte[] sky=new byte[LENGTH];
    private final byte[] block=new byte[LENGTH];
    private final byte[] blocks;
    private LightEngine(byte[] blocks){
        if(blocks.length!=LENGTH)throw new IllegalArgumentException("Expected 16x512x16 blocks");
        this.blocks=blocks;
    }
    private static int index(int x,int y,int z){return (x*SIZE+z)*HEIGHT+y;}
    private static boolean permeable(byte b){
        BlockType type=BlockType.fromId(b);
        return !type.isSolid() || type.name().contains("LEAVES") || type==BlockType.GLASS;
    }
    private static int attenuation(byte b){
        BlockType type=BlockType.fromId(b);
        return type.isWater() || type.isLava() || type.name().contains("LEAVES")?2:1;
    }
    public static LightEngine bake(byte[] snapshot){return bake(snapshot,null,null,null,null);}
    /** Neighbor edge slabs allow skylight and lava to cross a streamed seam.
     * Deeper indirect light in unloaded neighbors remains approximate. */
    public static LightEngine bake(byte[] snapshot,byte[] west,byte[] east,byte[] north,byte[] south){
        LightEngine result=new LightEngine(snapshot);
        ArrayDeque<Integer> skyQueue=new ArrayDeque<>(),blockQueue=new ArrayDeque<>();
        for(int x=0;x<SIZE;x++)for(int z=0;z<SIZE;z++){
            int incoming=15;
            for(int y=HEIGHT-1;y>=0;y--){
                int i=index(x,y,z); byte b=snapshot[i];
                if(!permeable(b))incoming=0;
                else if(incoming>0 && attenuation(b)>1)incoming=Math.max(0,incoming-1);
                if(incoming>0){result.sky[i]=(byte)incoming;skyQueue.add(i);}
                int emission=LightEmissionCatalog.emission(b);
                if(emission>0){result.block[i]=(byte)emission;blockQueue.add(i);}
            }
        }
        result.seedBoundary(west,0,true,skyQueue,blockQueue);
        result.seedBoundary(east,15,true,skyQueue,blockQueue);
        result.seedBoundary(north,0,false,skyQueue,blockQueue);
        result.seedBoundary(south,15,false,skyQueue,blockQueue);
        result.spread(result.sky,skyQueue);
        result.spread(result.block,blockQueue);
        return result;
    }
    private void seedBoundary(byte[] strip,int edge,boolean fixedX,
                              ArrayDeque<Integer> skyQueue,ArrayDeque<Integer> blockQueue) {
        if(strip==null)return;
        int h=HEIGHT;
        for(int along=0;along<SIZE;along++){
            int sunlight=15;
            for(int y=h-1;y>=0;y--){
                byte outside=strip[along*h+y];
                if(!permeable(outside))sunlight=0;
                else if(sunlight>0 && attenuation(outside)>1)sunlight=Math.max(0,sunlight-1);
                int x=fixedX?edge:along,z=fixedX?along:edge;
                int index=index(x,y,z);
                if(!permeable(blocks[index]))continue;
                int propagated=Math.max(0,sunlight-attenuation(blocks[index]));
                if(propagated>Byte.toUnsignedInt(sky[index])){
                    sky[index]=(byte)propagated;skyQueue.add(index);
                }
                int source=LightEmissionCatalog.emission(outside);
                if(source>0){
                    int next=Math.max(0,source-attenuation(blocks[index]));
                    if(next>Byte.toUnsignedInt(block[index])){
                        block[index]=(byte)next;blockQueue.add(index);
                    }
                }
            }
        }
    }
    private void spread(byte[] light,ArrayDeque<Integer> queue) {
        while(!queue.isEmpty()) {
            int current=queue.removeFirst(),amount=Byte.toUnsignedInt(light[current]);
            if(amount<=1)continue;
            int y=current%HEIGHT, x=current/(HEIGHT*SIZE), z=(current/HEIGHT)%SIZE;
            for(int dir=0;dir<6;dir++){
                int nx=x+DX[dir],ny=y+DY[dir],nz=z+DZ[dir];
                if(nx<0||nx>=SIZE||ny<0||ny>=HEIGHT||nz<0||nz>=SIZE)continue;
                int next=index(nx,ny,nz);
                if(!permeable(blocks[next]))continue;
                int level=amount-attenuation(blocks[next]);
                if(level>Byte.toUnsignedInt(light[next])){light[next]=(byte)level;queue.addLast(next);}
            }
        }
    }
    /** Does swapping these blocks change the ability of sky/block light to travel? */
    public static boolean sameTransmission(byte before, byte after) {
        return permeable(before) == permeable(after) && attenuation(before) == attenuation(after);
    }

    /**
     * Relight a single emission-only edit without scanning the entire 512-block column.
     * Rebuild the 15-block influence cube from its real emitters and unchanged outside
     * boundary. Rebuilding rather than simply propagating handles source removal as
     * well as source placement (prevents self-sustaining ghost-light cycles).
     *
     * Caller must ensure only one voxel changed and that the four boundary slabs
     * did not change. Returns null for edits that alter opacity/transmission.
     */
    public LightEngine withEmissionEdit(byte[] updated, int changed) {
        if (updated == null || updated.length != LENGTH || changed < 0 || changed >= LENGTH)
            throw new IllegalArgumentException("Invalid light edit");
        if (!sameTransmission(blocks[changed], updated[changed])) return null;
        LightEngine result = new LightEngine(updated);
        System.arraycopy(sky, 0, result.sky, 0, LENGTH);
        System.arraycopy(block, 0, result.block, 0, LENGTH);
        int ey=changed%HEIGHT, ex=changed/(SIZE*HEIGHT), ez=(changed/HEIGHT)%SIZE;
        int x0=Math.max(0,ex-15),x1=Math.min(SIZE-1,ex+15);
        int y0=Math.max(0,ey-15),y1=Math.min(HEIGHT-1,ey+15);
        int z0=Math.max(0,ez-15),z1=Math.min(SIZE-1,ez+15);
        ArrayDeque<Integer> frontier=new ArrayDeque<>();
        for(int x=x0;x<=x1;x++)for(int z=z0;z<=z1;z++)for(int y=y0;y<=y1;y++){
            int i=index(x,y,z);
            int base=LightEmissionCatalog.emission(updated[i]);
            result.block[i]=(byte)base;
            if(base>0)frontier.addLast(i);
        }
        // Seed with light that enters from strictly outside the affected cube.
        // No source edited here can reach beyond 15 steps, so this boundary is
        // unchanged. Values beyond chunk borders are handled by a full bake.
        for(int x=x0;x<=x1;x++)for(int z=z0;z<=z1;z++)for(int y=y0;y<=y1;y++){
            if(x!=x0&&x!=x1&&z!=z0&&z!=z1&&y!=y0&&y!=y1)continue;
            int i=index(x,y,z);
            if(!permeable(updated[i]))continue;
            int best=Byte.toUnsignedInt(result.block[i]);
            for(int d=0;d<6;d++){
                int nx=x+DX[d],ny=y+DY[d],nz=z+DZ[d];
                if(nx>=x0&&nx<=x1&&ny>=y0&&ny<=y1&&nz>=z0&&nz<=z1)continue;
                if(nx<0||nx>=SIZE||ny<0||ny>=HEIGHT||nz<0||nz>=SIZE)continue;
                best=Math.max(best, Byte.toUnsignedInt(block[index(nx,ny,nz)])-attenuation(updated[i]));
            }
            if(best>Byte.toUnsignedInt(result.block[i])){
                result.block[i]=(byte)best;
                frontier.addLast(i);
            }
        }
        while(!frontier.isEmpty()){
            int current=frontier.removeFirst();
            int amount=Byte.toUnsignedInt(result.block[current]);
            if(amount<=1)continue;
            int y=current%HEIGHT,x=current/(HEIGHT*SIZE),z=(current/HEIGHT)%SIZE;
            for(int d=0;d<6;d++){
                int nx=x+DX[d],ny=y+DY[d],nz=z+DZ[d];
                if(nx<x0||nx>x1||ny<y0||ny>y1||nz<z0||nz>z1)continue;
                int i=index(nx,ny,nz);
                if(!permeable(updated[i]))continue;
                int next=amount-attenuation(updated[i]);
                if(next>Byte.toUnsignedInt(result.block[i])){
                    result.block[i]=(byte)next;
                    frontier.addLast(i);
                }
            }
        }
        return result;
    }

    public int sky(int x,int y,int z){return sample(sky,x,y,z);}
    public int block(int x,int y,int z){return sample(block,x,y,z);}
    private static int sample(byte[] source,int x,int y,int z){
        if(y>=HEIGHT)return 15;
        if(x<0||x>=SIZE||z<0||z>=SIZE||y<0)return 0;
        return Byte.toUnsignedInt(source[index(x,y,z)]);
    }
    public float brightness(int x,int y,int z) {
        int level=Math.max(sky(x,y,z),block(x,y,z));
        return .12f+.88f*level/15f;
    }
    /** Adjacent voxel sampling avoids black faces on completely opaque blocks. */
    public float vertexBrightness(float worldX,float worldY,float worldZ,int chunkX,int chunkZ) {
        float lx=worldX-chunkX*SIZE, lz=worldZ-chunkZ*SIZE;
        int x=(int)Math.floor(lx),y=(int)Math.floor(worldY),z=(int)Math.floor(lz);
        float best=brightness(x,y,z);
        if(lx==(float)Math.floor(lx))best=Math.max(best,Math.max(brightness(x-1,y,z),brightness(x+1,y,z)));
        if(lz==(float)Math.floor(lz))best=Math.max(best,Math.max(brightness(x,y,z-1),brightness(x,y,z+1)));
        if(worldY==(float)Math.floor(worldY))best=Math.max(best,Math.max(brightness(x,y-1,z),brightness(x,y+1,z)));
        return best;
    }
}
