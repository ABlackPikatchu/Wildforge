package com.wildforge.world;

import com.wildforge.worldgen.WorldGenerator;
import com.wildforge.worldgen.WorldGenConfigLoader;
import com.wildforge.worldgen.BiomeDistribution;
import com.wildforge.worldgen.SeededNoise;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Bounded, player-centered chunk cache with persistent sparse edits and v1 imports. */
public final class TerrainMesh {
    public static final int CHUNK_SIZE=16, WORLD_HEIGHT=256;
    // Legacy constants are retained only for old save import and source compatibility.
    public static final int WORLD_CHUNKS=4, WORLD_SIZE=64;
    public static final int FLOATS_PER_VERTEX=7;
    private static final int MAGIC=0x57464F52, SAVE_VERSION=2;
    private static final long DEFAULT_SEED=0x57494C44464F5247L;
    private final LinkedHashMap<Long,Chunk> chunks = new LinkedHashMap<>();
    private final HashMap<Long,HashMap<Integer,Byte>> edits = new HashMap<>();
    private WorldGenerator generator;
    private BiomeDistribution distribution;
    private SeededNoise depositNoise;
    private long seed=DEFAULT_SEED;
    private int version=0, renderDistance=8, simulationDistance=5;
    private int centerX=1,centerZ=1;
    private boolean streamed=false;
    // Worker builds raw chunk data only. OpenGL-facing meshes remain on the main thread.
    private ExecutorService generationWorker = newGenerationWorker();
    private final HashMap<Long, Future<Chunk>> pending = new HashMap<>();
    private static ExecutorService newGenerationWorker() {
        return Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "Wildforge-Chunk-Generation");
            t.setDaemon(true);
            return t;
        });
    }
    private void cancelGeneration() {
        generationWorker.shutdownNow();
        pending.clear();
        generationWorker = newGenerationWorker();
    }
    private void generateSpawnChunk(int x, int z) {
        int cx = Math.floorDiv(x, CHUNK_SIZE), cz = Math.floorDiv(z, CHUNK_SIZE);
        Chunk c = generate(cx, cz);
        applySavedEdits(c);
        chunks.put(key(cx, cz), c);
        rebuild(c);
        version++;
    }
    private void applySavedEdits(Chunk c) {
        HashMap<Integer, Byte> changes = edits.get(key(c.cx, c.cz));
        if (changes != null) for (var entry : changes.entrySet()) c.blocks[entry.getKey()] = entry.getValue();
    }
    public static final class RayHit {
        public final int x,y,z,placeX,placeY,placeZ;
        public final BlockType block;
        RayHit(int x,int y,int z,int px,int py,int pz,BlockType block){
            this.x=x;this.y=y;this.z=z;placeX=px;placeY=py;placeZ=pz;this.block=block;
        }
    }
    private static final class Chunk {
        final int cx,cz;
        final byte[] blocks=new byte[CHUNK_SIZE*CHUNK_SIZE*WORLD_HEIGHT];
        float[] vertices=new float[0];
        int meshRevision=0;
        Chunk(int cx,int cz){this.cx=cx;this.cz=cz;}
    }
    private static final class FloatArrayBuilder {
        private float[] values=new float[1024]; private int size;
        void add(float value){if(size==values.length)values=Arrays.copyOf(values,values.length*2);values[size++]=value;}
        float[] toArray(){return Arrays.copyOf(values,size);}
    }
    private static long key(int x,int z){return ((long)x<<32)|(z&0xffffffffL);}
    private static int index(int x,int y,int z){return (x*CHUNK_SIZE+z)*WORLD_HEIGHT+y;}
    private void initializeGenerator(){
        try { generator=new WorldGenerator(seed,WorldGenConfigLoader.loadBuiltIn()); distribution=new BiomeDistribution(seed); depositNoise=new SeededNoise(seed); }
        catch(IOException e){ throw new IllegalStateException("Worldgen profile unavailable; run generateContent",e); }
    }
    public TerrainMesh(){initializeGenerator();generateSpawnChunk(16,27);}
    public void setDistances(int render,int simulation){
        renderDistance=Math.max(2,Math.min(24,render));
        simulationDistance=Math.max(2,Math.min(renderDistance,simulation));
    }
    public int renderDistance(){return renderDistance;}
    public int simulationDistance(){return simulationDistance;}
    public int loadedChunkCount(){return chunks.size();}
    public long seed(){return seed;}
    public void resetWorld(){cancelGeneration();edits.clear();seed=DEFAULT_SEED;initializeGenerator();chunks.clear();streamed=false;generateSpawnChunk(16,27);version++;}
    /** Nonblocking player-centered streaming: one worker produces raw columns, main thread owns meshes. */
    public void streamAround(float x,float z){
        int cx=Math.floorDiv((int)Math.floor(x),CHUNK_SIZE);
        int cz=Math.floorDiv((int)Math.floor(z),CHUNK_SIZE);
        streamed=true;centerX=cx;centerZ=cz;
        boolean changed=false;
        Iterator<Map.Entry<Long,Chunk>> it=chunks.entrySet().iterator();
        while(it.hasNext()){
            Chunk c=it.next().getValue();
            if(Math.abs(c.cx-cx)>renderDistance || Math.abs(c.cz-cz)>renderDistance){
                it.remove();changed=true;
            }
        }
        // Discard out-of-range requests. Cancellation is best-effort; late results are ignored.
        Iterator<Map.Entry<Long, Future<Chunk>>> requests=pending.entrySet().iterator();
        while(requests.hasNext()){
            var entry=requests.next();
            int px=(int)(entry.getKey()>>32),pz=(int)(long)entry.getKey();
            if(Math.abs(px-cx)>renderDistance || Math.abs(pz-cz)>renderDistance){
                entry.getValue().cancel(true);requests.remove();
            }
        }
        // Install at most one completed chunk per frame, bounding expensive main-thread mesh work.
        Iterator<Map.Entry<Long,Future<Chunk>>> finished=pending.entrySet().iterator();
        while(finished.hasNext()){
            var entry=finished.next();
            Future<Chunk> future=entry.getValue();
            if(!future.isDone())continue;
            finished.remove();
            try {
                Chunk c=future.get();
                if(chunks.containsKey(entry.getKey()))continue;
                applySavedEdits(c);
                chunks.put(entry.getKey(),c);
                rebuild(c);
                rebuildNeighbor(c.cx-1,c.cz);rebuildNeighbor(c.cx+1,c.cz);
                rebuildNeighbor(c.cx,c.cz-1);rebuildNeighbor(c.cx,c.cz+1);
                changed=true;
            } catch(CancellationException ignored) {
            } catch(ExecutionException ex) {
                throw new IllegalStateException("Asynchronous terrain generation failed",ex.getCause());
            } catch(InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
            break;
        }
        // Bounded queue prevents runaway work when moving quickly or changing render distance.
        if(pending.size()<2){
            boolean queued=false;
            for(int ring=0;ring<=renderDistance && !queued;ring++){
                for(int dx=-ring;dx<=ring && !queued;dx++) for(int dz=-ring;dz<=ring && !queued;dz++){
                    if(Math.max(Math.abs(dx),Math.abs(dz))!=ring)continue;
                    int nx=cx+dx,nz=cz+dz;long k=key(nx,nz);
                    if(chunks.containsKey(k)||pending.containsKey(k))continue;
                    final int targetX=nx,targetZ=nz;
                    pending.put(k,generationWorker.submit(()->generate(targetX,targetZ)));
                    queued=true;
                }
            }
        }
        if(changed)version++;
    }
    private void rebuildNeighbor(int x,int z){Chunk c=chunks.get(key(x,z));if(c!=null)rebuild(c);}
    private Chunk generate(int cx,int cz){
        Chunk c=new Chunk(cx,cz);
        for(int lx=0;lx<CHUNK_SIZE;lx++)for(int lz=0;lz<CHUNK_SIZE;lz++){
            int wx=cx*CHUNK_SIZE+lx,wz=cz*CHUNK_SIZE+lz;
            WorldGenerator.Surface surface=generator.surface(wx,wz);
            int top=Math.max(1,Math.min(WORLD_HEIGHT-2,surface.surfaceY()));
            int slope=generator.slopeAt(wx,wz);
            BlockType topBlock=distribution.exposedTop(surface.biome(),wx,wz,top,generator.settings().seaLevel(),
                    slope,material(surface.topTexture(), BlockType.GRASS));
            BlockType filler=distribution.under(surface.biome(),wx,wz,material(surface.fillerTexture(), BlockType.DIRT));
            BlockType baseRock=material(surface.baseRockTexture(), BlockType.STONE);
            BlockType rock=distribution.rock(surface.biome(),wx,wz,baseRock);
            int soilDepth=distribution.soilDepth(surface.biome(),wx,wz);
            // The first 3D density pass works above and below the old heightmap.
            // Sample only the upper terrain band; deep rock remains inexpensive.
            int highest=Math.min(WORLD_HEIGHT-2,top+8);
            for(int y=0;y<=highest;y++){
                if(y>3 && !generator.isSolidTerrain(wx,y,wz,top,slope))continue;
                if(y>3 && generator.isCave(wx,y,wz,top))continue;
                // Rock overhangs above the original height receive rock faces,
                // rather than floating copies of biome topsoil.
                BlockType b=y==0?BlockType.BEDROCK:y>top?rock:y==top?topBlock:y>=top-soilDepth?filler:rock;
                c.blocks[index(lx,y,lz)]=b.id;
            }
        }
        // Populate only cells belonging to this chunk. Candidates are sampled in
        // world coordinates, including adjacent cells whose crowns overlap us.
        populateFeatures(c);
        generateOreVeins(c);
        // Edits are applied when the completed chunk is installed on the main thread.
        return c;
    }
    /** Deterministic feature population, independent of chunk generation order. */
    private void populateFeatures(Chunk c) {
        int minX=c.cx*CHUNK_SIZE, minZ=c.cz*CHUNK_SIZE;
        int maxX=minX+CHUNK_SIZE-1, maxZ=minZ+CHUNK_SIZE-1;
        int treeCell=WorldGenerator.TREE_CELL_SIZE;
        // A tree canopy reaches at most three blocks beyond its anchor.
        for(int tx=Math.floorDiv(minX-3,treeCell);tx<=Math.floorDiv(maxX+3,treeCell);tx++)
            for(int tz=Math.floorDiv(minZ-3,treeCell);tz<=Math.floorDiv(maxZ+3,treeCell);tz++) {
                WorldGenerator.Tree tree=generator.treeForCell(tx,tz);
                if(tree!=null) placeTree(c,tree,minX,minZ);
            }
        int plantCell=WorldGenerator.PLANT_CELL_SIZE;
        for(int px=Math.floorDiv(minX,plantCell);px<=Math.floorDiv(maxX,plantCell);px++)
            for(int pz=Math.floorDiv(minZ,plantCell);pz<=Math.floorDiv(maxZ,plantCell);pz++) {
                WorldGenerator.Plant plant=generator.plantForCell(px,pz);
                if(plant!=null) placePlant(c,plant,minX,minZ);
            }
    }
    private void putFeature(Chunk c,int x,int y,int z,BlockType type,int minX,int minZ) {
        if(x<minX||x>=minX+CHUNK_SIZE||z<minZ||z>=minZ+CHUNK_SIZE||y<1||y>=WORLD_HEIGHT-1)return;
        int idx=index(x-minX,y,z-minZ);
        if(c.blocks[idx]==BlockType.AIR.id)c.blocks[idx]=type.id;
    }
    private void placeTree(Chunk c,WorldGenerator.Tree t,int minX,int minZ) {
        int x=t.x(),z=t.z();
        int y=generator.surface(x,z).surfaceY()+1;
        if(y<2||y+t.trunkHeight()+3>=WORLD_HEIGHT)return;
        BlockType log=switch(t.type()) {
            case PINE -> BlockType.PINE_LOG;
            case MAPLE -> BlockType.MAPLE_LOG;
            case CYPRESS -> BlockType.CYPRESS_LOG;
            case WILLOW -> BlockType.WILLOW_LOG;
            default -> BlockType.OAK_LOG;
        };
        for(int h=0;h<t.trunkHeight();h++)putFeature(c,x,y+h,z,log,minX,minZ);
    }
    private void placePlant(Chunk c,WorldGenerator.Plant p,int minX,int minZ) {
        int x=p.x(),z=p.z();
        int y=generator.surface(x,z).surfaceY()+1;
        if(y<2||y>=WORLD_HEIGHT-3)return;
        String species=p.species();
        if(species.contains("cactus")) {
            for(int h=0;h<2;h++)putFeature(c,x,y+h,z,BlockType.CACTUS,minX,minZ);
        } else if(species.contains("rock")||species.contains("boulder")||species.contains("scree")) {
            putFeature(c,x,y,z,BlockType.MOSSY_ROCK,minX,minZ);
        }
        // Flowers, grasses and reeds await crossed-plane transparent models.
    }
    /** Map profile texture names to registered blocks without changing existing block IDs. */
    private static BlockType material(String texture, BlockType fallback) {
        if(texture==null)return fallback;
        String name=texture.replace("wildforge:", "").replace("textures/", "").replace(".png", "").toUpperCase(java.util.Locale.ROOT);
        // Worldgen profiles refer to texture names, which are not necessarily block IDs.
        // Preserve saved numeric IDs by mapping these aliases onto existing block types.
        switch (name) {
            case "GRASS_TOP": return BlockType.GRASS;
            case "FOREST_LOAM": return BlockType.RICH_HUMUS;
            default: break;
        }
        try{return BlockType.valueOf(name);}catch(IllegalArgumentException ignored){return fallback;}
    }
    /** Connected, host-rock-aware mineral deposits. Anchor cells are in world coordinates,
     * including adjacent cells, so deposits remain continuous across chunk boundaries.
     * A fixed number of candidates per anchor gives predictable upper bounds on work.
     */
    private void generateOreVeins(Chunk c) {
        final int spacing=16, band=24;
        for(int ax=c.cx-1;ax<=c.cx+1;ax++) for(int az=c.cz-1;az<=c.cz+1;az++) {
            for(int ay=0;ay<=WORLD_HEIGHT/band;ay++) for(int type=0;type<5;type++) {
                long salt=0x4f524553L+type*0x54abL;
                double chance=switch(type){case 0->.73;case 1->.60;case 2->.24;case 3->.13;default->.028;};
                if(depositNoise.unit(ax,ay,az,salt)>chance)continue;
                int ox=ax*spacing+(int)(depositNoise.unit(ax,ay,az,salt+1)*spacing);
                int oy=ay*band+(int)(depositNoise.unit(ax,ay,az,salt+2)*band);
                int oz=az*spacing+(int)(depositNoise.unit(ax,ay,az,salt+3)*spacing);
                if(oy<5 || oy>WORLD_HEIGHT-8)continue;
                if(type==0 && oy>145 || type==1 && oy>135 || type==2 && (oy<12||oy>100)
                        || type==3 && (oy<7||oy>80) || type==4 && oy>42)continue;
                BlockType ore=switch(type){case 0->BlockType.COAL_ORE;case 1->BlockType.IRON_ORE;
                    case 2->BlockType.SILVER_ORE;case 3->BlockType.GOLD_ORE;default->BlockType.DIAMOND_ORE;};
                int length=switch(type){case 0->4+(int)(depositNoise.unit(ax,ay,az,salt+4)*11);
                    case 1->3+(int)(depositNoise.unit(ax,ay,az,salt+4)*7);
                    case 2->2+(int)(depositNoise.unit(ax,ay,az,salt+4)*5);
                    case 3->2+(int)(depositNoise.unit(ax,ay,az,salt+4)*4);
                    default->1+(int)(depositNoise.unit(ax,ay,az,salt+4)*3);};
                int vx=ox,vy=oy,vz=oz;
                for(int n=0;n<length;n++) {
                    if(vx>=c.cx*16 && vx<(c.cx+1)*16 && vz>=c.cz*16 && vz<(c.cz+1)*16 && vy>1 && vy<WORLD_HEIGHT-1){
                        int idx=index(vx-c.cx*16,vy,vz-c.cz*16);
                        BlockType host=BlockType.fromId(c.blocks[idx]);
                        if(validOreHost(host,type))c.blocks[idx]=ore.id;
                    }
                    // A connected 6-neighbour walk, not isolated dice-rolled ore specks.
                    int direction=(int)(depositNoise.unit(ox+n,oy+n,oz-n,salt+5)*6);
                    switch(direction){case 0->vx++;case 1->vx--;case 2->vy++;case 3->vy--;case 4->vz++;default->vz--;}
                }
            }
        }
    }
    private static boolean validOreHost(BlockType block,int type) {
        return switch(type) {
            case 0 -> block==BlockType.STONE||block==BlockType.SHALE||block==BlockType.SANDSTONE||block==BlockType.SILTSTONE;
            case 1 -> block==BlockType.STONE||block==BlockType.GRANITE||block==BlockType.ANDESITE||block==BlockType.SHALE||block==BlockType.GABBRO;
            case 2,4 -> block==BlockType.STONE||block==BlockType.GRANITE||block==BlockType.GABBRO||block==BlockType.DIORITE||block==BlockType.SLATE;
            default -> block==BlockType.STONE||block==BlockType.GRANITE||block==BlockType.GABBRO||block==BlockType.SHALE;
        };
    }
    public BlockType getBlockAt(int x,int y,int z){
        if(y<0||y>=WORLD_HEIGHT)return BlockType.AIR;
        Chunk c=chunks.get(key(Math.floorDiv(x,CHUNK_SIZE),Math.floorDiv(z,CHUNK_SIZE)));
        if(c==null)return BlockType.AIR;
        return BlockType.fromId(c.blocks[index(Math.floorMod(x,CHUNK_SIZE),y,Math.floorMod(z,CHUNK_SIZE))]);
    }
    public boolean setBlock(int x,int y,int z,BlockType type){
        if(type==null||y<0||y>=WORLD_HEIGHT)return false;
        int cx=Math.floorDiv(x,CHUNK_SIZE),cz=Math.floorDiv(z,CHUNK_SIZE);
        Chunk c=chunks.get(key(cx,cz));if(c==null)return false;
        int i=index(Math.floorMod(x,CHUNK_SIZE),y,Math.floorMod(z,CHUNK_SIZE));
        if(c.blocks[i]==type.id)return false;
        c.blocks[i]=type.id;
        edits.computeIfAbsent(key(cx,cz),k->new HashMap<>()).put(i,type.id);
        rebuild(c);
        if(Math.floorMod(x,CHUNK_SIZE)==0)rebuildNeighbor(cx-1,cz);
        if(Math.floorMod(x,CHUNK_SIZE)==CHUNK_SIZE-1)rebuildNeighbor(cx+1,cz);
        if(Math.floorMod(z,CHUNK_SIZE)==0)rebuildNeighbor(cx,cz-1);
        if(Math.floorMod(z,CHUNK_SIZE)==CHUNK_SIZE-1)rebuildNeighbor(cx,cz+1);
        version++;return true;
    }
    public float getHeightAt(float x,float z){
        int bx=(int)Math.floor(x),bz=(int)Math.floor(z);
        // Spawn queries may precede streaming into a new area.
        for(int y=WORLD_HEIGHT-1;y>=0;y--)if(getBlockAt(bx,y,bz).isSolid())return y+1;
        return Math.max(1,Math.min(WORLD_HEIGHT-2,generator.surface(bx,bz).surfaceY()+1));
    }
    /** Read-only mesh descriptions; mesh arrays are replaced, never mutated. */
    public record ChunkMesh(int x, int z, int revision, float[] vertices) {}
    public java.util.List<ChunkMesh> chunkMeshes(){
        java.util.ArrayList<ChunkMesh> result=new java.util.ArrayList<>(chunks.size());
        for(Chunk c:chunks.values()) result.add(new ChunkMesh(c.cx,c.cz,c.meshRevision,c.vertices));
        return result;
    }
    public void newWorld(long newSeed){
        cancelGeneration();edits.clear();seed=newSeed;initializeGenerator();chunks.clear();streamed=false;
        generateSpawnChunk(16,27);version++;
    }
    public int getVersion(){return version;}
    public float[] getVertices(){
        int n=0;for(Chunk c:chunks.values())n+=c.vertices.length;
        float[] all=new float[n];int offset=0;
        for(Chunk c:chunks.values()){System.arraycopy(c.vertices,0,all,offset,c.vertices.length);offset+=c.vertices.length;}
        return all;
    }
    public int getVertexCount(){int n=0;for(Chunk c:chunks.values())n+=c.vertices.length/FLOATS_PER_VERTEX;return n;}
    /** v2 stores seed and all edits, independent of which chunks are loaded. */
    public void saveTo(Path path)throws IOException{
        Path p=path.toAbsolutePath();Files.createDirectories(p.getParent());Path temp=p.resolveSibling(p.getFileName()+".tmp");
        try{
            try(DataOutputStream out=new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(temp)))){
                out.writeInt(MAGIC);out.writeInt(SAVE_VERSION);out.writeLong(seed);out.writeInt(edits.size());
                for(var entry:edits.entrySet()){
                    out.writeLong(entry.getKey());out.writeInt(entry.getValue().size());
                    for(var change:entry.getValue().entrySet()){out.writeInt(change.getKey());out.writeByte(change.getValue());}
                }
            }
            try{Files.move(temp,p,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}
            catch(AtomicMoveNotSupportedException e){Files.move(temp,p,StandardCopyOption.REPLACE_EXISTING);}
        }finally{Files.deleteIfExists(temp);}
    }
    public void loadFrom(Path path)throws IOException{
        HashMap<Long,HashMap<Integer,Byte>> incoming=new HashMap<>();long newSeed=DEFAULT_SEED;
        try(DataInputStream in=new DataInputStream(new BufferedInputStream(Files.newInputStream(path)))){
            if(in.readInt()!=MAGIC)throw new IOException("Not a Wildforge world save");
            int v=in.readInt();
            if(v==1){
                int width=in.readInt(),height=in.readInt(),depth=in.readInt();
                if(width!=64||height!=32||depth!=64)throw new IOException("Unexpected legacy world dimensions");
                for(int x=0;x<64;x++)for(int y=0;y<32;y++)for(int z=0;z<64;z++){
                    byte b=in.readByte();if(!BlockType.isValidId(b))throw new IOException("Invalid legacy block id");
                    int cx=x/16,cz=z/16,i=index(x%16,y,z%16);
                    incoming.computeIfAbsent(key(cx,cz),k->new HashMap<>()).put(i,b);
                }
            }else if(v==2){
                newSeed=in.readLong();int count=in.readInt();
                if(count<0||count>1000000)throw new IOException("Invalid chunk count");
                for(int n=0;n<count;n++){
                    long k=in.readLong();int size=in.readInt();
                    if(size<0||size>CHUNK_SIZE*CHUNK_SIZE*WORLD_HEIGHT)throw new IOException("Invalid edit count");
                    HashMap<Integer,Byte> changes=incoming.computeIfAbsent(k,ignore->new HashMap<>());
                    for(int j=0;j<size;j++){
                        int idx=in.readInt();byte b=in.readByte();
                        if(idx<0||idx>=CHUNK_SIZE*CHUNK_SIZE*WORLD_HEIGHT||!BlockType.isValidId(b))throw new IOException("Invalid edit");
                        changes.put(idx,b);
                    }
                }
            }else throw new IOException("Unsupported world save version: "+v);
            if(in.read()!=-1)throw new IOException("World save has trailing data");
        }
        // Commit only after complete validation.
        cancelGeneration();seed=newSeed;initializeGenerator();edits.clear();edits.putAll(incoming);
        chunks.clear();streamed=false;generateSpawnChunk(16,27);version++;
    }
    private void rebuild(Chunk c){
        FloatArrayBuilder data=new FloatArrayBuilder();
        for(int lx=0;lx<CHUNK_SIZE;lx++)for(int lz=0;lz<CHUNK_SIZE;lz++)for(int y=0;y<WORLD_HEIGHT;y++){
            BlockType block=BlockType.fromId(c.blocks[index(lx,y,lz)]);
            if(block==BlockType.AIR)continue;
            int x=c.cx*CHUNK_SIZE+lx,z=c.cz*CHUNK_SIZE+lz;
            if(getBlockAt(x,y,z+1)==BlockType.AIR)face(data,x,y,z+1,x+1,y,z+1,x+1,y+1,z+1,x,y+1,z+1,block.getSideTextureLayer(),.85f);
            if(getBlockAt(x,y,z-1)==BlockType.AIR)face(data,x+1,y,z,x,y,z,x,y+1,z,x+1,y+1,z,block.getSideTextureLayer(),.75f);
            if(getBlockAt(x+1,y,z)==BlockType.AIR)face(data,x+1,y,z+1,x+1,y,z,x+1,y+1,z,x+1,y+1,z+1,block.getSideTextureLayer(),.8f);
            if(getBlockAt(x-1,y,z)==BlockType.AIR)face(data,x,y,z,x,y,z+1,x,y+1,z+1,x,y+1,z,block.getSideTextureLayer(),.8f);
            if(getBlockAt(x,y+1,z)==BlockType.AIR)face(data,x,y+1,z+1,x+1,y+1,z+1,x+1,y+1,z,x,y+1,z,block.getTopTextureLayer(),1f);
            if(getBlockAt(x,y-1,z)==BlockType.AIR)face(data,x,y,z,x+1,y,z,x+1,y,z+1,x,y,z+1,block.getBottomTextureLayer(),.55f);
        }
        appendFoliage(c, data);
        c.vertices=data.toArray();
        c.meshRevision++;
    }
    /** Visual-only alpha-cutout foliage: no block IDs, no collision, no save migration. */
    private void appendFoliage(Chunk c, FloatArrayBuilder data) {
        int minX=c.cx*CHUNK_SIZE, minZ=c.cz*CHUNK_SIZE;
        int maxX=minX+CHUNK_SIZE-1, maxZ=minZ+CHUNK_SIZE-1;
        // Scan neighboring anchor cells so crowns naturally cross chunk boundaries.
        int tc=WorldGenerator.TREE_CELL_SIZE;
        for(int tx=Math.floorDiv(minX-3,tc);tx<=Math.floorDiv(maxX+3,tc);tx++)
            for(int tz=Math.floorDiv(minZ-3,tc);tz<=Math.floorDiv(maxZ+3,tc);tz++) {
                WorldGenerator.Tree t=generator.treeForCell(tx,tz);
                if(t==null)continue;
                int tip=generator.surface(t.x(),t.z()).surfaceY()+1+t.trunkHeight();
                int layer=leafLayer(t.type());
                for(int dy=-2;dy<=1;dy++) {
                    int radius=t.type()==WorldGenerator.TreeType.PINE ? (dy==1?0:dy==0?1:2) : (dy==1?1:2);
                    for(int dx=-radius;dx<=radius;dx++)for(int dz=-radius;dz<=radius;dz++) {
                        if(dx*dx+dz*dz>radius*radius+1 || (dx==0 && dz==0 && dy<=0))continue;
                        int x=t.x()+dx,z=t.z()+dz,y=tip+dy;
                        if(x<minX||x>maxX||z<minZ||z>maxZ||y<1||y>=WORLD_HEIGHT)continue;
                        if(getBlockAt(x,y,z)!=BlockType.AIR)continue;
                        leafCube(data,x,y,z,layer);
                    }
                }
            }
        int pc=WorldGenerator.PLANT_CELL_SIZE;
        for(int px=Math.floorDiv(minX,pc);px<=Math.floorDiv(maxX,pc);px++)
            for(int pz=Math.floorDiv(minZ,pc);pz<=Math.floorDiv(maxZ,pc);pz++) {
                WorldGenerator.Plant p=generator.plantForCell(px,pz);
                if(p==null||p.x()<minX||p.x()>maxX||p.z()<minZ||p.z()>maxZ)continue;
                int layer=plantLayer(p.species());
                if(layer<0)continue;
                int y=generator.surface(p.x(),p.z()).surfaceY()+1;
                if(y<1||y>=WORLD_HEIGHT-2 || getBlockAt(p.x(),y,p.z())!=BlockType.AIR)continue;
                // Never render floating plants when players have removed the ground.
                if(getBlockAt(p.x(),y-1,p.z())==BlockType.AIR)continue;
                crossPlant(data,p.x(),y,p.z(),layer,p.tall()?1.0f:0.78f);
            }
    }
    private static int leafLayer(WorldGenerator.TreeType type) {
        return switch(type) {
            case OAK -> 116; case PINE -> 117; case MAPLE -> 118;
            case WILLOW -> 119; case CYPRESS -> 120;
        };
    }
    private static int plantLayer(String name) {
        String s=name.toLowerCase(java.util.Locale.ROOT);
        if(s.contains("cactus")||s.contains("rock")||s.contains("boulder")||s.contains("scree"))return -1;
        if(s.contains("fern"))return 122;
        if(s.contains("reeds")||s.contains("cattail"))return 123;
        if(s.contains("dead_bush")||s.contains("dry_shrub"))return 124;
        if(s.contains("evergreen_shrub")||s.contains("shrub"))return 125;
        if(s.contains("mushroom"))return 126;
        if(s.contains("snowdrop"))return 127;
        if(s.contains("poppy"))return 128;
        if(s.contains("daisy"))return 129;
        if(s.contains("goldenrod"))return 130;
        if(s.contains("lavender"))return 131;
        if(s.contains("bluebell"))return 132;
        if(s.contains("flower")||s.contains("orchid"))return 129;
        if(s.contains("grass")||s.contains("sedge"))return 121;
        return 121;
    }
    private void crossPlant(FloatArrayBuilder data,int x,int y,int z,int layer,float height) {
        // Offset from voxel corners prevents cutout sprites from looking like thin walls.
        float x0=x+0.12f,x1=x+0.88f,z0=z+0.12f,z1=z+0.88f;
        doubleFace(data,x0,y,z0,x1,y,z1,x1,y+height,z1,x0,y+height,z0,layer,.94f);
        doubleFace(data,x0,y,z1,x1,y,z0,x1,y+height,z0,x0,y+height,z1,layer,.91f);
    }
    private void doubleFace(FloatArrayBuilder data,float ax,float ay,float az,float bx,float by,float bz,
                            float cx,float cy,float cz,float dx,float dy,float dz,int layer,float shade) {
        face(data,ax,ay,az,bx,by,bz,cx,cy,cz,dx,dy,dz,layer,shade);
        face(data,bx,by,bz,ax,ay,az,dx,dy,dz,cx,cy,cz,layer,shade);
    }
    private void leafCube(FloatArrayBuilder data,int x,int y,int z,int layer) {
        // Leaves are cutout visuals and are deliberately non-solid/non-mineable in this phase.
        face(data,x,y,z+1,x+1,y,z+1,x+1,y+1,z+1,x,y+1,z+1,layer,.86f);
        face(data,x+1,y,z,x,y,z,x,y+1,z,x+1,y+1,z,layer,.76f);
        face(data,x+1,y,z+1,x+1,y,z,x+1,y+1,z,x+1,y+1,z+1,layer,.81f);
        face(data,x,y,z,x,y,z+1,x,y+1,z+1,x,y+1,z,layer,.81f);
        face(data,x,y+1,z+1,x+1,y+1,z+1,x+1,y+1,z,x,y+1,z,layer,1.0f);
        face(data,x,y,z,x+1,y,z,x+1,y,z+1,x,y,z+1,layer,.60f);
    }
    private void face(
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

    private void vertex(
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

    public RayHit raycast(
            float ox, float oy, float oz,
            float dx, float dy, float dz,
            float reach
    ) {
        float length = (float) Math.sqrt(
                dx * dx + dy * dy + dz * dz
        );

        if (length == 0.0f || reach < 0.0f) {
            return null;
        }

        dx /= length;
        dy /= length;
        dz /= length;

        int x = (int) Math.floor(ox);
        int y = (int) Math.floor(oy);
        int z = (int) Math.floor(oz);

        BlockType startingBlock = getBlockAt(x, y, z);
        if (startingBlock != BlockType.AIR) {
            return new RayHit(
                    x, y, z,
                    x, y, z,
                    startingBlock
            );
        }

        int stepX = dx > 0.0f ? 1 : dx < 0.0f ? -1 : 0;
        int stepY = dy > 0.0f ? 1 : dy < 0.0f ? -1 : 0;
        int stepZ = dz > 0.0f ? 1 : dz < 0.0f ? -1 : 0;

        double tDeltaX = stepX == 0
                ? Double.POSITIVE_INFINITY
                : Math.abs(1.0 / dx);
        double tDeltaY = stepY == 0
                ? Double.POSITIVE_INFINITY
                : Math.abs(1.0 / dy);
        double tDeltaZ = stepZ == 0
                ? Double.POSITIVE_INFINITY
                : Math.abs(1.0 / dz);

        double tMaxX = stepX > 0
                ? (x + 1.0 - ox) / dx
                : stepX < 0
                        ? (ox - x) / -dx
                        : Double.POSITIVE_INFINITY;
        double tMaxY = stepY > 0
                ? (y + 1.0 - oy) / dy
                : stepY < 0
                        ? (oy - y) / -dy
                        : Double.POSITIVE_INFINITY;
        double tMaxZ = stepZ > 0
                ? (z + 1.0 - oz) / dz
                : stepZ < 0
                        ? (oz - z) / -dz
                        : Double.POSITIVE_INFINITY;

        while (true) {
            int previousX = x;
            int previousY = y;
            int previousZ = z;
            double distance;

            // Advance through the next voxel boundary. Ties are resolved
            // one axis at a time, yielding a face-adjacent placement cell.
            if (tMaxX <= tMaxY && tMaxX <= tMaxZ) {
                distance = tMaxX;
                x += stepX;
                tMaxX += tDeltaX;
            } else if (tMaxY <= tMaxZ) {
                distance = tMaxY;
                y += stepY;
                tMaxY += tDeltaY;
            } else {
                distance = tMaxZ;
                z += stepZ;
                tMaxZ += tDeltaZ;
            }

            if (distance > reach) {
                return null;
            }

            BlockType block = getBlockAt(x, y, z);
            if (block != BlockType.AIR) {
                return new RayHit(
                        x, y, z,
                        previousX, previousY, previousZ,
                        block
                );
            }
        }
    }

}
