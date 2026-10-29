package com.wildforge.world;

import com.wildforge.worldgen.Biome;

import com.wildforge.worldgen.WorldGenerator;
import com.wildforge.worldgen.WorldGenConfigLoader;
import com.wildforge.worldgen.BiomeDistribution;
import com.wildforge.worldgen.SeededNoise;
import com.wildforge.worldgen.CaveCarvers;
import com.wildforge.worldgen.CaveBiomePainter;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Bounded, player-centered chunk cache with persistent sparse edits and v1 imports. */
public final class TerrainMesh {
    public static final int CHUNK_SIZE=16, WORLD_HEIGHT=512, SECTION_HEIGHT=16, SECTION_COUNT=WORLD_HEIGHT/SECTION_HEIGHT;
    // Legacy constants are retained only for old save import and source compatibility.
    public static final int WORLD_CHUNKS=4, WORLD_SIZE=64;
    public static final int FLOATS_PER_VERTEX=7;
    // Fluid sections use a separate mesh stream (section + FLUID_SECTION_OFFSET).
    // No block IDs or save indexes change.
    public static final int FLUID_SECTION_OFFSET=SECTION_COUNT;
    private static final int MAGIC=0x57464F52, SAVE_VERSION=3;
    private static final long DEFAULT_SEED=0x57494C44464F5247L;
    private final LinkedHashMap<Long,Chunk> chunks = new LinkedHashMap<>();
    private final HashMap<Long,HashMap<Integer,Byte>> edits = new HashMap<>();
    private WorldGenerator generator;
    private BiomeDistribution distribution;
    private SeededNoise depositNoise;
    private long seed=DEFAULT_SEED;
    // Active-only fluid scheduler: oceans are inert until a nearby cell changes.
    // Flow strength is bounded; source vs flowing fluid is stored as a block ID.
    private record FluidTask(int x,int y,int z,BlockType type,int strength) {}
    private final ArrayDeque<FluidTask> fluidQueue = new ArrayDeque<>();
    private final HashMap<FluidPosition,Integer> activeFluidStrength = new HashMap<>();
    private record FluidPosition(int x,int y,int z) {}
    private static final int FLUID_QUEUE_LIMIT = 8192;
    private int fluidFrame=0;
    private boolean legacyVerticalMigration=false;
    public boolean needsLegacyPlayerHeightShift(){return legacyVerticalMigration;}
    private int version=0, renderDistance=8, simulationDistance=5;
    private int centerX=1,centerZ=1;
    private boolean streamed=false;
    // Worker builds raw chunk data only. OpenGL-facing meshes remain on the main thread.
    private ExecutorService generationWorker = newGenerationWorker();
    private final HashMap<Long, Future<Chunk>> pending = new HashMap<>();
    // Mesh jobs never read live chunk maps or mutate meshes; only the game thread installs results.
    private ExecutorService meshWorker = newMeshWorker();
    private final LinkedHashSet<Long> dirtyMeshes = new LinkedHashSet<>();
    private final HashMap<Long, MeshJob> meshJobs = new HashMap<>();
    private final HashMap<Long, Integer> meshEpochs = new HashMap<>();
    private int meshGeneration = 0;
    // A small independent queue avoids blocking terrain and full-detail mesh workers.
    private ExecutorService lodWorker = newLodWorker();
    private final HashMap<Long,LodJob> lodJobs = new HashMap<>();
    private record LodMeshes(float[] terrain,float[] fluids) {}
    private record LodJob(Future<LodMeshes> future, int revision, int generation) {}
    private static ExecutorService newLodWorker() {
        return Executors.newSingleThreadExecutor(r -> {
            Thread t=new Thread(r,"Wildforge-Distant-LOD"); t.setDaemon(true); return t;
        });
    }
    private static ExecutorService newMeshWorker() {
        return Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "Wildforge-Section-Mesher");
            t.setDaemon(true);
            return t;
        });
    }
    private record MeshJob(Future<float[][]> future, int epoch, int generation) {}
    private record MeshSnapshot(int cx, int cz, byte[] blocks, byte[] west, byte[] east, byte[] north, byte[] south, WorldGenerator generator) {}
    private static ExecutorService newGenerationWorker() {
        return Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "Wildforge-Chunk-Generation");
            t.setDaemon(true);
            return t;
        });
    }
    private void cancelGeneration() {
        fluidQueue.clear();activeFluidStrength.clear();
        generationWorker.shutdownNow();
        pending.clear();
        generationWorker = newGenerationWorker();
        meshWorker.shutdownNow();
        meshWorker = newMeshWorker();
        meshJobs.clear();
        dirtyMeshes.clear();
        meshEpochs.clear();
        meshGeneration++;
        lodWorker.shutdownNow();
        lodWorker=newLodWorker();
        lodJobs.clear();
    }
    private void generateSpawnChunk(int x, int z) {
        int cx = Math.floorDiv(x, CHUNK_SIZE), cz = Math.floorDiv(z, CHUNK_SIZE);
        Chunk c = generate(cx, cz);
        applySavedEdits(c);
        chunks.put(key(cx, cz), c);
        rebuild(c);
        c.compact();
        version++;
    }
    private void applySavedEdits(Chunk c) {
        HashMap<Integer, Byte> changes = edits.get(key(c.cx, c.cz));
        if (changes != null) for (var entry : changes.entrySet()) c.set(entry.getKey(), entry.getValue());
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
        byte[] blocks=new byte[CHUNK_SIZE*CHUNK_SIZE*WORLD_HEIGHT];
        PaletteColumn compact;
        byte get(int i) { return compact == null ? blocks[i] : compact.get(i); }
        void set(int i,byte b) { if(compact == null) blocks[i]=b; else compact.set(i,b); }
        byte[] copyBlocks() { return compact == null ? blocks.clone() : compact.copyRaw(); }
        void compact() { if(compact == null) {compact = new PaletteColumn(blocks); blocks=null;} }
        float[] lodVertices;
        float[] lodFluidVertices;
        int lodRevision=0;
        final float[][] sectionVertices=new float[SECTION_COUNT][];
        final float[][] fluidVertices=new float[SECTION_COUNT][];
        final int[] sectionRevision=new int[SECTION_COUNT];
        final int[] fluidRevision=new int[SECTION_COUNT];
        Chunk(int cx,int cz){this.cx=cx;this.cz=cz;for(int i=0;i<SECTION_COUNT;i++){
            sectionVertices[i]=new float[0];fluidVertices[i]=new float[0];
        }}
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
    /** Approximate encoded block payload, excluding object overhead and CPU meshes. */
    public long residentBlockPayloadBytes(){long bytes=0;for(Chunk c:chunks.values())bytes+=c.compact==null?CHUNK_SIZE*CHUNK_SIZE*WORLD_HEIGHT:c.compact.storedBytes();return bytes;}
    /** Count of outstanding terrain generation jobs; queried on the game thread. */
    public int pendingGenerationCount(){return pending.size();}
    /** True only when a terrain column is actually resident in memory. */
    public boolean isColumnLoaded(int worldX,int worldZ){
        return chunks.containsKey(key(Math.floorDiv(worldX,CHUNK_SIZE),Math.floorDiv(worldZ,CHUNK_SIZE)));
    }

    public long seed(){return seed;}
    public void resetWorld(){legacyVerticalMigration=false;cancelGeneration();edits.clear();seed=DEFAULT_SEED;initializeGenerator();chunks.clear();streamed=false;generateSpawnChunk(16,27);version++;}
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
                long removed=key(c.cx,c.cz);
                dirtyMeshes.remove(removed);
                LodJob lodJob=lodJobs.remove(removed);
                if(lodJob!=null)lodJob.future().cancel(true);
                MeshJob old=meshJobs.remove(removed);
                if(old!=null)old.future().cancel(true);
                meshEpochs.remove(removed);
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
                c.compact();
                chunks.put(entry.getKey(),c);
                markMeshDirty(c.cx,c.cz);
                markMeshDirty(c.cx-1,c.cz);markMeshDirty(c.cx+1,c.cz);
                markMeshDirty(c.cx,c.cz-1);markMeshDirty(c.cx,c.cz+1);
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
        // Install no more than one completed column (16 sections) per frame.
        int installed = 0;
        var meshIter = meshJobs.entrySet().iterator();
        while (meshIter.hasNext() && installed < 1) {
            var entry = meshIter.next();
            MeshJob job = entry.getValue();
            if (!job.future().isDone()) continue;
            meshIter.remove();
            try {
                float[][] built = job.future().get();
                Chunk target = chunks.get(entry.getKey());
                if (target == null || job.generation() != meshGeneration
                    || job.epoch() != meshEpochs.getOrDefault(entry.getKey(), 0)) continue;
                for (int section=0; section<SECTION_COUNT; section++) {
                    target.sectionVertices[section] = built[section];
                    target.fluidVertices[section] = built[section+SECTION_COUNT];
                    target.sectionRevision[section]++;
                    target.fluidRevision[section]++;
                }
                changed = true;
                installed++;
            } catch (CancellationException ignored) {
            } catch (ExecutionException e) {
                throw new IllegalStateException("Asynchronous section meshing failed", e.getCause());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        // Snapshots are captured on the game thread. Queue is bounded to prevent memory spikes.
        while (meshJobs.size() < 2 && !dirtyMeshes.isEmpty()) {
            long bestKey = 0;
            long bestDistance = Long.MAX_VALUE;
            for (long candidate : dirtyMeshes) {
                int mx=(int)(candidate>>32),mz=(int)candidate;
                long d=(long)(mx-cx)*(mx-cx)+(long)(mz-cz)*(mz-cz);
                if (d < bestDistance) {bestDistance=d;bestKey=candidate;}
            }
            dirtyMeshes.remove(bestKey);
            Chunk target = chunks.get(bestKey);
            if (target == null || meshJobs.containsKey(bestKey)) continue;
            MeshSnapshot snapshot = snapshot(target);
            int epoch=meshEpochs.getOrDefault(bestKey,0);
            int generation=meshGeneration;
            meshJobs.put(bestKey,new MeshJob(meshWorker.submit(() -> buildColumnMesh(snapshot)),epoch,generation));
        }
        // The queue is budgeted so fluid spread never stalls rendering.
        processFluidUpdates(24);
        if(changed)version++;
    }
    private void markMeshDirty(int x,int z) {
        long k=key(x,z);
        if (!chunks.containsKey(k)) return;
        meshEpochs.put(k,meshEpochs.getOrDefault(k,0)+1);
        MeshJob previous=meshJobs.remove(k);
        if(previous!=null)previous.future().cancel(true);
        dirtyMeshes.add(k);
        Chunk lodChunk=chunks.get(k);
        LodJob lodJob=lodJobs.remove(k);
        if(lodJob!=null)lodJob.future().cancel(true);
        if(lodChunk!=null){lodChunk.lodVertices=null;lodChunk.lodFluidVertices=null;lodChunk.lodRevision++;}
    }
    private MeshSnapshot snapshot(Chunk c) {
        Chunk w=chunks.get(key(c.cx-1,c.cz)), e=chunks.get(key(c.cx+1,c.cz));
        Chunk n=chunks.get(key(c.cx,c.cz-1)), so=chunks.get(key(c.cx,c.cz+1));
        return new MeshSnapshot(c.cx,c.cz,c.copyBlocks(),w==null?null:w.copyBlocks(),
            e==null?null:e.copyBlocks(),n==null?null:n.copyBlocks(),so==null?null:so.copyBlocks(),generator);
    }
    private float[][] buildColumnMesh(MeshSnapshot snap) {
        float[][] built=new float[SECTION_COUNT*2][];
        for(int i=0;i<SECTION_COUNT;i++) {
            if(Thread.currentThread().isInterrupted())throw new CancellationException();
            float[][] layers=buildSectionLayers(snap,i);
            built[i]=layers[0];
            built[i+SECTION_COUNT]=layers[1];
        }
        return built;
    }
    public int pendingMeshCount() {return meshJobs.size()+dirtyMeshes.size();}
    public int pendingLodCount() {return lodJobs.size();}
    public int readyLodCount() {int n=0;for(Chunk c:chunks.values())if(c.lodVertices!=null)n++;return n;}
    private void rebuildNeighbor(int x,int z){Chunk c=chunks.get(key(x,z));if(c!=null)rebuild(c);}
    private void rebuildNeighborSection(int x,int z,int section){Chunk c=chunks.get(key(x,z));if(c!=null)rebuildSection(c,section);}
    private Chunk generate(int cx,int cz){
        Chunk c=new Chunk(cx,cz);
        // Cache nearby tree anchors once per column to avoid repeatedly sampling
        // tree candidates for each surface block. Includes anchors across seams.
        java.util.List<WorldGenerator.Tree> litterTrees = new java.util.ArrayList<>();
        final int litterRadius = 3;
        int wx0=cx*CHUNK_SIZE, wz0=cz*CHUNK_SIZE;
        for(int tcx=Math.floorDiv(wx0-litterRadius,WorldGenerator.TREE_CELL_SIZE);
                tcx<=Math.floorDiv(wx0+CHUNK_SIZE-1+litterRadius,WorldGenerator.TREE_CELL_SIZE);tcx++)
            for(int tcz=Math.floorDiv(wz0-litterRadius,WorldGenerator.TREE_CELL_SIZE);
                    tcz<=Math.floorDiv(wz0+CHUNK_SIZE-1+litterRadius,WorldGenerator.TREE_CELL_SIZE);tcz++) {
                WorldGenerator.Tree tree=generator.treeForCell(tcx,tcz);
                if(tree!=null)litterTrees.add(tree);
            }
        for(int lx=0;lx<CHUNK_SIZE;lx++)for(int lz=0;lz<CHUNK_SIZE;lz++){
            int wx=cx*CHUNK_SIZE+lx,wz=cz*CHUNK_SIZE+lz;
            WorldGenerator.Surface surface=generator.surface(wx,wz);
            int top=Math.max(1,Math.min(WORLD_HEIGHT-2,surface.surfaceY()));
            int slope=generator.slopeAt(wx,wz);
            BlockType topBlock=distribution.exposedTop(surface.biome(),wx,wz,top,generator.settings().seaLevel(),
                    slope,material(surface.topTexture(), BlockType.GRASS));
            // Small irregular litter patches immediately beneath matching trees.
            // Tree anchors are deterministic in world coordinates, including borders.
            if(slope<=3 && surface.waterBody()==WorldGenerator.WaterBody.LAND) {
                for(WorldGenerator.Tree tree:litterTrees) {
                    int dx=wx-tree.x(),dz=wz-tree.z();
                    if(dx*dx+dz*dz>9)continue;
                    if(Math.abs(top-generator.surface(tree.x(),tree.z()).surfaceY())>2)continue;
                    BlockType litter=switch(tree.type()) {
                        case PINE -> BlockType.PINE_NEEDLE_LITTER;
                        case MAPLE -> surface.biome()==Biome.EMBERLEAF_WOODLAND
                                ? BlockType.AUTUMN_LEAF_LITTER : BlockType.LEAF_LITTER;
                        case OAK -> BlockType.LEAF_LITTER;
                        default -> null;
                    };
                    if(litter==null)continue;
                    long h=((long)wx*0x9E3779B97F4A7C15L)^((long)wz*0xC2B2AE3D27D4EB4FL)
                            ^((long)tree.x()*0x165667B19E3779F9L)^((long)tree.z()*0xD6E8FEB86659FD93L);
                    h^=h>>>33;h*=0xff51afd7ed558ccdL;h^=h>>>33;
                    if((h&15L)<(dx*dx+dz*dz<=2?13:7))topBlock=litter;
                    break;
                }
            }
            // Submerged surfaces are sediments, not living turf. Select in world
            // coordinates so underwater beds meet naturally across chunks.
            boolean submerged=top<generator.settings().seaLevel();
            if(submerged || surface.waterBody()==WorldGenerator.WaterBody.RIVER
                    || surface.waterBody()==WorldGenerator.WaterBody.LAKE)
                topBlock=seabedAt(wx,wz,top,generator.settings().seaLevel(),slope,surface);
            BlockType filler=distribution.under(surface.biome(),wx,wz,material(surface.fillerTexture(), BlockType.DIRT));
            if(submerged) filler=(surface.waterDepth()>22 ? BlockType.GRAVEL :
                    surface.rainfall()>0.64 ? BlockType.CLAY : BlockType.SAND);
            BlockType baseRock=material(surface.baseRockTexture(), BlockType.STONE);
            // Surface geology is a shallow regional accent, not the entire 512-block column.
            BlockType geologicalRock=distribution.rock(surface.biome(),wx,wz,baseRock);
            int soilDepth=distribution.soilDepth(surface.biome(),wx,wz);
            // Shallow, exposed ridges lose soil; sheltered and wet valleys build it up.
            if(slope>=7) soilDepth=Math.min(soilDepth,1);
            else if(slope>=4) soilDepth=Math.min(soilDepth,2);
            else if(surface.waterBody()!=WorldGenerator.WaterBody.LAND)soilDepth=Math.min(8,soilDepth+2);
            else if(slope<=1 && surface.rainfall()>0.68)soilDepth=Math.min(8,soilDepth+1);
            if(slope>=7 && topBlock!=BlockType.PACKED_SNOW && topBlock!=BlockType.SNOW_CRUST)
                topBlock=baseRock;
            // The first 3D density pass works above and below the old heightmap.
            // Sample only the upper terrain band; deep rock remains inexpensive.
            int highest=Math.min(WORLD_HEIGHT-2,top+8);
            for(int y=0;y<=highest;y++){
                if(y>3 && !generator.isSolidTerrain(wx,y,wz,top,slope))continue;
                if(y>3 && generator.isCave(wx,y,wz,top))continue;
                // Rock overhangs above the original height receive rock faces,
                // rather than floating copies of biome topsoil.
                // Ordinary stone dominates the crust. Deeper strata transition to
                // darker slate; biome-specific rock occurs as sparse coherent lenses.
                BlockType rock=naturalRock(wx,y,wz,top,geologicalRock);
                BlockType b=y==0?BlockType.BEDROCK:y>top?rock:y==top?topBlock:y>=top-soilDepth?filler:rock;
                c.blocks[index(lx,y,lz)]=b.id;
            }
        }
        // Directed regional tunnels and ravines supplement existing 3D noise caves.
        new CaveCarvers(seed).carve(c.blocks,cx,cz,generator);
        // Paint exposed cave surfaces after ALL carving, before features and ores.
        CaveBiomePainter.paint(c.blocks, cx, cz, generator, WORLD_HEIGHT);
        // Populate only cells belonging to this chunk. Candidates are sampled in
        // world coordinates, including adjacent cells whose crowns overlap us.
        populateFeatures(c);
        generateOreVeins(c);
        placeNaturalFluids(c);
        // Edits are applied when the completed chunk is installed on the main thread.
        return c;
    }
    /** Deterministic static water: submerged ocean/river terrain and rare cave pools.
     * This runs on the generation worker and never enqueues millions of ocean cells.
     */
    private void placeNaturalFluids(Chunk c) {
        int sea=generator.settings().seaLevel();
        for(int lx=0;lx<CHUNK_SIZE;lx++)for(int lz=0;lz<CHUNK_SIZE;lz++) {
            int wx=c.cx*CHUNK_SIZE+lx,wz=c.cz*CHUNK_SIZE+lz;
            WorldGenerator.Surface surface=generator.surface(wx,wz);
            if(surface.waterBody()!=WorldGenerator.WaterBody.LAND) {
                int y0=Math.max(1,surface.surfaceY()+1);
                // Inland streams and basin lakes have local levels above sea level.
                // Unlike fluid ticks, generated water is inert until disturbed.
                int surfaceLevel=surface.waterSurfaceY();
                for(int y=y0;y<=Math.min(surfaceLevel,WORLD_HEIGHT-2);y++) {
                    int at=index(lx,y,lz);
                    if(c.blocks[at]==BlockType.AIR.id)c.blocks[at]=BlockType.WATER.id;
                }
                // On a sharp downhill step, extend a bounded sheet of flowing
                // water from the upstream river level. Neighbor sampling uses
                // world coordinates, so waterfalls continue across chunk seams.
                if(surface.waterBody()==WorldGenerator.WaterBody.RIVER) {
                    int upstream=surfaceLevel;
                    for(int[] d : new int[][]{{1,0},{-1,0},{0,1},{0,-1}}) {
                        WorldGenerator.Surface neighbor=generator.surface(wx+d[0],wz+d[1]);
                        if(neighbor.waterBody()==WorldGenerator.WaterBody.RIVER)
                            upstream=Math.max(upstream,neighbor.waterSurfaceY());
                    }
                    if(upstream>=surfaceLevel+4) {
                        int fallTop=Math.min(WORLD_HEIGHT-2,Math.min(upstream-1,surfaceLevel+10));
                        for(int y=surfaceLevel+1;y<=fallTop;y++) {
                            int at=index(lx,y,lz);
                            if(c.blocks[at]!=BlockType.AIR.id)break;
                            c.blocks[at]=BlockType.FLOWING_WATER.id;
                        }
                    }
                }
            }
            // Rare underground water-table pockets, inside already carved caves.
            // Shared world-coordinate region cells keep chunk seams consistent.
            int rx=Math.floorDiv(wx,96),rz=Math.floorDiv(wz,96);
            long region=rockHash(rx,13,rz);
            boolean aquifer=(region&15L)==0L && surface.rainfall()>0.42;
            boolean lavaPocket=(rockHash(rx,71,rz)&31L)==0L;
            if(!aquifer&&!lavaPocket)continue;
            // Previous sea-75 placed the water table below the simulated range
            // when sea level was 64, silently disabling underground water.
            int aquiferLevel=Math.max(24,Math.min(sea-12,WORLD_HEIGHT-120));
            for(int y=8;y<=Math.max(aquiferLevel,28);y++) {
                boolean water=aquifer&&y>=16&&y<=aquiferLevel;
                boolean lava=!water&&lavaPocket&&y<=22;
                if(!water&&!lava)continue;
                int at=index(lx,y,lz);
                // Require a solid basin floor and a roof or deep overburden:
                // open shafts must not become artificial vertical waterfalls.
                if(c.blocks[at]!=BlockType.AIR.id || c.blocks[at-1]==BlockType.AIR.id)continue;
                int ceiling=Math.min(WORLD_HEIGHT-2,Math.min(y+5,surface.surfaceY()-18));
                for(int fy=y;fy<=ceiling;fy++) {
                    int fluidIndex=index(lx,fy,lz);
                    if(c.blocks[fluidIndex]!=BlockType.AIR.id)break;
                    c.blocks[fluidIndex]=water?BlockType.WATER.id:BlockType.LAVA.id;
                }
            }
        }
    }

    private void scheduleFluid(int x,int y,int z) {
        if(y<1||y>=WORLD_HEIGHT-1||fluidQueue.size()>=FLUID_QUEUE_LIMIT)return;
        BlockType b=getBlockAt(x,y,z);
        if(!b.isFluid())return;
        int strength=(b==BlockType.WATER||b==BlockType.LAVA)?7:
                activeFluidStrength.getOrDefault(new FluidPosition(x,y,z),1);
        fluidQueue.addLast(new FluidTask(x,y,z,b,strength));
    }
    private void processFluidUpdates(int budget) {
        // Lava advances more slowly without checking inert lava pools every tick.
        fluidFrame++;
        for(int processed=0;processed<budget&&!fluidQueue.isEmpty();processed++) {
            FluidTask task=fluidQueue.removeFirst();
            BlockType b=getBlockAt(task.x,task.y,task.z);
            if(!b.isFluid())continue;
            if(b.isLava() && (fluidFrame&3)!=0) {
                if(fluidQueue.size()<FLUID_QUEUE_LIMIT)fluidQueue.addLast(task);
                continue;
            }
            BlockType flow=b.isWater()?BlockType.FLOWING_WATER:BlockType.FLOWING_LAVA;
            if(tryFluid(task.x,task.y-1,task.z,flow,task.strength))continue;
            if(task.strength<=1)continue;
            int next=task.strength-1;
            tryFluid(task.x+1,task.y,task.z,flow,next);
            tryFluid(task.x-1,task.y,task.z,flow,next);
            tryFluid(task.x,task.y,task.z+1,flow,next);
            tryFluid(task.x,task.y,task.z-1,flow,next);
        }
    }
    private boolean tryFluid(int x,int y,int z,BlockType flow,int strength) {
        if(y<=0||y>=WORLD_HEIGHT-1||strength<1)return false;
        int cx=Math.floorDiv(x,CHUNK_SIZE),cz=Math.floorDiv(z,CHUNK_SIZE);
        Chunk c=chunks.get(key(cx,cz));if(c==null)return false;
        int pos=index(Math.floorMod(x,CHUNK_SIZE),y,Math.floorMod(z,CHUNK_SIZE));
        BlockType old=BlockType.fromId(c.get(pos));
        if(old.isFluid() && old.isWater()!=flow.isWater()) {
            // Simplified water/lava reaction. Rock remains persistent in the save.
            c.set(pos,BlockType.OBSIDIAN.id);
            edits.computeIfAbsent(key(cx,cz),k->new HashMap<>()).put(pos,BlockType.OBSIDIAN.id);
            markMeshDirty(cx,cz);version++;return true;
        }
        if(old!=BlockType.AIR)return false;
        c.set(pos,flow.id);
        edits.computeIfAbsent(key(cx,cz),k->new HashMap<>()).put(pos,flow.id);
        activeFluidStrength.put(new FluidPosition(x,y,z),strength);
        if(fluidQueue.size()<FLUID_QUEUE_LIMIT)fluidQueue.addLast(new FluidTask(x,y,z,flow,strength));
        markMeshDirty(cx,cz);
        if(Math.floorMod(x,CHUNK_SIZE)==0)markMeshDirty(cx-1,cz);
        if(Math.floorMod(x,CHUNK_SIZE)==CHUNK_SIZE-1)markMeshDirty(cx+1,cz);
        if(Math.floorMod(z,CHUNK_SIZE)==0)markMeshDirty(cx,cz-1);
        if(Math.floorMod(z,CHUNK_SIZE)==CHUNK_SIZE-1)markMeshDirty(cx,cz+1);
        version++;return true;
    }

    /** Main host rock for 512-block worlds. No new block IDs or save migration.
     * Low-cost world-space hash gives broad strata and small geological lenses.
     * Deep sections are slate-rich, with stone still present and rare host-rock veins.
     */
    private static BlockType naturalRock(int x,int y,int z,int top,BlockType accent) {
        // Stratification is mostly altitude-dependent; slight spatial variation
        // prevents a perfectly horizontal line at the deep-rock boundary.
        int deepBoundary=126 + (int)(rockHash(Math.floorDiv(x,32),0,Math.floorDiv(z,32)) & 23L)-12;
        long province=rockHash(Math.floorDiv(x,18),Math.floorDiv(y,12),Math.floorDiv(z,18));
        int roll=(int)((province>>>24)&255L);
        if(y<deepBoundary-28) {
            if(roll<14 && accent!=BlockType.AIR) return accent;
            return roll<190 ? BlockType.SLATE : BlockType.STONE;
        }
        if(y<deepBoundary+16) {
            int slateChance=Math.max(0,Math.min(190,(deepBoundary+16-y)*4));
            if(roll<slateChance)return BlockType.SLATE;
            if(roll>244 && accent!=BlockType.AIR)return accent;
            return BlockType.STONE;
        }
        // Surface geology affects only a small fraction of upper host rock.
        if(roll<24 && accent!=BlockType.AIR)return accent;
        return BlockType.STONE;
    }
    /** Shallow beaches transition to gravel and rocky deep-sea floors. */
    private BlockType seabedAt(int x,int z,int y,int sea,int slope,WorldGenerator.Surface surface) {
        int depth=Math.max(0,sea-y);
        // Coarse deterministic patches, with finer local breakup; no grass under water.
        double broad=depositNoise.fbm2(x/44.0,z/44.0,3,0x57eab1L);
        double detail=depositNoise.fbm2(x/12.0,z/12.0,2,0x781c2dL);
        double d=broad*0.8+detail*0.2;
        if(surface.waterBody()==WorldGenerator.WaterBody.RIVER)
            return slope>5?BlockType.WATERWORN_STONE:d>.10?BlockType.GRAVEL:BlockType.RIVERBED_SEDIMENT;
        if(surface.waterBody()==WorldGenerator.WaterBody.LAKE)
            return slope>4?BlockType.GRAVEL:d<-.20?BlockType.CLAY:BlockType.RIVERBED_SEDIMENT;
        if(depth<=5) return d>.31?BlockType.GRAVEL:d<-.38?BlockType.CLAY:BlockType.SAND;
        if(depth<20) return slope>5?BlockType.WATERWORN_STONE:
                d>.19?BlockType.GRAVEL:d<-.38?BlockType.CLAY:BlockType.SAND;
        return slope>5?BlockType.STONE:d>.26?BlockType.WATERWORN_STONE:
                d<-.34?BlockType.CLAY:BlockType.GRAVEL;
    }
    private static long rockHash(int x,int y,int z) {
        long v=((long)x*0x9E3779B97F4A7C15L)^((long)y*0xD6E8FEB86659FD93L)
            ^((long)z*0xC2B2AE3D27D4EB4FL);
        v=(v^(v>>>30))*0xbf58476d1ce4e5b9L;
        v=(v^(v>>>27))*0x94d049bb133111ebL;
        return v^(v>>>31);
    }
    /** Deterministic feature population, independent of chunk generation order. */
    private void populateFeatures(Chunk c) {
        int minX=c.cx*CHUNK_SIZE, minZ=c.cz*CHUNK_SIZE;
        int maxX=minX+CHUNK_SIZE-1, maxZ=minZ+CHUNK_SIZE-1;
        int treeCell=WorldGenerator.TREE_CELL_SIZE;
        // Large species crowns may extend eight blocks across chunk borders.
        for(int tx=Math.floorDiv(minX-9,treeCell);tx<=Math.floorDiv(maxX+9,treeCell);tx++)
            for(int tz=Math.floorDiv(minZ-9,treeCell);tz<=Math.floorDiv(maxZ+9,treeCell);tz++) {
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
        if(c.get(idx)==BlockType.AIR.id)c.set(idx,type.id);
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
        // Branch-first broadleaf construction. Branch geometry is sampled only from
        // the immutable tree anchor, so neighboring chunks produce identical limbs.
        if (t.type()==WorldGenerator.TreeType.OAK || t.type()==WorldGenerator.TreeType.MAPLE
                || t.type()==WorldGenerator.TreeType.WILLOW) {
            int count=branchCount(t);
            for(int b=0;b<count;b++) {
                int[] tip=branchTip(t,b);
                int startY=y+(int)(t.trunkHeight()*.57)+(b%3);
                int endY=y+t.trunkHeight()+tip[1];
                int steps=Math.max(Math.abs(tip[0]),Math.max(Math.abs(tip[2]),Math.abs(endY-startY)))*2;
                for(int i=0;i<=steps;i++) {
                    double f=i/(double)Math.max(1,steps);
                    int bx=x+(int)Math.round(tip[0]*f), bz=z+(int)Math.round(tip[2]*f);
                    int by=(int)Math.round(startY+(endY-startY)*f);
                    BlockType branchLog=log.orientedLog(Math.abs(tip[0])>=Math.abs(tip[2])?0:2);
                    putFeature(c,bx,by,bz,branchLog,minX,minZ);
                }
            }
        }
    }
    private static int branchCount(WorldGenerator.Tree tree) {
        return switch(tree.type()) {case OAK -> 4; case MAPLE -> 6; case WILLOW -> 6; default -> 0;};
    }
    /** Returns relative X, vertical displacement from the trunk tip, and Z. */
    private static int[] branchTip(WorldGenerator.Tree t,int branch) {
        long v=((long)t.x()*0x9E3779B97F4A7C15L)^((long)t.z()*0xC2B2AE3D27D4EB4FL)
                ^((long)branch*0x165667B19E3779F9L);
        v ^= v>>>30; v*=0xbf58476d1ce4e5b9L;v^=v>>>27;
        double angle=2*Math.PI*(branch+.23+((v>>>12)&255)/1024.0)/branchCount(t);
        int length=switch(t.type()) {case OAK->3+(int)((v>>>24)&3);case MAPLE->3+(int)((v>>>24)&3);
            case WILLOW->5+(int)((v>>>24)&3);default->2;};
        int rise=switch(t.type()) {case OAK->-2+(branch%3);case MAPLE->-1+(branch%3);
            case WILLOW->-2+(branch%2);default->0;};
        return new int[]{(int)Math.round(length*Math.cos(angle)),rise,(int)Math.round(length*Math.sin(angle))};
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
        return BlockType.fromId(c.get(index(Math.floorMod(x,CHUNK_SIZE),y,Math.floorMod(z,CHUNK_SIZE))));
    }
    public boolean setBlock(int x,int y,int z,BlockType type){
        if(type==null||y<0||y>=WORLD_HEIGHT)return false;
        int cx=Math.floorDiv(x,CHUNK_SIZE),cz=Math.floorDiv(z,CHUNK_SIZE);
        Chunk c=chunks.get(key(cx,cz));if(c==null)return false;
        int i=index(Math.floorMod(x,CHUNK_SIZE),y,Math.floorMod(z,CHUNK_SIZE));
        if(c.get(i)==type.id)return false;
        BlockType previous=BlockType.fromId(c.get(i));
        c.set(i,type.id);
        activeFluidStrength.remove(new FluidPosition(x,y,z));
        if(previous.isFluid()||type==BlockType.AIR||type.isFluid()) {
            scheduleFluid(x+1,y,z);scheduleFluid(x-1,y,z);
            scheduleFluid(x,y,z+1);scheduleFluid(x,y,z-1);
            scheduleFluid(x,y+1,z);scheduleFluid(x,y-1,z);
            scheduleFluid(x,y,z);
        }
        edits.computeIfAbsent(key(cx,cz),k->new HashMap<>()).put(i,type.id);
        int section=y/SECTION_HEIGHT;
        markMeshDirty(cx,cz);
        if(Math.floorMod(x,CHUNK_SIZE)==0)markMeshDirty(cx-1,cz);
        if(Math.floorMod(x,CHUNK_SIZE)==CHUNK_SIZE-1)markMeshDirty(cx+1,cz);
        if(Math.floorMod(z,CHUNK_SIZE)==0)markMeshDirty(cx,cz-1);
        if(Math.floorMod(z,CHUNK_SIZE)==CHUNK_SIZE-1)markMeshDirty(cx,cz+1);
        version++;return true;
    }
    public float getHeightAt(float x,float z){
        int bx=(int)Math.floor(x),bz=(int)Math.floor(z);
        // Spawn queries may precede streaming into a new area.
        for(int y=WORLD_HEIGHT-1;y>=0;y--)if(getBlockAt(bx,y,bz).isSolid())return y+1;
        return Math.max(1,Math.min(WORLD_HEIGHT-2,generator.surface(bx,bz).surfaceY()+1));
    }
    /** Read-only mesh descriptions; mesh arrays are replaced, never mutated. */
    public record ChunkMesh(int x, int section, int z, int revision, float[] vertices) {}
    /**
     * Surface-only distant LOD. Each 2x2 patch produces one top tile plus exposed
     * outer faces. Full-resolution blocks remain stored for edits and collision.
     * This intentionally omits underground cave walls and decorative foliage.
     */
    private LodMeshes buildDistantMeshes(Chunk c) {
        // Sample the LAND SURFACE, not the tallest log. Trunks are modeled separately.
        // The same world-space height sampler is used on either side of chunk seams.
        final int step=2, tiles=CHUNK_SIZE/step;
        int[][] top=new int[tiles][tiles], layer=new int[tiles][tiles];
        int[][] ground=new int[tiles][tiles],groundLayer=new int[tiles][tiles];
        boolean[][] wet=new boolean[tiles][tiles];
        int ox=c.cx*CHUNK_SIZE, oz=c.cz*CHUNK_SIZE;
        for(int tx=0;tx<tiles;tx++)for(int tz=0;tz<tiles;tz++) {
            int wx=ox+tx*step, wz=oz+tz*step;
            WorldGenerator.Surface surf=generator.surface(wx,wz);
            int h=surf.waterBody()!=WorldGenerator.WaterBody.LAND
                ? Math.max(surf.surfaceY(),surf.waterSurfaceY()) : surf.surfaceY();
            top[tx][tz]=h;
            byte id=c.get(index(tx*step,Math.max(0,Math.min(WORLD_HEIGHT-1,h)),tz*step));
            BlockType sampled=BlockType.fromId(id);
            wet[tx][tz]=sampled.isFluid();
            layer[tx][tz]=sampled.getTopTextureLayer();
            ground[tx][tz]=Math.max(0,Math.min(WORLD_HEIGHT-1,surf.surfaceY()));
            groundLayer[tx][tz]=BlockType.fromId(c.get(index(tx*step,ground[tx][tz],tz*step))).getTopTextureLayer();
        }
        FloatArrayBuilder out=new FloatArrayBuilder();
        FloatArrayBuilder water=new FloatArrayBuilder();
        for(int tx=0;tx<tiles;tx++)for(int tz=0;tz<tiles;tz++) {
            int h=top[tx][tz],x=ox+tx*step,z=oz+tz*step;
            if(h<0)continue;
            float y=h+1f;
            int tex=layer[tx][tz];
            // Keep distant water translucent as a separate mesh, not as an
            // opaque blue terrain tile. The seabed remains visible beneath it.
            if(wet[tx][tz]) {
                float waterY=h+.94f;
                face(water,x,waterY,z+step,x+step,waterY,z+step,x+step,waterY,z,x,waterY,z,tex,1f);
                float bedY=ground[tx][tz]+1f;
                face(out,x,bedY,z+step,x+step,bedY,z+step,x+step,bedY,z,x,bedY,z,groundLayer[tx][tz],1f);
                continue;
            }
            face(out,x,y,z+step,x+step,y,z+step,x+step,y,z,x,y,z,tex,1f);
            // Boundary neighbors are sampled in world space, not defaulted to h-1.
            // This prevents seam walls when adjacent LOD columns load separately.
            int south=tz+1<tiles?top[tx][tz+1]:distantSurfaceY(x,z+step);
            int north=tz>0?top[tx][tz-1]:distantSurfaceY(x,z-step);
            int east=tx+1<tiles?top[tx+1][tz]:distantSurfaceY(x+step,z);
            int west=tx>0?top[tx-1][tz]:distantSurfaceY(x-step,z);
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
                int crownY=treeGround+tree.trunkHeight();
                int r=switch(tree.type()) {case OAK,MAPLE->4;case WILLOW->5;case PINE,CYPRESS->2;};
                int depth=switch(tree.type()) {case PINE,CYPRESS->7;default->4;};
                int logTex=switch(tree.type()) {
                    case OAK->BlockType.OAK_LOG.getSideTextureLayer();
                    case MAPLE->BlockType.MAPLE_LOG.getSideTextureLayer();
                    case PINE->BlockType.PINE_LOG.getSideTextureLayer();
                    case WILLOW->BlockType.WILLOW_LOG.getSideTextureLayer();
                    case CYPRESS->BlockType.CYPRESS_LOG.getSideTextureLayer();
                };
                // Two crossed trunk planes and two crossed cutout crown planes.
                float x=tree.x()+.5f,z=tree.z()+.5f;
                face(out,x-.5f,treeGround,z,x+.5f,treeGround,z,x+.5f,crownY,z,x-.5f,crownY,z,logTex,.85f);
                face(out,x,treeGround,z-.5f,x,treeGround,z+.5f,x,crownY,z+.5f,x,crownY,z-.5f,logTex,.85f);
                int leaf=leafLayer(tree.type());
                float bottom=crownY-depth,topY=crownY+1;
                face(out,x-r,bottom,z,x+r,bottom,z,x+r,topY,z,x-r,topY,z,leaf,1f);
                face(out,x,bottom,z-r,x,bottom,z+r,x,topY,z+r,x,topY,z-r,leaf,1f);
            }
        return new LodMeshes(out.toArray(),water.toArray());
    }

    private int distantSurfaceY(int x,int z) {
        WorldGenerator.Surface surface=generator.surface(x,z);
        return surface.waterBody()!=WorldGenerator.WaterBody.LAND
                ? Math.max(surface.surfaceY(),surface.waterSurfaceY()) : surface.surfaceY();
    }
    /** Choose one representation per column; section=-1 identifies a distant mesh.
     * Builds far meshes on a worker from private block snapshots. Until a far mesh is
     * ready the existing detailed sections remain visible (no terrain holes).
     */
    private boolean lodBuildPending=false;
    public boolean lodBuildPending(){return lodBuildPending;}
    public java.util.List<ChunkMesh> chunkMeshesForView(int cameraChunkX,int cameraChunkZ,int detailRadius){
        // Futures are consumed on the game thread only. World resets invalidate jobs.
        Iterator<Map.Entry<Long,LodJob>> iterator=lodJobs.entrySet().iterator();
        while(iterator.hasNext()){
            var entry=iterator.next();
            LodJob job=entry.getValue();
            if(!job.future().isDone())continue;
            iterator.remove();
            Chunk chunk=chunks.get(entry.getKey());
            if(chunk==null || job.generation()!=meshGeneration || job.revision()!=chunk.lodRevision)continue;
            try {
                LodMeshes built=job.future().get();
                chunk.lodVertices=built.terrain();
                chunk.lodFluidVertices=built.fluids();
                version++;
            } catch(CancellationException ignored) {
            } catch(ExecutionException e) {
                throw new IllegalStateException("Background distant LOD generation failed",e.getCause());
            } catch(InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        lodBuildPending=false;
        java.util.ArrayList<Chunk> ordered=new java.util.ArrayList<>(chunks.values());
        ordered.sort(java.util.Comparator.comparingLong(c->{
            long dx=(long)c.cx-cameraChunkX,dz=(long)c.cz-cameraChunkZ;
            return dx*dx+dz*dz;
        }));
        java.util.ArrayList<ChunkMesh> result=new java.util.ArrayList<>(chunks.size()*4);
        for(Chunk c:ordered){
            int radius=Math.max(Math.abs(c.cx-cameraChunkX),Math.abs(c.cz-cameraChunkZ));
            long k=key(c.cx,c.cz);
            // Both meshes exist in the transition ring for a dithered crossfade.
            boolean needsFar = radius>=Math.max(2,detailRadius-2);
            boolean needsNear = radius<=detailRadius+2;
            if(needsFar){
                if(c.lodVertices==null){
                    lodBuildPending=true;
                    if(!lodJobs.containsKey(k) && lodJobs.size()<2){
                        // Never read mutable live blocks from a worker thread.
                        byte[] snapshot=c.copyBlocks();
                        int revision=c.lodRevision,generation=meshGeneration;
                        int chunkX=c.cx,chunkZ=c.cz;
                        Future<LodMeshes> task=lodWorker.submit(()->{
                            Chunk immutable=new Chunk(chunkX,chunkZ);
                            immutable.blocks=snapshot;
                            return buildDistantMeshes(immutable);
                        });
                        lodJobs.put(k,new LodJob(task,revision,generation));
                    }
                }else if(c.lodVertices.length>0){
                    result.add(new ChunkMesh(c.cx,-1,c.cz,c.lodRevision,c.lodVertices));
                    if(c.lodFluidVertices!=null && c.lodFluidVertices.length>0)
                        result.add(new ChunkMesh(c.cx,-2,c.cz,c.lodRevision,c.lodFluidVertices));
                }
                // Keep fully detailed geometry until the far mesh is ready.
                if(!needsNear && c.lodVertices!=null)continue;
            }
            for(int section=0;section<SECTION_COUNT;section++) {
                if(c.sectionVertices[section].length>0)
                    result.add(new ChunkMesh(c.cx,section,c.cz,c.sectionRevision[section],c.sectionVertices[section]));
                if(c.fluidVertices[section].length>0)
                    result.add(new ChunkMesh(c.cx,section+FLUID_SECTION_OFFSET,c.cz,c.fluidRevision[section],c.fluidVertices[section]));
            }
        }
        return result;
    }

    public java.util.List<ChunkMesh> chunkMeshes(){
        java.util.ArrayList<ChunkMesh> result=new java.util.ArrayList<>(chunks.size()*SECTION_COUNT);
        for(Chunk c:chunks.values()) for(int section=0;section<SECTION_COUNT;section++) {
            if(c.sectionVertices[section].length>0)result.add(new ChunkMesh(c.cx,section,c.cz,c.sectionRevision[section],c.sectionVertices[section]));
            if(c.fluidVertices[section].length>0)result.add(new ChunkMesh(c.cx,section+FLUID_SECTION_OFFSET,c.cz,c.fluidRevision[section],c.fluidVertices[section]));
        }
        return result;
    }
    public void newWorld(long newSeed){
        cancelGeneration();edits.clear();seed=newSeed;initializeGenerator();chunks.clear();streamed=false;
        generateSpawnChunk(16,27);version++;
    }
    public int getVersion(){return version;}
    public float[] getVertices(){
        int n=0;for(Chunk c:chunks.values()) {
            for(float[] section:c.sectionVertices)n+=section.length;
            for(float[] section:c.fluidVertices)n+=section.length;
        }
        float[] all=new float[n];int offset=0;
        for(Chunk c:chunks.values()) {
            for(float[] section:c.sectionVertices){System.arraycopy(section,0,all,offset,section.length);offset+=section.length;}
            for(float[] section:c.fluidVertices){System.arraycopy(section,0,all,offset,section.length);offset+=section.length;}
        }
        return all;
    }
    public int getVertexCount(){int n=0;for(Chunk c:chunks.values()){
        for(float[] section:c.sectionVertices)n+=section.length/FLOATS_PER_VERTEX;
        for(float[] section:c.fluidVertices)n+=section.length/FLOATS_PER_VERTEX;
    }return n;}
    /** v2 stores seed and all edits, independent of which chunks are loaded. */
    public void saveTo(Path path)throws IOException{
        Path p=path.toAbsolutePath();Files.createDirectories(p.getParent());Path temp=p.resolveSibling(p.getFileName()+".tmp");
        try{
            try(DataOutputStream out=new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(temp)))){
                out.writeInt(MAGIC);out.writeInt(SAVE_VERSION);out.writeLong(seed);out.writeInt(WORLD_HEIGHT);out.writeInt(edits.size());
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
        HashMap<Long,HashMap<Integer,Byte>> incoming=new HashMap<>();long newSeed=DEFAULT_SEED;boolean migrateLegacy=false;
        try(DataInputStream in=new DataInputStream(new BufferedInputStream(Files.newInputStream(path)))){
            if(in.readInt()!=MAGIC)throw new IOException("Not a Wildforge world save");
            int v=in.readInt();
            migrateLegacy=v<3;
            if(v==1){
                int width=in.readInt(),height=in.readInt(),depth=in.readInt();
                if(width!=64||height!=32||depth!=64)throw new IOException("Unexpected legacy world dimensions");
                for(int x=0;x<64;x++)for(int y=0;y<32;y++)for(int z=0;z<64;z++){
                    byte b=in.readByte();if(!BlockType.isValidId(b))throw new IOException("Invalid legacy block id");
                    int cx=x/16,cz=z/16,i=index(x%16,y+160,z%16);
                    incoming.computeIfAbsent(key(cx,cz),k->new HashMap<>()).put(i,b);
                }
            }else if(v==2 || v==3){
                newSeed=in.readLong();
                if(v==3){int savedHeight=in.readInt();if(savedHeight!=WORLD_HEIGHT)throw new IOException("Unsupported saved world height: "+savedHeight);}
                int count=in.readInt();
                if(count<0||count>1000000)throw new IOException("Invalid chunk count");
                for(int n=0;n<count;n++){
                    long k=in.readLong();int size=in.readInt();
                    if(size<0||size>CHUNK_SIZE*CHUNK_SIZE*WORLD_HEIGHT)throw new IOException("Invalid edit count");
                    HashMap<Integer,Byte> changes=incoming.computeIfAbsent(k,ignore->new HashMap<>());
                    for(int j=0;j<size;j++){
                        int idx=in.readInt();byte b=in.readByte();
                        int limit=CHUNK_SIZE*CHUNK_SIZE*(v==2?256:WORLD_HEIGHT);
                        if(idx<0||idx>=limit||!BlockType.isValidId(b))throw new IOException("Invalid edit");
                        // Upgrade v2 column-major edits to new 512-block columns,
                        // shifting legacy surfaces upward by 160 blocks.
                        int upgraded=v==2?(idx/256)*WORLD_HEIGHT+(idx%256)+160:idx;
                        changes.put(upgraded,b);
                    }
                }
            }else throw new IOException("Unsupported world save version: "+v);
            if(in.read()!=-1)throw new IOException("World save has trailing data");
        }
        // Commit only after complete validation.
        legacyVerticalMigration=migrateLegacy;
        cancelGeneration();seed=newSeed;initializeGenerator();edits.clear();edits.putAll(incoming);
        chunks.clear();streamed=false;generateSpawnChunk(16,27);version++;
    }
    private static boolean fluidId(byte id) {
        return id==BlockType.WATER.id||id==BlockType.LAVA.id||
               id==BlockType.FLOWING_WATER.id||id==BlockType.FLOWING_LAVA.id;
    }
    private static boolean faceVisible(byte current,byte neighbor) {
        if(neighbor==BlockType.AIR.id)return true;
        if(!fluidId(current))return fluidId(neighbor);
        if(!fluidId(neighbor))return false;
        boolean currentWater=current==BlockType.WATER.id||current==BlockType.FLOWING_WATER.id;
        boolean otherWater=neighbor==BlockType.WATER.id||neighbor==BlockType.FLOWING_WATER.id;
        return currentWater!=otherWater;
    }
    /** Build only exposed faces. Chunk neighbors are resolved once per rebuild. */
    private void rebuild(Chunk c){for(int section=0;section<SECTION_COUNT;section++)rebuildSection(c,section);}
    private void rebuildSection(Chunk c,int section){
        float[][] layers=buildSectionLayers(snapshot(c),section);
        c.sectionVertices[section]=layers[0];
        c.fluidVertices[section]=layers[1];
        c.sectionRevision[section]++;
        c.fluidRevision[section]++;
    }
    // Never mix translucent fluids into the opaque / cutout terrain mesh.
    // Both streams use the same immutable neighbor snapshot and revision epoch.
    private float[][] buildSectionLayers(MeshSnapshot snap,int section){
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
            int northColumn=lz==0?index(lx,0,CHUNK_SIZE-1):index(lx,0,lz-1);
            int southColumn=lz==CHUNK_SIZE-1?index(lx,0,0):index(lx,0,lz+1);
            int westColumn=lx==0?index(CHUNK_SIZE-1,0,lz):index(lx-1,0,lz);
            int eastColumn=lx==CHUNK_SIZE-1?index(0,0,lz):index(lx+1,0,lz);
            byte[] northSource=lz==0?northBlocks:blocks;
            byte[] southSource=lz==CHUNK_SIZE-1?southBlocks:blocks;
            byte[] westSource=lx==0?westBlocks:blocks;
            byte[] eastSource=lx==CHUNK_SIZE-1?eastBlocks:blocks;
            for(int y=section*SECTION_HEIGHT;y<(section+1)*SECTION_HEIGHT;y++){
                byte id=blocks[column+y];
                if(id==air)continue;
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
                    float top = y + (showTop ? (id==BlockType.WATER.id||id==BlockType.LAVA.id ? .94f : .80f) : 1f);
                    if(showSouth)face(fluid,x,y,z+1,x+1,y,z+1,x+1,top,z+1,x,top,z+1,block.textureForAxis(2),.86f);
                    if(showNorth)face(fluid,x+1,y,z,x,y,z,x,top,z,x+1,top,z,block.textureForAxis(2),.78f);
                    if(showEast)face(fluid,x+1,y,z+1,x+1,y,z,x+1,top,z,x+1,top,z+1,block.textureForAxis(0),.82f);
                    if(showWest)face(fluid,x,y,z,x,y,z+1,x,top,z+1,x,top,z,block.textureForAxis(0),.82f);
                    if(showTop)face(fluid,x,top,z+1,x+1,top,z+1,x+1,top,z,x,top,z,block.textureForAxis(1),1f);
                    if(showBottom)face(fluid,x,y,z,x+1,y,z,x+1,y,z+1,x,y,z+1,block.getBottomTextureLayer(),.72f);
                    continue;
                }
                if(showSouth)face(data,x,y,z+1,x+1,y,z+1,x+1,y+1,z+1,x,y+1,z+1,block.textureForAxis(2),.85f);
                if(showNorth)face(data,x+1,y,z,x,y,z,x,y+1,z,x+1,y+1,z,block.textureForAxis(2),.75f);
                if(showEast)face(data,x+1,y,z+1,x+1,y,z,x+1,y+1,z,x+1,y+1,z+1,block.textureForAxis(0),.8f);
                if(showWest)face(data,x,y,z,x,y,z+1,x,y+1,z+1,x,y+1,z,block.textureForAxis(0),.8f);
                if(showTop)face(data,x,y+1,z+1,x+1,y+1,z+1,x+1,y+1,z,x,y+1,z,block.textureForAxis(1),1f);
                if(showBottom)face(data,x,y,z,x+1,y,z,x+1,y,z+1,x,y,z+1,
                        block.isLog()?block.textureForAxis(1):block.getBottomTextureLayer(),.55f);
            }
        }
        appendFoliage(snap,data,section);
        return new float[][]{data.toArray(),fluid.toArray()};
    }
    private static BlockType snapshotBlockAt(MeshSnapshot snap,int x,int y,int z) {
        if(y<0||y>=WORLD_HEIGHT)return BlockType.AIR;
        int cx=Math.floorDiv(x,CHUNK_SIZE),cz=Math.floorDiv(z,CHUNK_SIZE);
        byte[] source;
        if(cx==snap.cx()&&cz==snap.cz())source=snap.blocks();
        else if(cx==snap.cx()-1&&cz==snap.cz())source=snap.west();
        else if(cx==snap.cx()+1&&cz==snap.cz())source=snap.east();
        else if(cx==snap.cx()&&cz==snap.cz()-1)source=snap.north();
        else if(cx==snap.cx()&&cz==snap.cz()+1)source=snap.south();
        else return BlockType.AIR;
        return source==null?BlockType.AIR:BlockType.fromId(source[index(Math.floorMod(x,CHUNK_SIZE),y,Math.floorMod(z,CHUNK_SIZE))]);
    }
    /** Visual-only alpha-cutout foliage: no block IDs, no collision, no save migration. */
    private void appendFoliage(MeshSnapshot snap, FloatArrayBuilder data, int section) {
        int minX=snap.cx()*CHUNK_SIZE, minZ=snap.cz()*CHUNK_SIZE;
        int maxX=minX+CHUNK_SIZE-1, maxZ=minZ+CHUNK_SIZE-1;
        // Include entire large tree crowns across chunk boundaries.
        int tc=WorldGenerator.TREE_CELL_SIZE;
        for(int tx=Math.floorDiv(minX-9,tc);tx<=Math.floorDiv(maxX+9,tc);tx++)
            for(int tz=Math.floorDiv(minZ-9,tc);tz<=Math.floorDiv(maxZ+9,tc);tz++) {
                WorldGenerator.Tree t=snap.generator().treeForCell(tx,tz);
                if(t==null)continue;
                int tip=snap.generator().surface(t.x(),t.z()).surfaceY()+1+t.trunkHeight();
                int layer=leafLayer(t.type());
                if(t.type()==WorldGenerator.TreeType.OAK || t.type()==WorldGenerator.TreeType.MAPLE
                        || t.type()==WorldGenerator.TreeType.WILLOW) {
                    appendBranchCrown(snap,data,section,t,tip,layer,minX,maxX,minZ,maxZ);
                    continue;
                }
                // Shape-specific 3D crowns, not a single shared leaf sphere.
                // Larger crowns are generated only into their own visible section.
                int height=t.trunkHeight();
                int halfWidth=switch(t.type()) {
                    case OAK -> 5 + (height>=14?1:0);
                    case MAPLE -> 5 + (height>=14?1:0);
                    case PINE -> height>=19?4:3;
                    case WILLOW -> 7;
                    case CYPRESS -> height>=19?4:3;
                };
                int crownDepth=switch(t.type()) {
                    case OAK,MAPLE -> Math.max(5,height*3/5);
                    case PINE -> Math.max(7,height*2/3);
                    case WILLOW -> 5;
                    case CYPRESS -> Math.max(9,height*3/4);
                };
                for(int dy=-crownDepth;dy<=2;dy++) {
                    // Evergreens terminate at the trunk top: no floating tufts above it.
                    if ((t.type()==WorldGenerator.TreeType.PINE || t.type()==WorldGenerator.TreeType.CYPRESS) && dy>0) continue;
                    int y=tip+dy;
                    if(y<1||y>=WORLD_HEIGHT||y/SECTION_HEIGHT!=section)continue;
                    for(int dx=-halfWidth;dx<=halfWidth;dx++)
                        for(int dz=-halfWidth;dz<=halfWidth;dz++) {
                            int x=t.x()+dx,z=t.z()+dz;
                            if(x<minX||x>maxX||z<minZ||z>maxZ)continue;
                            double radial=Math.sqrt(dx*dx+dz*dz);
                            double limit;
                            double fill;
                            switch(t.type()) {
                                case OAK -> {
                                    limit=halfWidth * Math.sqrt(Math.max(0,1.0-Math.pow((dy+crownDepth*.42)/(crownDepth*.70),2)));
                                    fill=.69;
                                }
                                case MAPLE -> {
                                    limit=halfWidth * Math.sqrt(Math.max(0,1.0-Math.pow((dy+crownDepth*.48)/(crownDepth*.66),2)));
                                    fill=.83;
                                }
                                case PINE -> {
                                    int fromTop=-dy;
                                    // Cohesive tapered whorls: the central crown is continuous;
                                    // only the outer fringe receives sparse breakup.
                                    double taper=Math.min(1.0,(fromTop+2.0)/(crownDepth+2.0));
                                    limit=Math.max(1.25,halfWidth*taper)
                                            * ((fromTop%4==0 && fromTop>3)?0.79:1.0);
                                    fill=.82;
                                }
                                case CYPRESS -> {
                                    int fromTop=-dy;
                                    limit=Math.max(1.1,halfWidth*Math.min(1.0,(fromTop+2.0)/(crownDepth+2.0)))
                                            * ((fromTop%3==0 && fromTop>3)?0.83:1.0);
                                    fill=.82;
                                }
                                case WILLOW -> {
                                    limit=halfWidth * Math.sqrt(Math.max(0,1.0-Math.pow((dy+1.0)/4.0,2)));
                                    // Hanging curtains, spaced apart rather than a solid ring.
                                    if(dy < -2) limit=halfWidth * 0.83;
                                    fill=dy < -2 ? .20 : .58;
                                }
                                default -> throw new IllegalStateException();
                            }
                            if(radial>limit+0.25 || (dx==0&&dz==0&&dy<=0))continue;
                            long h=(((long)x*0x9E3779B97F4A7C15L)^((long)z*0xC2B2AE3D27D4EB4FL)
                                    ^((long)y*0x165667B19E3779F9L)^((long)t.x()*0xD6E8FEB86659FD93L));
                            h^=h>>>33;h*=0xff51afd7ed558ccdL;h^=h>>>33;
                            double chance=(h>>>11)*0x1.0p-53;
                            // A solid inner core joins every evergreen foliage layer;
                            // irregularity belongs primarily at the crown perimeter.
                            boolean evergreen=t.type()==WorldGenerator.TreeType.PINE
                                    || t.type()==WorldGenerator.TreeType.CYPRESS;
                            if(chance>=fill && (!evergreen || radial>Math.max(1.25,limit*.57)))continue;
                            if(snapshotBlockAt(snap,x,y,z)!=BlockType.AIR)continue;
                            leafCube(data,x,y,z,layer);
                        }
                }
            }
        int pc=WorldGenerator.PLANT_CELL_SIZE;
        for(int px=Math.floorDiv(minX,pc);px<=Math.floorDiv(maxX,pc);px++)
            for(int pz=Math.floorDiv(minZ,pc);pz<=Math.floorDiv(maxZ,pc);pz++) {
                WorldGenerator.Plant p=snap.generator().plantForCell(px,pz);
                if(p==null||p.x()<minX||p.x()>maxX||p.z()<minZ||p.z()>maxZ)continue;
                int layer=plantLayer(p.species());
                if(layer<0)continue;
                int y=snap.generator().surface(p.x(),p.z()).surfaceY()+1;
                if(y<1||y>=WORLD_HEIGHT-2 || y/SECTION_HEIGHT!=section || snapshotBlockAt(snap,p.x(),y,p.z())!=BlockType.AIR)continue;
                // Never render floating plants when players have removed the ground.
                if(snapshotBlockAt(snap,p.x(),y-1,p.z())==BlockType.AIR)continue;
                crossPlant(data,p.x(),y,p.z(),layer,p.tall()?1.0f:0.78f);
            }
    }
    /** Leaf clusters follow branch endpoints rather than a single floating sphere. */
    private void appendBranchCrown(MeshSnapshot snap,FloatArrayBuilder data,int section,
            WorldGenerator.Tree t,int trunkTip,int layer,int minX,int maxX,int minZ,int maxZ) {
        int total=branchCount(t);
        for(int b=-1;b<total;b++) {
            int[] offset=b<0?new int[]{0,-1,0}:branchTip(t,b);
            int cx=t.x()+offset[0],cy=trunkTip+offset[1],cz=t.z()+offset[2];
            int radius=(t.type()==WorldGenerator.TreeType.WILLOW)?3:((b<0)?3:2+(t.trunkHeight()>13?1:0));
            for(int dx=-radius;dx<=radius;dx++)for(int dz=-radius;dz<=radius;dz++)
                for(int dy=-radius;dy<=radius;dy++) {
                    int x=cx+dx,y=cy+dy,z=cz+dz;
                    if(x<minX||x>maxX||z<minZ||z>maxZ||y<1||y>=WORLD_HEIGHT
                            ||y/SECTION_HEIGHT!=section)continue;
                    double shape=(dx*dx+dz*dz)/(double)(radius*radius)
                            +(dy*dy)/(double)Math.max(1,(radius-1)*(radius-1));
                    if(shape>1.20)continue;
                    long hash=((long)x*0x9E3779B97F4A7C15L)^((long)y*0xC2B2AE3D27D4EB4FL)
                            ^((long)z*0x165667B19E3779F9L)^((long)t.x()*0xD6E8FEB86659FD93L);
                    hash^=hash>>>33;hash*=0xff51afd7ed558ccdL;hash^=hash>>>33;
                    double rnd=(hash>>>11)*0x1.0p-53;
                    double density=t.type()==WorldGenerator.TreeType.MAPLE?.87:.76;
                    if(rnd>density && shape>.48)continue;
                    if(snapshotBlockAt(snap,x,y,z)!=BlockType.AIR)continue;
                    leafCube(data,x,y,z,layer);
                }
        }
        if(t.type()==WorldGenerator.TreeType.WILLOW) {
            // Sparse hanging curtains anchored to the outer branches.
            for(int b=0;b<total;b++) {
                int[] off=branchTip(t,b);
                int x=t.x()+off[0],z=t.z()+off[2],top=trunkTip+off[1]-2;
                for(int d=0;d<3+(b%3);d++) {
                    int y=top-d;
                    if(x>=minX&&x<=maxX&&z>=minZ&&z<=maxZ&&y>0&&y/SECTION_HEIGHT==section
                            &&snapshotBlockAt(snap,x,y,z)==BlockType.AIR)leafCube(data,x,y,z,layer);
                }
            }
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
        if (startingBlock != BlockType.AIR && !startingBlock.isFluid()) {
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
            if (block != BlockType.AIR && !block.isFluid()) {
                return new RayHit(
                        x, y, z,
                        previousX, previousY, previousZ,
                        block
                );
            }
        }
    }

}
