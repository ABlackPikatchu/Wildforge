package com.wildforge.world;

import com.wildforge.worldgen.WorldGenerator;
import com.wildforge.world.mesh.FloatArrayBuilder;
import com.wildforge.world.fluid.FluidRules;
import java.util.*;
import java.util.concurrent.CancellationException;
import static com.wildforge.world.TerrainMesh.CHUNK_SIZE;
import static com.wildforge.world.TerrainMesh.WORLD_HEIGHT;
import static com.wildforge.world.TerrainMesh.SECTION_COUNT;
import static com.wildforge.world.TerrainMesh.SECTION_HEIGHT;

/** Pure CPU geometry builder; never reads mutable live chunks or schedules jobs. */
final class SectionMeshBuilder {
    private SectionMeshBuilder() {}
    public record Snapshot(int cx, int cz, byte[] blocks, byte[] west, byte[] east, byte[] north, byte[] south,
                           Map<Integer,Byte> flows, Map<Integer,Byte> westFlows, Map<Integer,Byte> eastFlows,
                           Map<Integer,Byte> northFlows, Map<Integer,Byte> southFlows, WorldGenerator generator) {}
    private static final byte LEGACY_FLOW_LEVEL=6;
    private static int index(int x,int y,int z){ return ChunkCoordinates.localIndex(x,y,z); }
    private static int snapshotFluidLevel(byte type,Map<Integer,Byte> levels,int i) {
        if(type==BlockType.WATER.id||type==BlockType.LAVA.id)return 7;
        if(type==BlockType.FLOWING_WATER.id||type==BlockType.FLOWING_LAVA.id)
            return Byte.toUnsignedInt(levels.getOrDefault(i,LEGACY_FLOW_LEVEL));
        return 0;
    }
    private static float fluidSurfaceHeight(int level) {
        return FluidRules.surfaceHeight(Math.max(1,Math.min(7,level)));
    }
    private static long rockHash(int x,int y,int z) {
        long v=((long)x*0x9E3779B97F4A7C15L)^((long)y*0xD6E8FEB86659FD93L)
            ^((long)z*0xC2B2AE3D27D4EB4FL);
        v=(v^(v>>>30))*0xbf58476d1ce4e5b9L;
        v=(v^(v>>>27))*0x94d049bb133111ebL;
        return v^(v>>>31);
    }

