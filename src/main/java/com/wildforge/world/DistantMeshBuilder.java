package com.wildforge.world;

import com.wildforge.worldgen.WorldGenerator;
import com.wildforge.world.fluid.FluidRules;
import com.wildforge.world.mesh.FloatArrayBuilder;
import java.util.Map;
import static com.wildforge.world.TerrainMesh.CHUNK_SIZE;
import static com.wildforge.world.TerrainMesh.WORLD_HEIGHT;

/** CPU-only distant land and water meshes; caller passes an immutable chunk snapshot. */
final class DistantMeshBuilder {
    private DistantMeshBuilder() {}
    private static int index(int x,int y,int z) {return ChunkCoordinates.localIndex(x,y,z);}
    private static float fluidSurfaceHeight(int level) {return FluidRules.surfaceHeight(Math.max(1,Math.min(7,level)));}
    private static int snapshotFluidLevel(byte type,Map<Integer,Byte> levels,int index) {
        if(type==BlockType.WATER.id || type==BlockType.LAVA.id)return 7;
        if(type==BlockType.FLOWING_WATER.id || type==BlockType.FLOWING_LAVA.id)
            return Byte.toUnsignedInt(levels.getOrDefault(index,(byte)6));
        return 0;
    }
    private static BlockType treeLog(WorldGenerator.TreeType type) {
        return switch(type) {
            case OAK -> BlockType.OAK_LOG; case MAPLE -> BlockType.MAPLE_LOG;
            case PINE -> BlockType.PINE_LOG; case CYPRESS -> BlockType.CYPRESS_LOG;
            case WILLOW -> BlockType.WILLOW_LOG; case BIRCH -> BlockType.BIRCH_LOG;
            case SPRUCE -> BlockType.SPRUCE_LOG; case ACACIA -> BlockType.ACACIA_LOG;
            case ALDER -> BlockType.ALDER_LOG; case MANGROVE -> BlockType.MANGROVE_LOG;
            case PALM -> BlockType.PALM_LOG;
        };
    }
    private static int leafLayer(WorldGenerator.TreeType species) {
        return TreeFoliage.leafFor(species).getSideTextureLayer();
    }
    static ChunkMeshScheduler.LodMeshes build(ChunkColumn c,Map<Integer,Byte> levels,WorldGenerator generator) {
        // Oceans retain inexpensive 2x2 tiles. Streams, coastlines and height / flow
        // transitions subdivide into actual 1x1 water/ground cells, preserving banks.
        final int step=2,tiles=CHUNK_SIZE/step;
        int[][] top=new int[CHUNK_SIZE][CHUNK_SIZE], layer=new int[CHUNK_SIZE][CHUNK_SIZE];
        int[][] ground=new int[CHUNK_SIZE][CHUNK_SIZE],groundLayer=new int[CHUNK_SIZE][CHUNK_SIZE];
        int[][] flow=new int[CHUNK_SIZE][CHUNK_SIZE];
        boolean[][] wet=new boolean[CHUNK_SIZE][CHUNK_SIZE];
        int ox=c.cx*CHUNK_SIZE,oz=c.cz*CHUNK_SIZE;
        for(int lx=0;lx<CHUNK_SIZE;lx++)for(int lz=0;lz<CHUNK_SIZE;lz++) {
            WorldGenerator.Surface surface=generator.surface(ox+lx,oz+lz);
            int h=surface.waterBody()!=WorldGenerator.WaterBody.LAND
                    ? Math.max(surface.surfaceY(),surface.waterSurfaceY()):surface.surfaceY();
            h=Math.max(0,Math.min(WORLD_HEIGHT-2,h));
            // A generated short waterfall may protrude above the usual waterline.
            for(int candidate=Math.min(WORLD_HEIGHT-2,h+3);candidate>h;candidate--)
                if(BlockType.fromId(c.get(index(lx,candidate,lz))).isFluid()) {h=candidate;break;}
            top[lx][lz]=h;
            int cell=index(lx,h,lz);
            byte id=c.get(cell);
            BlockType b=BlockType.fromId(id);
            wet[lx][lz]=b.isFluid();
            flow[lx][lz]=wet[lx][lz]?snapshotFluidLevel(id,levels,cell):0;
            layer[lx][lz]=b.getTopTextureLayer();
            int bed=Math.max(0,Math.min(WORLD_HEIGHT-1,surface.surfaceY()));
            int lower=Math.max(0,bed-12);
            while(bed>lower && !BlockType.fromId(c.get(index(lx,bed,lz))).isSolid())bed--;
            ground[lx][lz]=bed;
            BlockType bedType=BlockType.fromId(c.get(index(lx,bed,lz)));
            groundLayer[lx][lz]=(bedType.isSolid()?bedType:BlockType.STONE).getTopTextureLayer();
        }
        FloatArrayBuilder out=new FloatArrayBuilder();
        FloatArrayBuilder water=new FloatArrayBuilder();
        for(int tx=0;tx<tiles;tx++)for(int tz=0;tz<tiles;tz++) {
            int lx=tx*step,lz=tz*step,x=ox+lx,z=oz+lz;
            boolean[] patchWet={wet[lx][lz],wet[lx+1][lz],wet[lx][lz+1],wet[lx+1][lz+1]};
            int[] patchHeight={top[lx][lz],top[lx+1][lz],top[lx][lz+1],top[lx+1][lz+1]};
            int[] patchFlow={flow[lx][lz],flow[lx+1][lz],flow[lx][lz+1],flow[lx+1][lz+1]};
            if(DistantWaterLod.needsFineTiles(patchWet,patchHeight,patchFlow)) {
                for(int dx=0;dx<2;dx++)for(int dz=0;dz<2;dz++)
                    drawDistantCell(out,water,generator,ox,oz,lx+dx,lz+dz,top,layer,
                            ground,groundLayer,wet,flow);
                continue;
            }
            int h=top[lx][lz],tex=layer[lx][lz];
            if(wet[lx][lz]) {
                float waterY=h+fluidSurfaceHeight(flow[lx][lz]);
                face(water,x,waterY,z+step,x+step,waterY,z+step,
                           x+step,waterY,z,x,waterY,z,tex,1f);
                float bedY=ground[lx][lz]+1f;
                face(out,x,bedY,z+step,x+step,bedY,z+step,
                         x+step,bedY,z,x,bedY,z,groundLayer[lx][lz],1f);
                continue;
            }
            float y=h+1f;
            face(out,x,y,z+step,x+step,y,z+step,x+step,y,z,x,y,z,tex,1f);
            int south=lz+step<CHUNK_SIZE?top[lx][lz+step]:distantSurfaceY(generator,x,z+step);
            int north=lz>0?top[lx][lz-1]:distantSurfaceY(generator,x,z-step);
            int east=lx+step<CHUNK_SIZE?top[lx+step][lz]:distantSurfaceY(generator,x+step,z);
            int west=lx>0?top[lx-1][lz]:distantSurfaceY(generator,x-step,z);
            if(h>south)face(out,x,south+1,z+step,x+step,south+1,z+step,x+step,y,z+step,x,y,z+step,tex,.85f);
            if(h>north)face(out,x+step,north+1,z,x,north+1,z,x,y,z,x+step,y,z,tex,.75f);
            if(h>east)face(out,x+step,east+1,z+step,x+step,east+1,z,x+step,y,z,x+step,y,z+step,tex,.80f);
            if(h>west)face(out,x,west+1,z,x,west+1,z+step,x,y,z+step,x,y,z,tex,.80f);
        }
        // Very cheap, coherent tree proxies replace naked trunk spikes at long range.
        // Only the anchor's own chunk emits a proxy: no cross-chunk duplicates.
        int tc=WorldGenerator.TREE_CELL_SIZE;
        for(int tx=Math.floorDiv(ox,tc);tx<=Math.floorDiv(ox+CHUNK_SIZE-1,tc);tx++)
            for(int tz=Math.floorDiv(oz,tc);tz<=Math.floorDiv(oz+CHUNK_SIZE-1,tc);tz++) {
                WorldGenerator.Tree tree=generator.treeForCell(tx,tz);
                if(tree==null||tree.x()<ox||tree.x()>=ox+CHUNK_SIZE||tree.z()<oz||tree.z()>=oz+CHUNK_SIZE)continue;
                int treeGround=generator.surface(tree.x(),tree.z()).surfaceY()+1;
                if(treeGround<1 || treeGround>=WORLD_HEIGHT)continue;
                // LOD must reflect player edits; don't draw a phantom tree after
                // its base log has been mined from the live chunk.
                if(c.get(index(tree.x()-ox,treeGround,tree.z()-oz))!=treeLog(tree.type()).id)continue;
                int crownY=treeGround+tree.trunkHeight();
                int r=switch(tree.type()) {
                    case OAK,MAPLE,ALDER,ACACIA,MANGROVE -> 3;
                    case WILLOW -> 4;
                    case PINE,CYPRESS,SPRUCE,BIRCH,PALM -> 2;
                };
                int depth=switch(tree.type()) {
                    case PINE,CYPRESS,SPRUCE -> 7; case PALM -> 2;
                    default -> 4;
                };
                int logTex=treeLog(tree.type()).getSideTextureLayer();
                float x=tree.x()+.5f,z=tree.z()+.5f;
                // Six-sided trunk prism and a staggered 3D foliage canopy.
                lodBox(out,x-.48f,treeGround,z-.48f,x+.48f,crownY,z+.48f,logTex);
                int leaf=leafLayer(tree.type());
                int n=tree.type()==WorldGenerator.TreeType.SPRUCE || tree.type()==WorldGenerator.TreeType.PINE ? 3:2;
                for(int layerIndex=0;layerIndex<n;layerIndex++) {
                    float width=Math.max(1.1f,r*(layerIndex==0?1.0f:layerIndex==1?.73f:.43f));
                    float low=crownY-depth+layerIndex*(depth/(float)n);
                    float high=crownY-depth+(layerIndex+1)*depth/(float)n+.65f;
                    lodBox(out,x-width,low,z-width,x+width,high,z+width,leaf);
                }
            }
        return new ChunkMeshScheduler.LodMeshes(out.toArray(),water.toArray());
    }