    static float[][] buildSelectedMeshes(Snapshot snap,BitSet sections) {
        float[][] built=new float[SECTION_COUNT*2][];
        for(int i=sections.nextSetBit(0);i>=0;i=sections.nextSetBit(i+1)) {
            if(Thread.currentThread().isInterrupted())throw new CancellationException();
            float[][] layers=buildSectionLayers(snap,i);
            built[i]=layers[0];
            built[i+SECTION_COUNT]=layers[1];
        }
        return built;
    }
    private static float fluidSideBase(byte current,byte[] neighbor,int idx,
                                       Map<Integer,Byte> levels,int flowIndex,boolean exposed,int y,float top) {
        if(exposed)return y;
        if(neighbor==null)return top;
        byte other=neighbor[idx];
        if(!fluidId(other))return top;
        boolean same=(current==BlockType.WATER.id||current==BlockType.FLOWING_WATER.id)==
                     (other==BlockType.WATER.id||other==BlockType.FLOWING_WATER.id);
        if(!same)return top;
        // When the neighboring column is also submerged, no interior side wall
        // is needed. Avoid thousands of tiny faces throughout deep oceans.
        if(y<WORLD_HEIGHT-1 && fluidId(neighbor[idx+1]) &&
           ((neighbor[idx+1]==BlockType.WATER.id||neighbor[idx+1]==BlockType.FLOWING_WATER.id)==
            (current==BlockType.WATER.id||current==BlockType.FLOWING_WATER.id)))return top;
        float neighborY=y+fluidSurfaceHeight(snapshotFluidLevel(other,levels,flowIndex));
        return neighborY<top-.005f?neighborY:top;
    }
    private static boolean fluidId(byte id) {
        return id==BlockType.WATER.id||id==BlockType.LAVA.id||
               id==BlockType.FLOWING_WATER.id||id==BlockType.FLOWING_LAVA.id;
    }
    private static boolean faceVisible(byte current,byte neighbor) {
        if(neighbor==BlockType.AIR.id)return true;
        if(fluidId(current)) {
            if(!fluidId(neighbor))return false;
            boolean water=current==BlockType.WATER.id||current==BlockType.FLOWING_WATER.id;
            boolean other=neighbor==BlockType.WATER.id||neighbor==BlockType.FLOWING_WATER.id;
            return water!=other;
        }
        if(fluidId(neighbor))return true;
        BlockModels.Model currentModel=BlockModels.modelFor(BlockState.fromLegacyId(current));
        BlockModels.Model otherModel=BlockModels.modelFor(BlockState.fromLegacyId(neighbor));
        if(currentModel.pass()==BlockModels.Pass.CUTOUT && current==neighbor)return false;
        return !otherModel.fullCubeOcclusion();
    }
    // Never mix translucent fluids into the opaque / cutout terrain mesh.
    // Both streams use the same immutable neighbor snapshot and revision epoch.
    private static int snapshotLevel(Snapshot snap,int x,int y,int z,byte fluidId) {
        byte[] blocks=snap.blocks();Map<Integer,Byte> levels=snap.flows();
        if(x<0) {blocks=snap.west();levels=snap.westFlows();x+=CHUNK_SIZE;}
        else if(x>=CHUNK_SIZE){blocks=snap.east();levels=snap.eastFlows();x-=CHUNK_SIZE;}
        if(z<0) {if(blocks!=snap.blocks())return -1;blocks=snap.north();levels=snap.northFlows();z+=CHUNK_SIZE;}
        else if(z>=CHUNK_SIZE){if(blocks!=snap.blocks())return -1;blocks=snap.south();levels=snap.southFlows();z-=CHUNK_SIZE;}
        if(blocks==null||y<0||y>=WORLD_HEIGHT)return -1;
        int flowIndex=index(x,y,z);
        int i=blocks==snap.blocks()?flowIndex:(x*WORLD_HEIGHT+y);
        if(blocks==snap.west()||blocks==snap.east())i=z*WORLD_HEIGHT+y;
        byte id=blocks[i];
        if(!fluidId(id)||((id==BlockType.WATER.id||id==BlockType.FLOWING_WATER.id)!=
           (fluidId==BlockType.WATER.id||fluidId==BlockType.FLOWING_WATER.id)))return -1;
        return snapshotFluidLevel(id,levels,flowIndex);
    }
    private static float fluidCornerHeight(Snapshot snap,int lx,int y,int lz,
                                           byte id,int cornerX,int cornerZ,float originalTop) {
        if(originalTop>=y+.999f)return originalTop;
        float sum=0f;int samples=0;
        for(int sx=cornerX-1;sx<=cornerX;sx++)for(int sz=cornerZ-1;sz<=cornerZ;sz++) {
            int xx=lx+sx,zz=lz+sz;
            int level=snapshotLevel(snap,xx,y,zz,id);
            if(level<0)continue;
            float height=fluidSurfaceHeight(level);
            if(snapshotLevel(snap,xx,y+1,zz,id)>=0)height=1f;
            sum+=height;samples++;
        }
        return samples==0?originalTop:y+Math.min(1f,sum/samples);
    }
    /** Direction is packed in the unused integer part of fluid vertex shade. */
    private static int fluidFlowCode(Snapshot snap,int x,int y,int z,byte id) {
        if(id!=BlockType.FLOWING_WATER.id)return 0;
        int east=Math.max(0,snapshotLevel(snap,x+1,y,z,id));
        int west=Math.max(0,snapshotLevel(snap,x-1,y,z,id));
        int north=Math.max(0,snapshotLevel(snap,x,y,z-1,id));
        int south=Math.max(0,snapshotLevel(snap,x,y,z+1,id));
        int dx=west-east,dz=north-south;
        if(Math.abs(dx)>=Math.abs(dz) && dx!=0)return dx>0?1:2;
        if(dz!=0)return dz>0?3:4;
        return 0;
    }
    private static float[][] buildSectionLayers(Snapshot snap,int section){
        FloatArrayBuilder data=new FloatArrayBuilder();
        FloatArrayBuilder fluid=new FloatArrayBuilder();
        final byte[] blocks=snap.blocks();
        final byte[] westBlocks=snap.west();
        final byte[] eastBlocks=snap.east();
        final byte[] northBlocks=snap.north();
        final byte[] southBlocks=snap.south();
        final byte air=BlockType.AIR.id;
        for(int lx=0;lx<CHUNK_SIZE;lx++)for(int lz=0;lz<CHUNK_SIZE;lz++){
            int column=index(lx,0,lz);
            int worldX=snap.cx()*CHUNK_SIZE+lx,worldZ=snap.cz()*CHUNK_SIZE+lz;
            int northColumn=lz==0?lx*WORLD_HEIGHT:index(lx,0,lz-1);
            int southColumn=lz==CHUNK_SIZE-1?lx*WORLD_HEIGHT:index(lx,0,lz+1);
            int westColumn=lx==0?lz*WORLD_HEIGHT:index(lx-1,0,lz);
            int eastColumn=lx==CHUNK_SIZE-1?lz*WORLD_HEIGHT:index(lx+1,0,lz);
            byte[] northSource=lz==0?northBlocks:blocks;
            byte[] southSource=lz==CHUNK_SIZE-1?southBlocks:blocks;
            byte[] westSource=lx==0?westBlocks:blocks;
            byte[] eastSource=lx==CHUNK_SIZE-1?eastBlocks:blocks;
            for(int y=section*SECTION_HEIGHT;y<(section+1)*SECTION_HEIGHT;y++){
                byte id=blocks[column+y];
                if(id==air)continue;
                BlockType type=BlockType.fromId(id);
                BlockModels.Shape shape=BlockModels.modelFor(BlockState.fromLegacy(type)).shape();
                if(type.isSnowLayer()) {
                    float top=y+type.collisionHeight();
                    int x=worldX,z=worldZ,tex=type.getTopTextureLayer();
                    BlockType above=y==WORLD_HEIGHT-1?BlockType.AIR:BlockType.fromId(blocks[column+y+1]);
                    if(above==BlockType.AIR || (above.isSnowLayer() && above.collisionHeight()<type.collisionHeight()))
                        face(data,x,top,z+1,x+1,top,z+1,x+1,top,z,x,top,z,tex,1f);
                    byte[][] neighbors={northSource,southSource,westSource,eastSource};
                    int[] offsets={northColumn+y,southColumn+y,westColumn+y,eastColumn+y};
                    float[] shade={.75f,.85f,.8f,.8f};
                    for(int side=0;side<4;side++) {
                        BlockType neighbor=neighbors[side]==null?BlockType.AIR:BlockType.fromId(neighbors[side][offsets[side]]);
                        float h=neighbor.isSnowLayer()?neighbor.collisionHeight():(neighbor.isSolid()?1f:0f);
                        if(h>=type.collisionHeight())continue;
                        float bottom=y+h;
                        switch(side) {
                            case 0 -> face(data,x+1,bottom,z,x,bottom,z,x,top,z,x+1,top,z,tex,shade[side]);
                            case 1 -> face(data,x,bottom,z+1,x+1,bottom,z+1,x+1,top,z+1,x,top,z+1,tex,shade[side]);
                            case 2 -> face(data,x,bottom,z,x,bottom,z+1,x,top,z+1,x,top,z,tex,shade[side]);
                            case 3 -> face(data,x+1,bottom,z+1,x+1,bottom,z,x+1,top,z,x+1,top,z+1,tex,shade[side]);
                        }
                    }
                    continue;
                }
                if(shape==BlockModels.Shape.TALL_CROSS_PLANT) {
                    // The bottom cell owns the plant; upper geometry is drawn
                    // only into available air, with no duplicate inventory state.
                    // This keeps saves compatible and avoids cross-chunk plants.
                    boolean freeAbove=y+1<WORLD_HEIGHT && blocks[column+y+1]==air;
                    crossPlant(data,worldX,y,worldZ,type.getSideTextureLayer(),
                            freeAbove?1.82f:0.88f);
                    continue;
                }
                if(shape==BlockModels.Shape.CROSS_PLANT || shape==BlockModels.Shape.CEILING_ATTACHMENT) {
                    crossPlantAt(data,worldX,y,worldZ,type.getSideTextureLayer(),
                            shape==BlockModels.Shape.CEILING_ATTACHMENT);
                    continue;
                }
                if(shape==BlockModels.Shape.SPIKE) {
                    spike(data,worldX,y,worldZ,type.getSideTextureLayer(),type==BlockType.STALACTITE);
                    continue;
                }
                if(shape==BlockModels.Shape.CRYSTAL) {
                    crystalCluster(data,worldX,y,worldZ,type.getSideTextureLayer());
                    continue;
                }
                boolean showNorth=northSource==null||faceVisible(id,northSource[northColumn+y]);
                boolean showSouth=southSource==null||faceVisible(id,southSource[southColumn+y]);
                boolean showWest=westSource==null||faceVisible(id,westSource[westColumn+y]);
                boolean showEast=eastSource==null||faceVisible(id,eastSource[eastColumn+y]);
                boolean showTop=y==WORLD_HEIGHT-1||faceVisible(id,blocks[column+y+1]);
                boolean showBottom=y==0||faceVisible(id,blocks[column+y-1]);
                if(!(showNorth||showSouth||showWest||showEast||showTop||showBottom))continue;
                BlockType block=BlockType.fromId(id);
                int x=worldX,z=worldZ;
                if(block.isFluid()) {
                    // A water surface is below the voxel ceiling, while a fully
                    // submerged fluid cell fills its entire vertical slot.
                    // FLOWING_* IDs are the available coarse flow-state signal.
                    int fluidLevel=snapshotFluidLevel(id,snap.flows(),column+y);
                    float top = y + (showTop ? fluidSurfaceHeight(fluidLevel) : 1f);
                    // Draw exposed vertical fluid face from the adjacent (possibly lower) water level.
                    // The neighbor's flow metadata travels with the immutable mesh snapshot.
                    float southBase=fluidSideBase(id,southSource,southColumn+y,
                            lz==CHUNK_SIZE-1?snap.southFlows():snap.flows(),(lz==CHUNK_SIZE-1?index(lx,y,0):southColumn+y),showSouth,y,top);
                    float northBase=fluidSideBase(id,northSource,northColumn+y,
                            lz==0?snap.northFlows():snap.flows(),(lz==0?index(lx,y,CHUNK_SIZE-1):northColumn+y),showNorth,y,top);
                    float eastBase=fluidSideBase(id,eastSource,eastColumn+y,
                            lx==CHUNK_SIZE-1?snap.eastFlows():snap.flows(),(lx==CHUNK_SIZE-1?index(0,y,lz):eastColumn+y),showEast,y,top);
                    float westBase=fluidSideBase(id,westSource,westColumn+y,
                            lx==0?snap.westFlows():snap.flows(),(lx==0?index(CHUNK_SIZE-1,y,lz):westColumn+y),showWest,y,top);
                    // Shared 2x2 corner sampling makes adjacent flow levels form slopes,
                    // instead of disconnected horizontal shelves and sheer square lips.
                    float nw=fluidCornerHeight(snap,lx,y,lz,id,0,0,top);
                    float ne=fluidCornerHeight(snap,lx,y,lz,id,1,0,top);
                    float sw=fluidCornerHeight(snap,lx,y,lz,id,0,1,top);
                    float se=fluidCornerHeight(snap,lx,y,lz,id,1,1,top);
                    int flowDir=fluidFlowCode(snap,lx,y,lz,id);
                    float anim=(block.isWater()?2f*flowDir:0f);
                    if(southBase<Math.max(sw,se))face(fluid,x,southBase,z+1,x+1,southBase,z+1,x+1,se,z+1,x,sw,z+1,block.textureForAxis(2),.86f+anim);
                    if(northBase<Math.max(nw,ne))face(fluid,x+1,northBase,z,x,northBase,z,x,nw,z,x+1,ne,z,block.textureForAxis(2),.78f+anim);
                    if(eastBase<Math.max(ne,se))face(fluid,x+1,eastBase,z+1,x+1,eastBase,z,x+1,ne,z,x+1,se,z+1,block.textureForAxis(0),.82f+anim);
                    if(westBase<Math.max(nw,sw))face(fluid,x,westBase,z,x,westBase,z+1,x,sw,z+1,x,nw,z,block.textureForAxis(0),.82f+anim);
                    if(showTop)face(fluid,x,sw,z+1,x+1,se,z+1,x+1,ne,z,x,nw,z,block.textureForAxis(1),1f+anim);
                    if(showBottom)face(fluid,x,y,z,x+1,y,z,x+1,y,z+1,x,y,z+1,block.getBottomTextureLayer(),.72f+anim);
                    continue;
                }
                if(showSouth)face(data,x,y,z+1,x+1,y,z+1,x+1,y+1,z+1,x,y+1,z+1,block.textureForFace(BlockModels.Face.SOUTH),.85f);
                if(showNorth)face(data,x+1,y,z,x,y,z,x,y+1,z,x+1,y+1,z,block.textureForFace(BlockModels.Face.NORTH),.75f);
                if(showEast)face(data,x+1,y,z+1,x+1,y,z,x+1,y+1,z,x+1,y+1,z+1,block.textureForFace(BlockModels.Face.EAST),.8f);
                if(showWest)face(data,x,y,z,x,y,z+1,x,y+1,z+1,x,y+1,z,block.textureForFace(BlockModels.Face.WEST),.8f);
                if(showTop)face(data,x,y+1,z+1,x+1,y+1,z+1,x+1,y+1,z,x,y+1,z,block.textureForFace(BlockModels.Face.UP),1f);
                if(showBottom)face(data,x,y,z,x+1,y,z,x+1,y,z+1,x,y,z+1,
                        block.textureForFace(BlockModels.Face.DOWN),.55f);
            }
        }
        appendFoliage(snap,data,section);
        return new float[][]{data.toArray(),fluid.toArray()};
    }
    private static BlockType snapshotBlockAt(Snapshot snap,int x,int y,int z) {
        if(y<0||y>=WORLD_HEIGHT)return BlockType.AIR;
        int cx=Math.floorDiv(x,CHUNK_SIZE),cz=Math.floorDiv(z,CHUNK_SIZE);
        byte[] source;
        if(cx==snap.cx()&&cz==snap.cz())source=snap.blocks();
        else if(cx==snap.cx()-1&&cz==snap.cz())source=snap.west();
        else if(cx==snap.cx()+1&&cz==snap.cz())source=snap.east();
        else if(cx==snap.cx()&&cz==snap.cz()-1)source=snap.north();
        else if(cx==snap.cx()&&cz==snap.cz()+1)source=snap.south();
        else return BlockType.AIR;
        if(source==null)return BlockType.AIR;
        int lx=Math.floorMod(x,CHUNK_SIZE),lz=Math.floorMod(z,CHUNK_SIZE);
        int idx=source==snap.blocks()?index(lx,y,lz)
                : (source==snap.west()||source==snap.east()?lz:lx)*WORLD_HEIGHT+y;
        return BlockType.fromId(source[idx]);
    }
    /** Only submerged plants remain water-overlay visuals pending waterlogged block states. */
    private static void appendFoliage(Snapshot snap, FloatArrayBuilder data, int section) {
        appendMarineDecorations(snap,data,section);
    }
    /** Marine decorations are deterministic lightweight cutout models, not solid cubes. */
    private static void appendMarineDecorations(Snapshot snap,FloatArrayBuilder out,int section) {
        if(snap.generator()==null)return; // Optional biome decoration context in headless geometry fixtures.
        int minX=snap.cx()*CHUNK_SIZE,minZ=snap.cz()*CHUNK_SIZE;
        for(int wx=minX;wx<minX+CHUNK_SIZE;wx++)for(int wz=minZ;wz<minZ+CHUNK_SIZE;wz++) {
            if((wx&3)!=0 || (wz&3)!=0)continue;
            WorldGenerator.Surface surface=snap.generator().surface(wx,wz);
            if(surface.waterBody()!=WorldGenerator.WaterBody.OCEAN || surface.waterDepth()<3)continue;
            int floor=surface.surfaceY();
            int start=floor+1;
            if(start<1 || start>=WORLD_HEIGHT-8)continue;
            BlockType support=snapshotBlockAt(snap,wx,floor,wz);
            if(support==BlockType.AIR || support.isFluid())continue;
            BlockType above=snapshotBlockAt(snap,wx,start,wz);
            if(!above.isWater())continue;
            long hash=rockHash(wx,0,wz);
            double chance=((hash>>>11)&0xffff)/65536.0;
            WorldGenerator.OceanBiome ocean=snap.generator().oceanBiomeAt(wx,wz,surface);
            double threshold=switch(ocean) {
                case REEF,WARM,ESTUARY -> .66;
                case KELP -> .88; case TEMPERATE -> .52;
                case COLD -> .31; case FROZEN,ABYSSAL,VOLCANIC -> .10;
                case DEEP -> .18;
            };
            if(chance>=threshold)continue;
            int type=(int)((hash>>>27)&7);
            int layer=switch(ocean) {
                case REEF,WARM -> type==7 && (hash&8L)==0 ? 225 :
                        new int[]{217,218,219,220,221,222,223,224}[type];
                case ESTUARY -> new int[]{211,212,213,216,211,212,214,211}[type];
                case KELP -> 214;
                case COLD,FROZEN,DEEP,ABYSSAL,VOLCANIC -> type%3==0?216:211;
                case TEMPERATE -> new int[]{211,212,216,211,212,211,216,212}[type];
            };
            // Reef organisms need hard substrate and sufficient light.
            if(layer>=217 && layer<=225) {
                boolean reefStone=support==BlockType.OCEAN_REEF_LIMESTONE ||
                        support==BlockType.OCEAN_ALGAE_STONE ||
                        support==BlockType.OCEAN_DEAD_CORAL_RUBBLE;
                if(!reefStone || surface.waterDepth()>26)layer=211;
            }
            int height=layer==214?Math.min(surface.waterDepth()-2,3+((int)(hash>>>32)&7)):1;
            for(int dy=0;dy<height;dy++) {
                int y=start+dy;
                if(y/SECTION_HEIGHT!=section || y>=surface.waterSurfaceY()-1)continue;
                if(!snapshotBlockAt(snap,wx,y,wz).isWater())continue;
                crossPlant(out,wx,y,wz,layer==214&&dy==height-1?215:layer,
                        layer==214?1f:.72f);
            }
        }
    }
    private static void crossPlantAt(FloatArrayBuilder data,int x,int y,int z,int layer,boolean ceiling) {
        if(!ceiling){crossPlant(data,x,y,z,layer,0.88f);return;}
        // Hang below the block above; roots don't float from the floor.
        float y0=y+.12f, y1=y+1f;
        doubleFace(data,x+.12f,y0,z+.12f,x+.88f,y0,z+.88f,
                x+.88f,y1,z+.88f,x+.12f,y1,z+.12f,layer,.92f);
        doubleFace(data,x+.12f,y0,z+.88f,x+.88f,y0,z+.12f,
                x+.88f,y1,z+.12f,x+.12f,y1,z+.88f,layer,.90f);
    }
    private static void spike(FloatArrayBuilder data,int x,int y,int z,int layer,boolean hangs) {
        float baseY=hangs?y+1f:y,tipY=hangs?y+.08f:y+.92f;
        float min=x+.12f,max=x+.88f,left=z+.12f,right=z+.88f;
        // Two crossed, double-sided tapered planes; no solid cube or collision.
        doubleFace(data,min,baseY,left,max,baseY,right,x+.51f,tipY,z+.51f,
                x+.49f,tipY,z+.49f,layer,.92f);
        doubleFace(data,min,baseY,right,max,baseY,left,x+.51f,tipY,z+.49f,
                x+.49f,tipY,z+.51f,layer,.84f);
    }
    /** Three small prisms produce a solid 3D mineral cluster without a new shader. */
    private static void crystalCluster(FloatArrayBuilder out,int x,int y,int z,int layer) {
        crystalSpike(out,x+.31f,y,z+.32f,.22f,.88f,layer);
        crystalSpike(out,x+.69f,y,z+.62f,.17f,.59f,layer);
        crystalSpike(out,x+.44f,y,z+.76f,.14f,.43f,layer);
    }
    private static void crystalSpike(FloatArrayBuilder out,float x,float y,float z,
                              float radius,float height,int layer) {
        float tip=y+height;
        // Four tapered faces around a diamond cross section, double-sided.
        doubleFace(out,x-radius,y,z,x,y,z-radius,x,tip,z,x,tip,z,layer,.87f);
        doubleFace(out,x,y,z-radius,x+radius,y,z,x,tip,z,x,tip,z,layer,.98f);
        doubleFace(out,x+radius,y,z,x,y,z+radius,x,tip,z,x,tip,z,layer,.82f);
        doubleFace(out,x,y,z+radius,x-radius,y,z,x,tip,z,x,tip,z,layer,.92f);
    }
    private static void crossPlant(FloatArrayBuilder data,int x,int y,int z,int layer,float height) {
        // Offset from voxel corners prevents cutout sprites from looking like thin walls.
        float x0=x+0.12f,x1=x+0.88f,z0=z+0.12f,z1=z+0.88f;
        doubleFace(data,x0,y,z0,x1,y,z1,x1,y+height,z1,x0,y+height,z0,layer,.94f);
        doubleFace(data,x0,y,z1,x1,y,z0,x1,y+height,z0,x0,y+height,z1,layer,.91f);
    }
    private static void doubleFace(FloatArrayBuilder data,float ax,float ay,float az,float bx,float by,float bz,
                            float cx,float cy,float cz,float dx,float dy,float dz,int layer,float shade) {
        face(data,ax,ay,az,bx,by,bz,cx,cy,cz,dx,dy,dz,layer,shade);
        face(data,bx,by,bz,ax,ay,az,dx,dy,dz,cx,cy,cz,layer,shade);
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