    private static void drawDistantCell(FloatArrayBuilder out,FloatArrayBuilder water,WorldGenerator generator,int ox,int oz,
            int lx,int lz,int[][] top,int[][] layer,int[][] ground,int[][] groundLayer,
            boolean[][] wet,int[][] flow) {
        int x=ox+lx,z=oz+lz,h=top[lx][lz],tex=layer[lx][lz];
        float y=h+(wet[lx][lz]?fluidSurfaceHeight(flow[lx][lz]):1f);
        if(wet[lx][lz]) {
            face(water,x,y,z+1,x+1,y,z+1,x+1,y,z,x,y,z,tex,1f);
            float bed=ground[lx][lz]+1f;
            face(out,x,bed,z+1,x+1,bed,z+1,x+1,bed,z,x,bed,z,groundLayer[lx][lz],1f);
        } else face(out,x,y,z+1,x+1,y,z+1,x+1,y,z,x,y,z,tex,1f);
        for(int[] d:new int[][]{{0,1},{0,-1},{1,0},{-1,0}}) {
            int nx=lx+d[0],nz=lz+d[1];
            float neighborY;
            if(nx>=0&&nx<CHUNK_SIZE&&nz>=0&&nz<CHUNK_SIZE)
                neighborY=top[nx][nz]+(wet[nx][nz]?fluidSurfaceHeight(flow[nx][nz]):1f);
            else neighborY=distantSurfaceY(generator,ox+nx,oz+nz)+1f;
            if(y<=neighborY+.01f)continue;
            // Long waterfall walls remain visible at distance, but only at the
            // edge of water, never throughout a submerged ocean volume.
            FloatArrayBuilder target=wet[lx][lz]?water:out;
            if(d[0]==0&&d[1]==1)
                face(target,x,neighborY,z+1,x+1,neighborY,z+1,x+1,y,z+1,x,y,z+1,tex,.86f);
            else if(d[0]==0)
                face(target,x+1,neighborY,z,x,neighborY,z,x,y,z,x+1,y,z,tex,.76f);
            else if(d[0]==1)
                face(target,x+1,neighborY,z+1,x+1,neighborY,z,x+1,y,z,x+1,y,z+1,tex,.82f);
            else
                face(target,x,neighborY,z,x,neighborY,z+1,x,y,z+1,x,y,z,tex,.80f);
        }
    }
    private static void lodBox(FloatArrayBuilder out,float x0,float y0,float z0,float x1,float y1,float z1,int tex) {
        face(out,x0,y0,z1,x1,y0,z1,x1,y1,z1,x0,y1,z1,tex,.88f);
        face(out,x1,y0,z0,x0,y0,z0,x0,y1,z0,x1,y1,z0,tex,.76f);
        face(out,x1,y0,z1,x1,y0,z0,x1,y1,z0,x1,y1,z1,tex,.84f);
        face(out,x0,y0,z0,x0,y0,z1,x0,y1,z1,x0,y1,z0,tex,.80f);
        face(out,x0,y1,z1,x1,y1,z1,x1,y1,z0,x0,y1,z0,tex,1f);
    }
    private static int distantSurfaceY(WorldGenerator generator,int x,int z) {
        WorldGenerator.Surface surface=generator.surface(x,z);
        return surface.waterBody()!=WorldGenerator.WaterBody.LAND
                ? Math.max(surface.surfaceY(),surface.waterSurfaceY()) : surface.surfaceY();
    }
    private static void face(
            FloatArrayBuilder out,
            float ax, float ay, float az,
            float bx, float by, float bz,
            float cx, float cy, float cz,
            float dx, float dy, float dz,
            int textureLayer,
            float shade
    ) {
        // Six vertices forming two triangles. Each vertex contains
        // XYZ, UV, texture layer, and face shade. Append directly to the
        // primitive buffer to avoid per-face arrays and boxed Float objects.
        vertex(out, ax, ay, az, 0f, 1f, textureLayer, shade);
        vertex(out, bx, by, bz, 1f, 1f, textureLayer, shade);
        vertex(out, cx, cy, cz, 1f, 0f, textureLayer, shade);

        vertex(out, ax, ay, az, 0f, 1f, textureLayer, shade);
        vertex(out, cx, cy, cz, 1f, 0f, textureLayer, shade);
        vertex(out, dx, dy, dz, 0f, 0f, textureLayer, shade);
    }

    private static void vertex(
            FloatArrayBuilder out,
            float x, float y, float z,
            float u, float v,
            int textureLayer,
            float shade
    ) {
        out.add(x);
        out.add(y);
        out.add(z);
        out.add(u);
        out.add(v);
        out.add(textureLayer);
        out.add(shade);
    }

}
