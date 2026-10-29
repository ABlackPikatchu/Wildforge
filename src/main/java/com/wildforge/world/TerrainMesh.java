package com.wildforge.world;

import com.wildforge.worldgen.Biome;
import com.wildforge.worldgen.SpawnFinder;
import com.wildforge.world.mesh.FloatArrayBuilder;
import com.wildforge.world.fluid.FluidRules;

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
    private static final int MAGIC=0x57464F52, SAVE_VERSION=5;
    private static final long DEFAULT_SEED=0x57494C44464F5247L;
    /** Generator revision is independent of the binary save format. Never change an
     * old revision's semantics silently; reject incompatible future saves. */
    public static final int WORLDGEN_REVISION=6;
    private int worldgenRevision=WORLDGEN_REVISION;
    public int worldgenRevision(){return worldgenRevision;}
    private final LinkedHashMap<Long,ChunkColumn> chunks = new LinkedHashMap<>();
    private final HashMap<Long,HashMap<Integer,Byte>> edits = new HashMap<>();
    private WorldGenerator generator;
    private SpawnFinder.Spawn initialSpawn;
    private BiomeDistribution distribution;
    private SeededNoise depositNoise;
    private long seed=DEFAULT_SEED;
    // Active-only fluid scheduler: oceans are inert until a nearby cell changes.
    // Flow strength is bounded; source vs flowing fluid is stored as a block ID.
    private record FluidTask(int x,int y,int z,long readyTick) {}
    private final PriorityQueue<FluidTask> fluidQueue = new PriorityQueue<>(
        Comparator.comparingLong(FluidTask::readyTick)
             .thenComparingInt(FluidTask::x).thenComparingInt(FluidTask::y).thenComparingInt(FluidTask::z));
    private long fluidTick = 0;
    private float fluidAccumulator = 0f;
    private static final float FLUID_TICK_SECONDS = FluidRules.TICK_SECONDS;
    private record FluidPosition(int x,int y,int z) {}
    private final HashSet<FluidPosition> queuedFluidPositions = new HashSet<>();
    // Edits outside simulation distance are parked rather than consuming fluid ticks.
    // This is transient scheduling state; the existing v4 edits/levels remain authoritative.
    private final HashMap<Long,LinkedHashSet<FluidPosition>> sleepingFluid = new HashMap<>();
    private int simulationCenterX=1,simulationCenterZ=1;
    private static final int SLEEPING_FLUID_LIMIT_PER_COLUMN=2048;
    // Sparse, persistent levels for FLOWING_* cells only. Source blocks are always level 7.
    // Indexing matches edits (chunk key + column-major block index), so unloaded fluids retain state.
    private final HashMap<Long,HashMap<Integer,Byte>> fluidLevels = new HashMap<>();
    private static final int FLUID_QUEUE_LIMIT = 8192;
    // Generated waterfall blocks predate level metadata. Keep their existing
    // near-full visual height instead of collapsing them to tiny puddles.
    private static final byte LEGACY_FLOW_LEVEL = 6;

    private boolean legacyVerticalMigration=false;
    public boolean needsLegacyPlayerHeightShift(){return legacyVerticalMigration;}
    private int version=0, renderDistance=8, simulationDistance=5;
    // Worker builds raw chunk data only. OpenGL-facing meshes remain on the main thread.
    private ExecutorService generationWorker = newGenerationWorker();
    private final HashMap<Long, Future<ChunkColumn>> pending = new HashMap<>();
    // Mesh jobs never read live chunk maps or mutate meshes; only the game thread installs results.
    private ExecutorService meshWorker = newMeshWorker();
    // Each pending column has a bit set for only the vertical sections needing remesh.
    private final LinkedHashMap<Long,BitSet> dirtyMeshes = new LinkedHashMap<>();
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
    private record MeshJob(Future<float[][]> future, BitSet sections, int epoch, int generation) {}
    private record MeshSnapshot(int cx, int cz, byte[] blocks, byte[] west, byte[] east, byte[] north, byte[] south,
                                Map<Integer,Byte> flows, Map<Integer,Byte> westFlows, Map<Integer,Byte> eastFlows,
                                Map<Integer,Byte> northFlows, Map<Integer,Byte> southFlows, WorldGenerator generator) {}
    private static ExecutorService newGenerationWorker() {
        return Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "Wildforge-Chunk-Generation");
            t.setDaemon(true);
            return t;
        });
    }
    private void cancelGeneration() {
        fluidQueue.clear();queuedFluidPositions.clear();sleepingFluid.clear();fluidAccumulator=0f;fluidTick=0;
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
        ChunkColumn c = generate(cx, cz);
        applySavedEdits(c);
        chunks.put(key(cx, cz), c);
        resumeEditedFlows(c);
        activateGeneratedRiverFalls(c);
        rebuild(c);
        c.compact();
        version++;
    }
    private void applySavedEdits(ChunkColumn c) {
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
    private static long key(int x,int z){return ChunkCoordinates.pack(x,z);}
    private static int index(int x,int y,int z){return ChunkCoordinates.localIndex(x,y,z);}
    private void initializeGenerator(){
        try { generator=new WorldGenerator(seed,WorldGenConfigLoader.loadBuiltIn()); distribution=new BiomeDistribution(seed); depositNoise=new SeededNoise(seed); }
        catch(IOException e){ throw new IllegalStateException("Worldgen profile unavailable; run generateContent",e); }
    }
    public TerrainMesh(){initializeGenerator();generateSpawnChunk(16,27);}
    public void setDistances(int render,int simulation){
        renderDistance=Math.max(2,Math.min(24,render));
        int next=Math.max(2,Math.min(renderDistance,simulation));
        if(next!=simulationDistance) {
            simulationDistance=next;
            reclassifyFluidTasks();
            wakeSleepingFluids();
        }
    }
    public int renderDistance(){return renderDistance;}
    public int simulationDistance(){return simulationDistance;}
    public int loadedChunkCount(){return chunks.size();}
    /** Approximate encoded block payload, excluding object overhead and CPU meshes. */
    public long residentBlockPayloadBytes(){long bytes=0;for(ChunkColumn c:chunks.values())bytes+=c.compact==null?CHUNK_SIZE*CHUNK_SIZE*WORLD_HEIGHT:c.compact.storedBytes();return bytes;}
    /** Count of outstanding terrain generation jobs; queried on the game thread. */
    public int pendingGenerationCount(){return pending.size();}
    /** True only when a terrain column is actually resident in memory. */
    public boolean isColumnLoaded(int worldX,int worldZ){
        return chunks.containsKey(key(Math.floorDiv(worldX,CHUNK_SIZE),Math.floorDiv(worldZ,CHUNK_SIZE)));
    }

    public String biomeNameAt(int worldX,int worldZ) {
        return generator.surface(worldX,worldZ).biome().name().replace('_',' ');
    }
    public int activeFluidUpdates(){return fluidQueue.size();}
    public int sleepingFluidUpdates(){int count=0;for(var positions:sleepingFluid.values())count+=positions.size();return count;}
    /** Square simulation radius in chunk units, including negative chunk coordinates. */
    static boolean withinSimulation(int playerCX,int playerCZ,int distance,int worldX,int worldZ) {
        return Math.abs(Math.floorDiv(worldX,CHUNK_SIZE)-playerCX)<=distance &&
               Math.abs(Math.floorDiv(worldZ,CHUNK_SIZE)-playerCZ)<=distance;
    }
    private boolean withinSimulation(int worldX,int worldZ) {
        return withinSimulation(simulationCenterX,simulationCenterZ,simulationDistance,worldX,worldZ);
    }
    /** Entity physics uses the same center/radius as fluid updates. */
    public boolean isWithinSimulationDistance(float worldX,float worldZ) {
        if(!Float.isFinite(worldX)||!Float.isFinite(worldZ))return false;
        int x=(int)Math.floor(worldX),z=(int)Math.floor(worldZ);
        return withinSimulation(x,z) && isColumnLoaded(x,z);
    }
    public long seed(){return seed;}
    /** Chosen at new-world creation; null for loaded legacy worlds. */
    public SpawnFinder.Spawn initialSpawn(){return initialSpawn;}
    public void resetWorld(){initialSpawn=null;legacyVerticalMigration=false;cancelGeneration();edits.clear();fluidLevels.clear();seed=DEFAULT_SEED;worldgenRevision=WORLDGEN_REVISION;initializeGenerator();chunks.clear();generateSpawnChunk(16,27);version++;}
    /** Nonblocking player-centered streaming: one worker produces raw columns, main thread owns meshes. */
    public void streamAround(float x,float z){
        int cx=Math.floorDiv((int)Math.floor(x),CHUNK_SIZE);
        int cz=Math.floorDiv((int)Math.floor(z),CHUNK_SIZE);
        if(cx!=simulationCenterX || cz!=simulationCenterZ) {
            simulationCenterX=cx;simulationCenterZ=cz;
            reclassifyFluidTasks();
            wakeSleepingFluids();
        }
        boolean changed=false;
        Iterator<Map.Entry<Long,ChunkColumn>> it=chunks.entrySet().iterator();
        while(it.hasNext()){
            ChunkColumn c=it.next().getValue();
            if(Math.abs(c.cx-cx)>renderDistance || Math.abs(c.cz-cz)>renderDistance){
                it.remove();changed=true;
                long removed=key(c.cx,c.cz);
                dirtyMeshes.remove(removed);
                LodJob lodJob=lodJobs.remove(removed);
                if(lodJob!=null)lodJob.future().cancel(true);
                MeshJob old=meshJobs.remove(removed);
                if(old!=null)old.future().cancel(true);
                meshEpochs.remove(removed);
                // Unloaded chunks have no active simulation. Flowing edits will be
                // resumed on reload, and seam neighbors are checked on installation.
                sleepingFluid.remove(removed);
                discardFluidTasks(removed);
            }
        }
        // Discard out-of-range requests. Cancellation is best-effort; late results are ignored.
        Iterator<Map.Entry<Long, Future<ChunkColumn>>> requests=pending.entrySet().iterator();
        while(requests.hasNext()){
            var entry=requests.next();
            int px=(int)(entry.getKey()>>32),pz=(int)(long)entry.getKey();
            if(Math.abs(px-cx)>renderDistance || Math.abs(pz-cz)>renderDistance){
                entry.getValue().cancel(true);requests.remove();
            }
        }
        // Install at most one completed chunk per frame, bounding expensive main-thread mesh work.
        Iterator<Map.Entry<Long,Future<ChunkColumn>>> finished=pending.entrySet().iterator();
        while(finished.hasNext()){
            var entry=finished.next();
            Future<ChunkColumn> future=entry.getValue();
            if(!future.isDone())continue;
            finished.remove();
            try {
                ChunkColumn c=future.get();
                if(chunks.containsKey(entry.getKey()))continue;
                applySavedEdits(c);
                c.compact();
                chunks.put(entry.getKey(),c);
                resumeEditedFlows(c);
                activateGeneratedRiverFalls(c);
                activateFluidSeams(c);
                wakeSleepingFluids();
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
        // Install at most one completed mesh result per frame. The result may contain
        // a full column (first load) or just the sections dirtied by block edits.
        int installed = 0;
        var meshIter = meshJobs.entrySet().iterator();
        while (meshIter.hasNext() && installed < 1) {
            var entry = meshIter.next();
            MeshJob job = entry.getValue();
            if (!job.future().isDone()) continue;
            meshIter.remove();
            try {
                float[][] built = job.future().get();
                ChunkColumn target = chunks.get(entry.getKey());
                if (target == null || job.generation() != meshGeneration) continue;
                if (job.epoch() != meshEpochs.getOrDefault(entry.getKey(), 0)) {
                    // A late result must never overwrite newer edits.
                    dirtyMeshes.computeIfAbsent(entry.getKey(), k -> new BitSet(SECTION_COUNT))
                        .or(job.sections());
                    continue;
                }
                for (int section=job.sections().nextSetBit(0); section>=0;
                     section=job.sections().nextSetBit(section+1)) {
                    target.sectionVertices[section] = built[section];
                    target.fluidVertices[section] = built[section+SECTION_COUNT];
                    target.sectionRevision[section]++;
                    target.fluidRevision[section]++;
                }
                changed = true;
                installed++;
            } catch (CancellationException ignored) {
                // Cancellation from an edit already requeued the sections.
            } catch (ExecutionException e) {
                throw new IllegalStateException("Asynchronous section meshing failed", e.getCause());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        // Snapshot once per scheduled column. Selected sections are meshed on
        // worker threads; untouched GPU sections keep their current revisions.
        while (meshJobs.size() < 2 && !dirtyMeshes.isEmpty()) {
            long bestKey = 0;
            long bestDistance = Long.MAX_VALUE;
            for (long candidate : dirtyMeshes.keySet()) {
                int mx=(int)(candidate>>32),mz=(int)candidate;
                long d=(long)(mx-cx)*(mx-cx)+(long)(mz-cz)*(mz-cz);
                if (d < bestDistance) {bestDistance=d;bestKey=candidate;}
            }
            BitSet selected = dirtyMeshes.remove(bestKey);
            ChunkColumn target = chunks.get(bestKey);
            if (target == null || selected==null || selected.isEmpty() || meshJobs.containsKey(bestKey)) continue;
            MeshSnapshot snapshot = snapshot(target);
            int epoch=meshEpochs.getOrDefault(bestKey,0);
            int generation=meshGeneration;
            // Capture a private bit set; edits can requeue this column while the worker runs.
            BitSet immutable=(BitSet)selected.clone();
            meshJobs.put(bestKey,new MeshJob(
                meshWorker.submit(() -> buildSelectedMeshes(snapshot, immutable)),immutable,epoch,generation));
        }
        if(changed)version++;
    }
    /** A block can affect faces in a second vertical section only at a section boundary. */
    static BitSet affectedSections(int y) {
        if(y<0 || y>=WORLD_HEIGHT)throw new IllegalArgumentException("Invalid block height: " + y);
        BitSet result=new BitSet(SECTION_COUNT);
        int section=y/SECTION_HEIGHT;
        result.set(section);
        if(y%SECTION_HEIGHT==0 && section>0)result.set(section-1);
        if(y%SECTION_HEIGHT==SECTION_HEIGHT-1 && section<SECTION_COUNT-1)result.set(section+1);
        return result;
    }
    private void markMeshDirty(int x,int z) {
        BitSet all=new BitSet(SECTION_COUNT);
        all.set(0,SECTION_COUNT);
        markMeshDirty(x,z,all);
    }
    private void markMeshDirty(int x,int z,int y) {
        markMeshDirty(x,z,affectedSections(y));
    }
    private void markMeshDirty(int x,int z,BitSet sections) {
        long k=key(x,z);
        if (!chunks.containsKey(k) || sections.isEmpty()) return;
        meshEpochs.put(k,meshEpochs.getOrDefault(k,0)+1);
        MeshJob previous=meshJobs.remove(k);
        BitSet requested=dirtyMeshes.computeIfAbsent(k,ignore -> new BitSet(SECTION_COUNT));
        requested.or(sections);
        if(previous!=null) {
            // Do not lose sections already removed from the pending queue for this job.
            requested.or(previous.sections());
            previous.future().cancel(true);
        }
        // Distant LOD is a per-column representation. A changed section still
        // invalidates its LOD, but not the other 31 full-detail section meshes.
        ChunkColumn lodChunk=chunks.get(k);
        LodJob lodJob=lodJobs.remove(k);
        if(lodJob!=null)lodJob.future().cancel(true);
        if(lodChunk!=null){lodChunk.lodVertices=null;lodChunk.lodFluidVertices=null;lodChunk.lodRevision++;}
    }
    private MeshSnapshot snapshot(ChunkColumn c) {
        ChunkColumn w=chunks.get(key(c.cx-1,c.cz)), e=chunks.get(key(c.cx+1,c.cz));
        ChunkColumn n=chunks.get(key(c.cx,c.cz-1)), so=chunks.get(key(c.cx,c.cz+1));
        return new MeshSnapshot(c.cx,c.cz,c.copyBlocks(),w==null?null:w.copyBlocks(),
            e==null?null:e.copyBlocks(),n==null?null:n.copyBlocks(),so==null?null:so.copyBlocks(),
            flowSnapshot(c.cx,c.cz),flowSnapshot(c.cx-1,c.cz),flowSnapshot(c.cx+1,c.cz),
            flowSnapshot(c.cx,c.cz-1),flowSnapshot(c.cx,c.cz+1),generator);
    }
    private Map<Integer,Byte> flowSnapshot(int cx,int cz) {
        Map<Integer,Byte> values=fluidLevels.get(key(cx,cz));
        return values==null?Map.of():Map.copyOf(values);
    }
    private static int snapshotFluidLevel(byte type,Map<Integer,Byte> levels,int index) {
        if(type==BlockType.WATER.id||type==BlockType.LAVA.id)return 7;
        if(type==BlockType.FLOWING_WATER.id||type==BlockType.FLOWING_LAVA.id)
            return Byte.toUnsignedInt(levels.getOrDefault(index,LEGACY_FLOW_LEVEL));
        return 0;
    }
    private float[][] buildSelectedMeshes(MeshSnapshot snap,BitSet sections) {
        float[][] built=new float[SECTION_COUNT*2][];
        for(int i=sections.nextSetBit(0);i>=0;i=sections.nextSetBit(i+1)) {
            if(Thread.currentThread().isInterrupted())throw new CancellationException();
            float[][] layers=buildSectionLayers(snap,i);
            built[i]=layers[0];
            built[i+SECTION_COUNT]=layers[1];
        }
        return built;
    }
    /** Pending sections count includes queued and in-flight work. */
    public int pendingMeshSectionCount() {
        int count=0;
        for(BitSet sections:dirtyMeshes.values())count+=sections.cardinality();
        for(MeshJob job:meshJobs.values())count+=job.sections().cardinality();
        return count;
    }
    public int pendingMeshCount() {return meshJobs.size()+dirtyMeshes.size();}
    public int pendingLodCount() {return lodJobs.size();}
    public int readyLodCount() {int n=0;for(ChunkColumn c:chunks.values())if(c.lodVertices!=null)n++;return n;}
    private ChunkColumn generate(int cx,int cz){
        ChunkColumn c=new ChunkColumn(cx,cz);
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
                        case PINE, SPRUCE -> BlockType.PINE_NEEDLE_LITTER;
                        case MAPLE -> surface.biome()==Biome.EMBERLEAF_WOODLAND
                                ? BlockType.AUTUMN_LEAF_LITTER : BlockType.LEAF_LITTER;
                        case OAK, BIRCH -> BlockType.LEAF_LITTER;
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
            if(submerged) filler=(surface.waterDepth()>34 ? BlockType.OCEAN_DARK_SILT :
                    surface.rainfall()>0.64 ? BlockType.CLAY : BlockType.SAND);
            BlockType baseRock=material(surface.baseRockTexture(), BlockType.STONE);
            // Surface geology is a shallow regional accent, not the entire 512-block column.
            BlockType geologicalRock=distribution.rock(surface.biome(),wx,wz,baseRock);
            int soilDepth=distribution.soilDepth(surface.biome(),wx,wz);
            // Shallow, exposed ridges lose soil; sheltered and wet valleys build it up.
            if(slope>=7) soilDepth=Math.min(soilDepth,1);
            else if(slope>=4) soilDepth=Math.min(soilDepth,2);
            else if(surface.waterBody()!=WorldGenerator.WaterBody.LAND)soilDepth=Math.max(3,Math.min(6,soilDepth));
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
        populateCaveDecorations(c);
        populateSnowCover(c);
        // Edits are applied when the completed chunk is installed on the main thread.
        return c;
    }
    /** Deterministic 1..8-height snow cover, placed only on exposed cold ground.
     * Layer state is encoded in append-only numeric block IDs for existing saves.
     */
    private void populateSnowCover(ChunkColumn c) {
        for(int lx=0;lx<CHUNK_SIZE;lx++)for(int lz=0;lz<CHUNK_SIZE;lz++) {
            int wx=c.cx*CHUNK_SIZE+lx,wz=c.cz*CHUNK_SIZE+lz;
            WorldGenerator.Surface s=generator.surface(wx,wz);
            if(s.waterBody()!=WorldGenerator.WaterBody.LAND)continue;
            int ground=s.surfaceY(),y=ground+1;
            if(y>=WORLD_HEIGHT-1 || y<2 || c.get(index(lx,y,lz))!=BlockType.AIR.id)continue;
            BlockType support=BlockType.fromId(c.get(index(lx,ground,lz)));
            if(!support.isSolid() || support.isSnowLayer() || support.isFluid())continue;
            boolean snowy=s.biome()==Biome.SNOWMANTLE_WOODS ||
                    s.biome()==Biome.FROSTMOOR_TUNDRA ||
                    s.biome()==Biome.FROSTSPIRE_HIGHLANDS ||
                    s.temperature()<0.23;
            if(!snowy || generator.slopeAt(wx,wz)>5)continue;
            double drifts=depositNoise.fbm2(wx/19.0,wz/19.0,3,0x534e4f575f34L);
            double small=depositNoise.fbm2(wx/6.0,wz/6.0,2,0x4452494654L);
            int layers=Math.max(1,Math.min(8,(int)Math.round(3.4+drifts*3.0+small*1.2)));
            c.set(index(lx,y,lz),BlockType.snowLayer(layers).id);
        }
    }

    /** Deterministic static water: submerged ocean/river terrain and rare cave pools.
     * This runs on the generation worker and never enqueues millions of ocean cells.
     */
    private void placeNaturalFluids(ChunkColumn c) {
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
                // Hydrology's incised stream bed already defines the water column.
                // Never inject an upstream-height water pillar over a downstream bank;
                // active fluid simulation handles free waterfalls at actual openings.
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

    /** Source blocks contain full fluid; flow levels are compact, persistent 1..7 values. */
    public int getFluidLevelAt(int x,int y,int z) {
        if(y<0||y>=WORLD_HEIGHT)return 0;
        int cx=Math.floorDiv(x,CHUNK_SIZE),cz=Math.floorDiv(z,CHUNK_SIZE);
        ChunkColumn c=chunks.get(key(cx,cz));
        if(c==null)return 0;
        int i=index(Math.floorMod(x,CHUNK_SIZE),y,Math.floorMod(z,CHUNK_SIZE));
        BlockType type=BlockType.fromId(c.get(i));
        if(!type.isFluid())return 0;
        if(type==BlockType.WATER||type==BlockType.LAVA)return 7;
        return savedFlowLevel(key(cx,cz),i);
    }
    private int savedFlowLevel(long chunkKey,int index) {
        Map<Integer,Byte> levels=fluidLevels.get(chunkKey);
        return levels==null?LEGACY_FLOW_LEVEL:Byte.toUnsignedInt(levels.getOrDefault(index,LEGACY_FLOW_LEVEL));
    }
    private void storeFlowLevel(long chunkKey,int index,BlockType type,int level) {
        if(type!=BlockType.FLOWING_WATER&&type!=BlockType.FLOWING_LAVA) {
            HashMap<Integer,Byte> values=fluidLevels.get(chunkKey);
            if(values!=null){values.remove(index);if(values.isEmpty())fluidLevels.remove(chunkKey);}
        } else {
            fluidLevels.computeIfAbsent(chunkKey,k->new HashMap<>()).put(index,(byte)Math.max(1,Math.min(7,level)));
        }
    }
    /** Set a flowing block's level; useful for simulation tools and save regression tests. */
    public boolean setFluidLevelAt(int x,int y,int z,int level) {
        if(y<1||y>=WORLD_HEIGHT-1||level<1||level>7)return false;
        int cx=Math.floorDiv(x,CHUNK_SIZE),cz=Math.floorDiv(z,CHUNK_SIZE);
        ChunkColumn c=chunks.get(key(cx,cz));if(c==null)return false;
        int i=index(Math.floorMod(x,CHUNK_SIZE),y,Math.floorMod(z,CHUNK_SIZE));
        BlockType b=BlockType.fromId(c.get(i));
        if(b!=BlockType.FLOWING_WATER&&b!=BlockType.FLOWING_LAVA)return false;
        if(getFluidLevelAt(x,y,z)==level)return false;
        storeFlowLevel(key(cx,cz),i,b,level);
        edits.computeIfAbsent(key(cx,cz),k->new HashMap<>()).put(i,b.id);
        scheduleFluid(x,y,z);markFluidMeshDirty(x,y,z);version++;
        return true;
    }
    /** Fractional fluid surface in world Y (or -infinity if dry). */
    public float fluidSurfaceAt(int x,int y,int z) {
        BlockType b=getBlockAt(x,y,z);
        if(!b.isFluid())return Float.NEGATIVE_INFINITY;
        // A fluid with another fluid immediately above it occupies its entire cell.
        return y+(getBlockAt(x,y+1,z).isFluid()?1f:fluidSurfaceHeight(getFluidLevelAt(x,y,z)));
    }
    private static float fluidSurfaceHeight(int level) {
        return FluidRules.surfaceHeight(Math.max(1,Math.min(7,level)));
    }
    /** Activate a few generated waterfall cells when a column becomes resident.
     * Oceans and still lakes stay inert; do not seed fluid ticks for every source block.
     * This also handles streams present in a freshly created world.
     */
    private void activateGeneratedRiverFalls(ChunkColumn c) {
        int scheduled = 0;
        // Sample a coarse grid to avoid 256 expensive noise lookups per loaded chunk.
        for (int lx=0;lx<CHUNK_SIZE && scheduled<24;lx+=2)
            for (int lz=0;lz<CHUNK_SIZE && scheduled<24;lz+=2) {
                int wx=c.cx*CHUNK_SIZE+lx,wz=c.cz*CHUNK_SIZE+lz;
                WorldGenerator.Surface surface=generator.surface(wx,wz);
                if (surface.waterBody()!=WorldGenerator.WaterBody.RIVER) continue;
                // Only wake exposed flow drops, not entire static water columns.
                for(int y=Math.min(WORLD_HEIGHT-2,surface.waterSurfaceY()+12);
                    y>=Math.max(1,surface.waterSurfaceY()-2) && scheduled<24;y--) {
                    byte waterId = c.get(index(lx,y,lz));
                    if (waterId!=BlockType.FLOWING_WATER.id &&
                        !(waterId==BlockType.WATER.id && y>=surface.waterSurfaceY()-1))continue;
                    if (getBlockAt(wx,y-1,wz)!=BlockType.AIR &&
                        getBlockAt(wx+1,y,wz)!=BlockType.AIR &&
                        getBlockAt(wx-1,y,wz)!=BlockType.AIR &&
                        getBlockAt(wx,y,wz+1)!=BlockType.AIR &&
                        getBlockAt(wx,y,wz-1)!=BlockType.AIR) continue;
                    scheduleFluid(wx,y,wz);
                    scheduled++;
                }
            }
    }
    private void resumeEditedFlows(ChunkColumn c) {
        // Only edited flowing cells resume; naturally generated oceans and pools stay inert.
        Map<Integer,Byte> changed=edits.get(key(c.cx,c.cz));
        if(changed==null)return;
        for(var change:changed.entrySet()) {
            byte type=change.getValue();
            if(type!=BlockType.FLOWING_WATER.id&&type!=BlockType.FLOWING_LAVA.id)continue;
            int i=change.getKey(),y=i%WORLD_HEIGHT;
            int column=i/WORLD_HEIGHT,lx=column/CHUNK_SIZE,lz=column%CHUNK_SIZE;
            scheduleFluid(c.cx*CHUNK_SIZE+lx,y,c.cz*CHUNK_SIZE+lz);
        }
    }
    private void markFluidMeshDirty(int x,int y,int z) {
        int cx=Math.floorDiv(x,CHUNK_SIZE),cz=Math.floorDiv(z,CHUNK_SIZE);
        markMeshDirty(cx,cz,y);
        if(Math.floorMod(x,CHUNK_SIZE)==0)markMeshDirty(cx-1,cz,y);
        if(Math.floorMod(x,CHUNK_SIZE)==CHUNK_SIZE-1)markMeshDirty(cx+1,cz,y);
        if(Math.floorMod(z,CHUNK_SIZE)==0)markMeshDirty(cx,cz-1,y);
        if(Math.floorMod(z,CHUNK_SIZE)==CHUNK_SIZE-1)markMeshDirty(cx,cz+1,y);
    }
    private void parkFluid(FluidPosition pos) {
        long k=key(Math.floorDiv(pos.x(),CHUNK_SIZE),Math.floorDiv(pos.z(),CHUNK_SIZE));
        LinkedHashSet<FluidPosition> parked=sleepingFluid.computeIfAbsent(k,unused->new LinkedHashSet<>());
        if(parked.size()<SLEEPING_FLUID_LIMIT_PER_COLUMN) parked.add(pos);
    }
    private void reclassifyFluidTasks() {
        Iterator<FluidTask> it=fluidQueue.iterator();
        while(it.hasNext()) {
            FluidTask task=it.next();
            if(withinSimulation(task.x(),task.z()) && isColumnLoaded(task.x(),task.z()))continue;
            it.remove();
            FluidPosition pos=new FluidPosition(task.x(),task.y(),task.z());
            queuedFluidPositions.remove(pos);
            if(isColumnLoaded(task.x(),task.z()))parkFluid(pos);
        }
    }
    private void discardFluidTasks(long columnKey) {
        Iterator<FluidTask> it=fluidQueue.iterator();
        while(it.hasNext()){
            FluidTask task=it.next();
            if(key(Math.floorDiv(task.x(),CHUNK_SIZE),Math.floorDiv(task.z(),CHUNK_SIZE))!=columnKey)continue;
            it.remove();queuedFluidPositions.remove(new FluidPosition(task.x(),task.y(),task.z()));
        }
    }
    private void wakeSleepingFluids() {
        ArrayList<FluidPosition> wake=new ArrayList<>();
        Iterator<Map.Entry<Long,LinkedHashSet<FluidPosition>>> it=sleepingFluid.entrySet().iterator();
        while(it.hasNext()) {
            var entry=it.next();
            int cx=(int)(entry.getKey()>>32),cz=(int)(long)entry.getKey();
            if(Math.abs(cx-simulationCenterX)>simulationDistance ||
               Math.abs(cz-simulationCenterZ)>simulationDistance ||
               !chunks.containsKey(entry.getKey()))continue;
            wake.addAll(entry.getValue());it.remove();
        }
        for(FluidPosition pos:wake)scheduleFluid(pos.x(),pos.y(),pos.z());
    }
    private void scheduleFluid(int x,int y,int z) {
        if(y<1||y>=WORLD_HEIGHT-1)return;
        if(!isColumnLoaded(x,z))return;
        BlockType b=getBlockAt(x,y,z);
        if(!b.isFluid())return;
        FluidPosition pos=new FluidPosition(x,y,z);
        if(!withinSimulation(x,z)) {parkFluid(pos);return;}
        if(fluidQueue.size()>=FLUID_QUEUE_LIMIT){parkFluid(pos);return;}
        if(queuedFluidPositions.add(pos)) {
            int delay=FluidRules.delay(b);
            fluidQueue.add(new FluidTask(x,y,z,fluidTick+delay));
        }
    }
    /** When the adjacent column arrives, reopen fluid/air contacts on both
     * sides of the seam. Without this, an earlier failed spill is never retried.
     * Only one-block-wide border strips are examined, not entire ocean volumes.
     */
    private void activateFluidSeams(ChunkColumn loaded) {
        for(int[] dir:new int[][]{{1,0},{-1,0},{0,1},{0,-1}}) {
            ChunkColumn neighbor=chunks.get(key(loaded.cx+dir[0],loaded.cz+dir[1]));
            if(neighbor==null)continue;
            for(int i=0;i<CHUNK_SIZE;i++) {
                int ax=dir[0]>0?CHUNK_SIZE-1:dir[0]<0?0:i;
                int az=dir[1]>0?CHUNK_SIZE-1:dir[1]<0?0:i;
                int bx=dir[0]>0?0:dir[0]<0?CHUNK_SIZE-1:i;
                int bz=dir[1]>0?0:dir[1]<0?CHUNK_SIZE-1:i;
                int wx=loaded.cx*CHUNK_SIZE+ax,wz=loaded.cz*CHUNK_SIZE+az;
                int nx=neighbor.cx*CHUNK_SIZE+bx,nz=neighbor.cz*CHUNK_SIZE+bz;
                for(int y=1;y<WORLD_HEIGHT-1;y++) {
                    BlockType a=BlockType.fromId(loaded.get(index(ax,y,az)));
                    BlockType b=BlockType.fromId(neighbor.get(index(bx,y,bz)));
                    if(!a.isFluid() && !b.isFluid())continue;
                    int levelA=a.isFluid()&&b.isFluid()?getFluidLevelAt(wx,y,wz):0;
                    int levelB=a.isFluid()&&b.isFluid()?getFluidLevelAt(nx,y,nz):0;
                    if(shouldActivateFluidSeam(a,levelA,b,levelB))scheduleFluid(wx,y,wz);
                    if(shouldActivateFluidSeam(b,levelB,a,levelA))scheduleFluid(nx,y,nz);
                }
            }
        }
    }
    /** Only exposed, lower-level or incompatible-fluid contacts need waking. */
    static boolean shouldActivateFluidSeam(BlockType source,int sourceLevel,
                                           BlockType neighbor,int neighborLevel) {
        return FluidRules.activatesBorder(source,sourceLevel,neighbor,neighborLevel);
    }
    /** Fixed-rate simulation, independent of FPS and bounded after long frame stalls. */
    public void tickFluids(float elapsedSeconds) {
        if(!Float.isFinite(elapsedSeconds)||elapsedSeconds<=0f)return;
        fluidAccumulator+=Math.min(elapsedSeconds,0.20f);
        int ticks=0;
        while(fluidAccumulator>=FLUID_TICK_SECONDS && ticks++<4) {
            fluidAccumulator-=FLUID_TICK_SECONDS;
            fluidTick++;
            processFluidUpdates(32);
            if((fluidTick&15L)==0 && fluidQueue.size()<FLUID_QUEUE_LIMIT/2 && !sleepingFluid.isEmpty())
                wakeSleepingFluids();
        }
    }
    private void processFluidUpdates(int budget) {
        for(int processed=0;processed<budget&&!fluidQueue.isEmpty();processed++) {
            if(fluidQueue.peek().readyTick()>fluidTick)break;
            FluidTask task=fluidQueue.remove();
            queuedFluidPositions.remove(new FluidPosition(task.x,task.y,task.z));
            if(!isColumnLoaded(task.x,task.z()))continue;
            if(!withinSimulation(task.x,task.z())) {
                parkFluid(new FluidPosition(task.x,task.y,task.z));
                continue;
            }
            BlockType b=getBlockAt(task.x,task.y,task.z);
            if(!b.isFluid())continue;
            int strength=getFluidLevelAt(task.x,task.y,task.z);
            // Renewable water sources form only from two horizontal sources and
            // a supporting floor (solid block or water source); never in midair.
            if(b==BlockType.FLOWING_WATER && canFormWaterSource(task.x,task.y,task.z)) {
                if(setBlock(task.x,task.y,task.z,BlockType.WATER)) {
                    scheduleFluid(task.x,task.y,task.z);
                    continue;
                }
            }
            BlockType flow=b.isWater()?BlockType.FLOWING_WATER:BlockType.FLOWING_LAVA;
            // Downward flow takes precedence; allow horizontal spreading once supported.
            if(tryFluid(task.x,task.y-1,task.z,flow,strength))continue;
            if(strength<=1)continue;
            int next=strength-1;
            tryFluid(task.x+1,task.y,task.z,flow,next);
            tryFluid(task.x-1,task.y,task.z,flow,next);
            tryFluid(task.x,task.y,task.z+1,flow,next);
            tryFluid(task.x,task.y,task.z-1,flow,next);
        }
    }
    /** Two horizontally adjacent sources replenish a supported flowing cell.
     * Unlike lava, water is renewable; negative world coordinates work normally.
     */
    public boolean canFormWaterSource(int x,int y,int z) {
        if(getBlockAt(x,y,z)!=BlockType.FLOWING_WATER || y<1)return false;
        return supportedWaterSource(getBlockAt(x,y-1,z),
            getBlockAt(x+1,y,z),getBlockAt(x-1,y,z),
            getBlockAt(x,y,z+1),getBlockAt(x,y,z-1));
    }

    /** Pure rule used by fluid ticks and regression tests. */
    static boolean supportedWaterSource(BlockType below, BlockType east,
            BlockType west, BlockType south, BlockType north) {
        return FluidRules.formsWaterSource(below,east,west,south,north);
    }

    /** Currents follow falling water and higher-to-lower liquid levels.
     * Solid shore banks are impermeable, NOT high-pressure water sources.
     * Source oceans surrounded by equal-level water therefore stay calm.
     */
    public float[] waterCurrentAt(int x,int y,int z) {
        if (!getBlockAt(x,y,z).isWater()) return new float[]{0f,0f,0f};
        float[] vxz=new float[2];
        accumulateWaterFlow(x,y,z, 1,0,vxz);
        accumulateWaterFlow(x,y,z,-1,0,vxz);
        accumulateWaterFlow(x,y,z,0, 1,vxz);
        accumulateWaterFlow(x,y,z,0,-1,vxz);
        float length=(float)Math.hypot(vxz[0],vxz[1]);
        if(length>0.001f) {vxz[0]=vxz[0]/length*.75f;vxz[1]=vxz[1]/length*.75f;}
        return new float[]{vxz[0], getBlockAt(x,y-1,z)==BlockType.AIR?-.85f:0f,vxz[1]};
    }
    private void accumulateWaterFlow(int x,int y,int z,int dx,int dz,float[] result) {
        BlockType adjacent=getBlockAt(x+dx,y,z+dz);
        if (adjacent.isSolid() || !isColumnLoaded(x+dx,z+dz)) return;
        int own=getFluidLevelAt(x,y,z);
        float push=0f;
        if(adjacent.isWater()) {
            int next=getFluidLevelAt(x+dx,y,z+dz);
            push=Math.max(0,own-next);
        } else if(adjacent==BlockType.AIR &&
                (getBlockAt(x+dx,y-1,z+dz)==BlockType.AIR ||
                 getBlockAt(x+dx,y-1,z+dz).isWater())) {
            push=.65f; // Currents toward open downward outlets, not solid banks.
        }
        result[0]+=dx*push;
        result[1]+=dz*push;
    }
    private boolean tryFluid(int x,int y,int z,BlockType flow,int strength) {
        if(y<=0||y>=WORLD_HEIGHT-1||strength<1)return false;
        int cx=Math.floorDiv(x,CHUNK_SIZE),cz=Math.floorDiv(z,CHUNK_SIZE);
        long chunkKey=key(cx,cz);
        ChunkColumn c=chunks.get(chunkKey);if(c==null)return false;
        int pos=index(Math.floorMod(x,CHUNK_SIZE),y,Math.floorMod(z,CHUNK_SIZE));
        BlockType old=BlockType.fromId(c.get(pos));
        if(old.isFluid() && old.isWater()!=flow.isWater()) {
            // Source lava cools to obsidian; a flowing lava cell produces cobblestone.
            BlockType rock=(old==BlockType.LAVA||flow==BlockType.LAVA)?BlockType.OBSIDIAN:BlockType.COBBLESTONE;
            c.set(pos,rock.id);
            storeFlowLevel(chunkKey,pos,rock,0);
            edits.computeIfAbsent(chunkKey,k->new HashMap<>()).put(pos,rock.id);
            markFluidMeshDirty(x,y,z);version++;return true;
        }
        if(old.isFluid()) {
            if(old==BlockType.WATER||old==BlockType.LAVA)return false; // Never overwrite a source.
            if(getFluidLevelAt(x,y,z)>=strength)return false;
            // Refresh a weaker existing flow without changing its block ID.
            storeFlowLevel(chunkKey,pos,old,strength);
            edits.computeIfAbsent(chunkKey,k->new HashMap<>()).put(pos,old.id);
        } else if(old==BlockType.AIR) {
            c.set(pos,flow.id);
            edits.computeIfAbsent(chunkKey,k->new HashMap<>()).put(pos,flow.id);
            storeFlowLevel(chunkKey,pos,flow,strength);
        } else return false;
        scheduleFluid(x,y,z);
        markFluidMeshDirty(x,y,z);
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
    /** Marine palettes are local to the ocean type and only cover a thin exposed seabed. */
    private BlockType seabedAt(int x,int z,int y,int sea,int slope,WorldGenerator.Surface surface) {
        if(surface.waterBody()==WorldGenerator.WaterBody.RIVER)
            return slope>5?BlockType.WATERWORN_STONE:BlockType.RIVERBED_SEDIMENT;
        if(surface.waterBody()==WorldGenerator.WaterBody.LAKE)
            return slope>4?BlockType.GRAVEL:BlockType.CLAY;
        WorldGenerator.OceanBiome ocean=generator.oceanBiomeAt(x,z,surface);
        BlockType[] palette; int[] weights;
        switch(ocean) {
            case TEMPERATE -> {palette=new BlockType[]{BlockType.OCEAN_SILTY_SAND,BlockType.OCEAN_SHELL_GRIT,BlockType.OCEAN_COLD_GRAVEL,BlockType.OCEAN_ALGAE_STONE};weights=new int[]{40,25,20,15};}
            case DEEP -> {palette=new BlockType[]{BlockType.OCEAN_DARK_SILT,BlockType.OCEAN_BASALT_PEBBLES,BlockType.OCEAN_COLD_GRAVEL,BlockType.OCEAN_ALGAE_STONE};weights=new int[]{55,20,15,10};}
            case WARM -> {palette=new BlockType[]{BlockType.OCEAN_CORAL_SAND,BlockType.OCEAN_SHELL_GRIT,BlockType.OCEAN_REEF_LIMESTONE,BlockType.OCEAN_DEAD_CORAL_RUBBLE};weights=new int[]{45,25,20,10};}
            case COLD -> {palette=new BlockType[]{BlockType.OCEAN_COLD_GRAVEL,BlockType.OCEAN_ALGAE_STONE,BlockType.OCEAN_SILTY_SAND,BlockType.OCEAN_BASALT_PEBBLES};weights=new int[]{45,25,20,10};}
            case FROZEN -> {palette=new BlockType[]{BlockType.OCEAN_COLD_GRAVEL,BlockType.OCEAN_BASALT_PEBBLES,BlockType.OCEAN_DARK_SILT,BlockType.OCEAN_ALGAE_STONE};weights=new int[]{45,30,15,10};}
            case REEF -> {palette=new BlockType[]{BlockType.OCEAN_REEF_LIMESTONE,BlockType.OCEAN_CORAL_SAND,BlockType.OCEAN_DEAD_CORAL_RUBBLE,BlockType.OCEAN_SHELL_GRIT};weights=new int[]{40,30,20,10};}
            case KELP -> {palette=new BlockType[]{BlockType.OCEAN_ALGAE_STONE,BlockType.OCEAN_COLD_GRAVEL,BlockType.OCEAN_SILTY_SAND,BlockType.OCEAN_DARK_SILT};weights=new int[]{40,25,20,15};}
            case ABYSSAL -> {palette=new BlockType[]{BlockType.OCEAN_ABYSSAL_SILT,BlockType.OCEAN_BASALT_PEBBLES,BlockType.OCEAN_DARK_SILT};weights=new int[]{65,25,10};}
            case ESTUARY -> {palette=new BlockType[]{BlockType.OCEAN_SILTY_SAND,BlockType.OCEAN_DARK_SILT,BlockType.OCEAN_SHELL_GRIT,BlockType.OCEAN_ALGAE_STONE};weights=new int[]{45,25,20,10};}
            case VOLCANIC -> {palette=new BlockType[]{BlockType.OCEAN_BASALT_PEBBLES,BlockType.OCEAN_ABYSSAL_SILT,BlockType.OCEAN_DEAD_CORAL_RUBBLE,BlockType.OCEAN_ALGAE_STONE};weights=new int[]{55,20,15,10};}
            default -> throw new IllegalStateException();
        }
        // Continuous multi-scale noise with soft weighted competition. Independent
        // coherent fields avoid visible square patch boundaries at 9-block intervals.
        // Use small-scale variation only as a perturbation, not a cell selector.
        // Two independent continuous warps break up straight mineral borders.
        // Smaller, organic lenses blend at shallow and deep-ocean transitions.
        double warpX=depositNoise.fbm2(x/115.0,z/115.0,3,0x39e3L)*15.0;
        double warpZ=depositNoise.fbm2(x/115.0,z/115.0,3,0x7bd2L)*15.0;
        double u=(x+warpX)/25.0,v=(z+warpZ)/25.0;
        double best=Double.NEGATIVE_INFINITY;
        BlockType selected=palette[0];
        for(int i=0;i<palette.length;i++) {
            long salt=0x523eaL+0x9E3779B97F4A7C15L*i;
            double coarse=depositNoise.fbm2(u,v,3,salt);
            double detail=depositNoise.fbm2(u*2.7,v*2.7,2,salt^0x72feL);
            // Weight remains meaningful without overwhelming local variation.
            double score=0.75*Math.log(weights[i])+2.6*coarse+0.30*detail;
            if(score>best){best=score;selected=palette[i];}
        }
        return selected;
    }
    private static long rockHash(int x,int y,int z) {
        long v=((long)x*0x9E3779B97F4A7C15L)^((long)y*0xD6E8FEB86659FD93L)
            ^((long)z*0xC2B2AE3D27D4EB4FL);
        v=(v^(v>>>30))*0xbf58476d1ce4e5b9L;
        v=(v^(v>>>27))*0x94d049bb133111ebL;
        return v^(v>>>31);
    }
    /** Tree trunks precede leaf placement, including at cross-column boundaries. */
    private void populateFeatures(ChunkColumn c) {
        int minX=c.cx*CHUNK_SIZE, minZ=c.cz*CHUNK_SIZE;
        int maxX=minX+CHUNK_SIZE-1, maxZ=minZ+CHUNK_SIZE-1;
        int tc=WorldGenerator.TREE_CELL_SIZE;
        List<WorldGenerator.Tree> trees=new ArrayList<>();
        for(int tx=Math.floorDiv(minX-13,tc);tx<=Math.floorDiv(maxX+13,tc);tx++)
            for(int tz=Math.floorDiv(minZ-13,tc);tz<=Math.floorDiv(maxZ+13,tc);tz++) {
                WorldGenerator.Tree tree=generator.treeForCell(tx,tz);
                if(tree!=null){trees.add(tree);placeTree(c,tree,minX,minZ);}
            }
        for(WorldGenerator.Tree tree:trees) {
            int ground=generator.surface(tree.x(),tree.z()).surfaceY();
            if(ground<1||ground+tree.trunkHeight()+5>=WORLD_HEIGHT)continue;
            TreeFoliage.generate(tree,ground,(x,y,z,leaf)->putFeature(c,x,y,z,leaf,minX,minZ));
        }
        int pc=WorldGenerator.PLANT_CELL_SIZE;
        for(int px=Math.floorDiv(minX,pc);px<=Math.floorDiv(maxX,pc);px++)
            for(int pz=Math.floorDiv(minZ,pc);pz<=Math.floorDiv(maxZ,pc);pz++) {
                WorldGenerator.Plant plant=generator.plantForCell(px,pz);
                if(plant!=null)placePlant(c,plant,minX,minZ);
            }
    }
    private void putFeature(ChunkColumn c,int x,int y,int z,BlockType type,int minX,int minZ) {
        if(x<minX||x>=minX+CHUNK_SIZE||z<minZ||z>=minZ+CHUNK_SIZE||y<1||y>=WORLD_HEIGHT-1)return;
        int idx=index(x-minX,y,z-minZ);
        if(c.get(idx)==BlockType.AIR.id)c.set(idx,type.id);
    }
    private void placeTree(ChunkColumn c,WorldGenerator.Tree t,int minX,int minZ) {
        int x=t.x(),z=t.z();
        int y=generator.surface(x,z).surfaceY()+1;
        if(y<2||y+t.trunkHeight()+4>=WORLD_HEIGHT)return;
        BlockType log=treeLog(t.type());
        int leanX=t.type()==WorldGenerator.TreeType.PALM ? ((t.x()&1)==0?1:-1) : 0;
        int leanZ=t.type()==WorldGenerator.TreeType.ACACIA ? ((t.z()&1)==0?1:-1) : 0;
        for(int h=0;h<t.trunkHeight();h++){
            int step=h*2/t.trunkHeight();
            putFeature(c,x+leanX*step,y+h,z+leanZ*step,log,minX,minZ);
        }
        // Stilt roots anchor mangroves into banks; alder has low multi-stem offshoots.
        if(t.type()==WorldGenerator.TreeType.MANGROVE || t.type()==WorldGenerator.TreeType.ALDER) {
            for(int dir=0;dir<4;dir++) {
                int dx=(dir%2==0)?(dir==0?1:-1):0;
                int dz=(dir%2==1)?(dir==1?1:-1):0;
                for(int k=1;k<=2;k++)putFeature(c,x+dx*k,y+Math.max(0,3-k),z+dz*k,
                        log.orientedLog(dx!=0?0:2),minX,minZ);
            }
        }
        if (branchCount(t)>0) {
            int count=branchCount(t);
            for(int b=0;b<count;b++) {
                int[] tip=branchTip(t,b);
                int startY=y+(int)(t.trunkHeight()*.57)+(b%3);
                int endY=y+t.trunkHeight()+tip[1];
                int steps=Math.max(1,Math.max(Math.abs(tip[0]),Math.max(Math.abs(tip[2]),Math.abs(endY-startY)))*2);
                for(int i=0;i<=steps;i++) {
                    double f=i/(double)steps;
                    int bx=x+(int)Math.round(tip[0]*f),bz=z+(int)Math.round(tip[2]*f);
                    int by=(int)Math.round(startY+(endY-startY)*f);
                    putFeature(c,bx,by,bz,log.orientedLog(Math.abs(tip[0])>=Math.abs(tip[2])?0:2),minX,minZ);
                }
            }
        }
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
    private static int branchCount(WorldGenerator.Tree tree) {
        return switch(tree.type()) {
            case OAK,BIRCH -> 4; case MAPLE,WILLOW,ALDER -> 6;
            case ACACIA -> 5; case MANGROVE -> 5;
            default -> 0;
        };
    }
    /** Returns relative X, vertical displacement from the trunk tip, and Z. */
    private static int[] branchTip(WorldGenerator.Tree t,int branch) {
        long v=((long)t.x()*0x9E3779B97F4A7C15L)^((long)t.z()*0xC2B2AE3D27D4EB4FL)
                ^((long)branch*0x165667B19E3779F9L);
        v ^= v>>>30; v*=0xbf58476d1ce4e5b9L;v^=v>>>27;
        double angle=2*Math.PI*(branch+.23+((v>>>12)&255)/1024.0)/Math.max(1,branchCount(t));
        int length=switch(t.type()) {
            case OAK,BIRCH -> 3+(int)((v>>>24)&3);
            case MAPLE,ALDER -> 3+(int)((v>>>24)&3);
            case WILLOW,ACACIA,MANGROVE -> 5+(int)((v>>>24)&3);
            default -> 2;
        };
        int rise=switch(t.type()) {
            case OAK,BIRCH,MAPLE -> -2+(branch%3);
            case WILLOW,ALDER -> -2+(branch%2);
            case ACACIA -> -1+(branch%2);
            case MANGROVE -> -1+(branch%3);
            default -> 0;
        };
        return new int[]{(int)Math.round(length*Math.cos(angle)),rise,(int)Math.round(length*Math.sin(angle))};
    }
    private void placePlant(ChunkColumn c,WorldGenerator.Plant p,int minX,int minZ) {
        int x=p.x(),z=p.z();
        int y=generator.surface(x,z).surfaceY()+1;
        if(y<2||y>=WORLD_HEIGHT-3)return;
        String species=p.species();
        if(species.contains("cactus")) {
            for(int h=0;h<2;h++)putFeature(c,x,y+h,z,BlockType.CACTUS,minX,minZ);
        } else if(species.contains("rock")||species.contains("boulder")||species.contains("scree")) {
            putFeature(c,x,y,z,BlockType.MOSSY_ROCK,minX,minZ);
        }
        else {
            BlockType plant=plantBlock(species);
            if(plant!=null && generator.surface(x,z).waterBody()==WorldGenerator.WaterBody.LAND)
                putFeature(c,x,y,z,plant,minX,minZ);
        }
    }
    private static int leafLayer(WorldGenerator.TreeType species) {
        return TreeFoliage.leafFor(species).getSideTextureLayer();
    }
    private static BlockType plantBlock(String species) {
        String name=species.toLowerCase(Locale.ROOT);
        if(name.contains("clover"))return BlockType.CLOVER_PLANT;
        if(name.contains("tall_grass") || name.contains("wild_wheat"))return BlockType.TALL_GRASS_PLANT;
        if(name.contains("cattail"))return BlockType.CATTAIL_PLANT;
        if(name.contains("cornflower"))return BlockType.CORNFLOWER;
        if(name.contains("orchid"))return BlockType.ORCHID_FLOWER;
        if(name.contains("frost_shrub") || name.contains("snow_grass"))return BlockType.FROST_SHRUB;
        if(name.contains("dry_scrub") || name.contains("dry_shrub") || name.contains("desert_succulent"))return BlockType.DRY_SCRUB;
        if(name.contains("lichen"))return BlockType.LICHEN_PATCH;
        if(name.contains("fern"))return BlockType.FERN_PLANT;
        if(name.contains("reeds")||name.contains("cattail"))return BlockType.REED_PLANT;
        if(name.contains("dead_bush")||name.contains("dry_shrub"))return BlockType.DEAD_BUSH_PLANT;
        if(name.contains("shrub"))return BlockType.SHRUB_PLANT;
        if(name.contains("mushroom"))return BlockType.MUSHROOM_PLANT;
        if(name.contains("poppy"))return BlockType.POPPY_FLOWER;
        if(name.contains("lavender"))return BlockType.LAVENDER_FLOWER;
        if(name.contains("daisy")||name.contains("flower")||name.contains("orchid"))return BlockType.DAISY_FLOWER;
        if(name.contains("grass")||name.contains("sedge"))return BlockType.GRASS_TUFT;
        return BlockType.GRASS_TUFT;
    }
    /** Sparse biome-specific cave decorations on actual carved faces. */
    private void populateCaveDecorations(ChunkColumn c) {
        for(int lx=1;lx<CHUNK_SIZE-1;lx+=3)for(int lz=1;lz<CHUNK_SIZE-1;lz+=3) {
            int wx=c.cx*CHUNK_SIZE+lx,wz=c.cz*CHUNK_SIZE+lz;
            WorldGenerator.Surface surf=generator.surface(wx,wz);
            for(int y=9;y<surf.surfaceY()-11;y+=5) {
                // Very cheap hash rejection before the expensive cave-biome query.
                long h=rockHash(wx,y,wz);
                if((h&63L)>6L)continue;
                int i=index(lx,y,lz);
                if(c.get(i)!=BlockType.AIR.id)continue;
                boolean ceiling=BlockType.fromId(c.get(i+1)).isSolid();
                boolean floor=BlockType.fromId(c.get(i-1)).isSolid();
                if(!floor&&!ceiling)continue;
                var context=generator.caveSampler().contextAt(wx,y,wz,surf);
                var biome=generator.caveSampler().choose(wx,y,wz,context);
                if(biome==null)continue;
                BlockType decor=switch(biome) {
                    case ROOTLACE_HOLLOWS,SEEPSTONE_GROTTOS -> ceiling ? BlockType.HANGING_ROOTS : BlockType.MUSHROOM_PLANT;
                    case HOLLOWSTONE_VAULTS,SALTVEIL_CHAMBERS -> ceiling?BlockType.STALACTITE:BlockType.STALAGMITE;
                    case CRYSTAL_SEAM_CHAMBERS -> floor?BlockType.CRYSTAL_CLUSTER:null;
                    case SPORELIGHT_HOLLOWS -> floor?BlockType.SPORECAP_FUNGUS:BlockType.HANGING_ROOTS;
                    default -> null;
                };
                if(decor!=null)c.set(i,decor.id);
            }
        }
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
    private void generateOreVeins(ChunkColumn c) {
        final int spacing=16, band=24;
        for(int ax=c.cx-1;ax<=c.cx+1;ax++) for(int az=c.cz-1;az<=c.cz+1;az++) {
            for(int ay=0;ay<=WORLD_HEIGHT/band;ay++) for(int type=0;type<5;type++) {
                long salt=0x4f524553L+type*0x54abL;
                // Rates are probabilities of a deposit, not inverse rarity.
                // Diamond/gold should be genuinely rarer than coal/iron.
                double chance=switch(type){case 0->.60;case 1->.43;case 2->.18;case 3->.09;default->.026;};
                int ox=ax*spacing+(int)(depositNoise.unit(ax,ay,az,salt+1)*spacing);
                int oy=ay*band+(int)(depositNoise.unit(ax,ay,az,salt+2)*band);
                int oz=az*spacing+(int)(depositNoise.unit(ax,ay,az,salt+3)*spacing);
                if(oy<5 || oy>WORLD_HEIGHT-8)continue;
                int sea=generator.settings().seaLevel();
                if(type==0 && oy>sea+48 || type==1 && oy>sea+24
                        || type==2 && (oy<14||oy>sea-6)
                        || type==3 && (oy<8||oy>sea-45)
                        || type==4 && oy>sea-105)continue;
                // Soft host-province preference, not hard filtering: mixed rock
                // retains occasional discoveries outside the most favorable province.
                com.wildforge.worldgen.Geology province=generator.geologyAt(ox,oz);
                double bonus=switch(type) {
                    case 0 -> province==com.wildforge.worldgen.Geology.SHALE || province==com.wildforge.worldgen.Geology.SANDSTONE ? 1.25 : .9;
                    case 1 -> province==com.wildforge.worldgen.Geology.GRANITE || province==com.wildforge.worldgen.Geology.SHALE ? 1.18 : 1.0;
                    case 2 -> province==com.wildforge.worldgen.Geology.GRANITE ? 1.22 : .9;
                    case 3 -> province==com.wildforge.worldgen.Geology.VOLCANIC || province==com.wildforge.worldgen.Geology.BASALT ? 1.38 : .85;
                    default -> province==com.wildforge.worldgen.Geology.GRANITE || province==com.wildforge.worldgen.Geology.BASALT ? 1.25 : .9;
                };
                if(depositNoise.unit(ax,ay,az,salt)>=Math.min(.95,chance*bonus))continue;
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
        ChunkColumn c=chunks.get(key(Math.floorDiv(x,CHUNK_SIZE),Math.floorDiv(z,CHUNK_SIZE)));
        if(c==null)return BlockType.AIR;
        return BlockType.fromId(c.get(index(Math.floorMod(x,CHUNK_SIZE),y,Math.floorMod(z,CHUNK_SIZE))));
    }
    /** Logical-state API; legacy byte column storage remains the save encoding. */
    public BlockState getBlockStateAt(int x,int y,int z) {
        return BlockState.fromLegacy(getBlockAt(x,y,z));
    }

    public boolean setBlockState(int x,int y,int z,BlockState state) {
        return setBlock(x,y,z,java.util.Objects.requireNonNull(state).toLegacyBlock());
    }

    public boolean setBlock(int x,int y,int z,BlockType type){
        if(type==null||y<0||y>=WORLD_HEIGHT)return false;
        int cx=Math.floorDiv(x,CHUNK_SIZE),cz=Math.floorDiv(z,CHUNK_SIZE);
        ChunkColumn c=chunks.get(key(cx,cz));if(c==null)return false;
        int i=index(Math.floorMod(x,CHUNK_SIZE),y,Math.floorMod(z,CHUNK_SIZE));
        if(c.get(i)==type.id)return false;
        BlockType previous=BlockType.fromId(c.get(i));
        c.set(i,type.id);
        storeFlowLevel(key(cx,cz),i,type,1);
        if(previous.isFluid()||type==BlockType.AIR||type.isFluid()) {
            scheduleFluid(x+1,y,z);scheduleFluid(x-1,y,z);
            scheduleFluid(x,y,z+1);scheduleFluid(x,y,z-1);
            scheduleFluid(x,y+1,z);scheduleFluid(x,y-1,z);
            scheduleFluid(x,y,z);
        }
        edits.computeIfAbsent(key(cx,cz),k->new HashMap<>()).put(i,type.id);
        markMeshDirty(cx,cz,y);
        if(Math.floorMod(x,CHUNK_SIZE)==0)markMeshDirty(cx-1,cz,y);
        if(Math.floorMod(x,CHUNK_SIZE)==CHUNK_SIZE-1)markMeshDirty(cx+1,cz,y);
        if(Math.floorMod(z,CHUNK_SIZE)==0)markMeshDirty(cx,cz-1,y);
        if(Math.floorMod(z,CHUNK_SIZE)==CHUNK_SIZE-1)markMeshDirty(cx,cz+1,y);
        version++;return true;
    }
    public float getHeightAt(float x,float z){
        int bx=(int)Math.floor(x),bz=(int)Math.floor(z);
        // Spawn queries may precede streaming into a new area.
        for(int y=WORLD_HEIGHT-1;y>=0;y--){
            BlockType b=getBlockAt(bx,y,bz);
            if(b.isSolid())return y+b.collisionHeight();
        }
        return Math.max(1,Math.min(WORLD_HEIGHT-2,generator.surface(bx,bz).surfaceY()+1));
    }
    /** Read-only mesh descriptions; mesh arrays are replaced, never mutated. */
    public record ChunkMesh(int x, int section, int z, int revision, float[] vertices) {}
    /**
     * Surface-only distant LOD. Each 2x2 patch produces one top tile plus exposed
     * outer faces. Full-resolution blocks remain stored for edits and collision.
     * This intentionally omits underground cave walls and decorative foliage.
     */
    private LodMeshes buildDistantMeshes(ChunkColumn c,Map<Integer,Byte> levels) {
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
                    drawDistantCell(out,water,ox,oz,lx+dx,lz+dz,top,layer,
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
            int south=lz+step<CHUNK_SIZE?top[lx][lz+step]:distantSurfaceY(x,z+step);
            int north=lz>0?top[lx][lz-1]:distantSurfaceY(x,z-step);
            int east=lx+step<CHUNK_SIZE?top[lx+step][lz]:distantSurfaceY(x+step,z);
            int west=lx>0?top[lx-1][lz]:distantSurfaceY(x-step,z);
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
        return new LodMeshes(out.toArray(),water.toArray());
    }

    private void drawDistantCell(FloatArrayBuilder out,FloatArrayBuilder water,int ox,int oz,
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
            else neighborY=distantSurfaceY(ox+nx,oz+nz)+1f;
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
    private void lodBox(FloatArrayBuilder out,float x0,float y0,float z0,float x1,float y1,float z1,int tex) {
        face(out,x0,y0,z1,x1,y0,z1,x1,y1,z1,x0,y1,z1,tex,.88f);
        face(out,x1,y0,z0,x0,y0,z0,x0,y1,z0,x1,y1,z0,tex,.76f);
        face(out,x1,y0,z1,x1,y0,z0,x1,y1,z0,x1,y1,z1,tex,.84f);
        face(out,x0,y0,z0,x0,y0,z1,x0,y1,z1,x0,y1,z0,tex,.80f);
        face(out,x0,y1,z1,x1,y1,z1,x1,y1,z0,x0,y1,z0,tex,1f);
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
            ChunkColumn chunk=chunks.get(entry.getKey());
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
        java.util.ArrayList<ChunkColumn> ordered=new java.util.ArrayList<>(chunks.values());
        ordered.sort(java.util.Comparator.comparingLong(c->{
            long dx=(long)c.cx-cameraChunkX,dz=(long)c.cz-cameraChunkZ;
            return dx*dx+dz*dz;
        }));
        java.util.ArrayList<ChunkMesh> result=new java.util.ArrayList<>(chunks.size()*4);
        for(ChunkColumn c:ordered){
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
                        Map<Integer,Byte> flows=flowSnapshot(chunkX,chunkZ);
                        Future<LodMeshes> task=lodWorker.submit(()->{
                            ChunkColumn immutable=new ChunkColumn(chunkX,chunkZ);
                            immutable.blocks=snapshot;
                            return buildDistantMeshes(immutable,flows);
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
        for(ChunkColumn c:chunks.values()) for(int section=0;section<SECTION_COUNT;section++) {
            if(c.sectionVertices[section].length>0)result.add(new ChunkMesh(c.cx,section,c.cz,c.sectionRevision[section],c.sectionVertices[section]));
            if(c.fluidVertices[section].length>0)result.add(new ChunkMesh(c.cx,section+FLUID_SECTION_OFFSET,c.cz,c.fluidRevision[section],c.fluidVertices[section]));
        }
        return result;
    }
    public void newWorld(long newSeed){
        cancelGeneration();edits.clear();fluidLevels.clear();seed=newSeed;worldgenRevision=WORLDGEN_REVISION;initializeGenerator();chunks.clear();
        initialSpawn=SpawnFinder.find(generator,512);
        generateSpawnChunk(initialSpawn.x(),initialSpawn.z());version++;
    }
    public int getVersion(){return version;}
    public float[] getVertices(){
        int n=0;for(ChunkColumn c:chunks.values()) {
            for(float[] section:c.sectionVertices)n+=section.length;
            for(float[] section:c.fluidVertices)n+=section.length;
        }
        float[] all=new float[n];int offset=0;
        for(ChunkColumn c:chunks.values()) {
            for(float[] section:c.sectionVertices){System.arraycopy(section,0,all,offset,section.length);offset+=section.length;}
            for(float[] section:c.fluidVertices){System.arraycopy(section,0,all,offset,section.length);offset+=section.length;}
        }
        return all;
    }
    public int getVertexCount(){int n=0;for(ChunkColumn c:chunks.values()){
        for(float[] section:c.sectionVertices)n+=section.length/FLOATS_PER_VERTEX;
        for(float[] section:c.fluidVertices)n+=section.length/FLOATS_PER_VERTEX;
    }return n;}
    /** v4 stores sparse block edits plus persistent flowing-fluid strengths. */
    public void saveTo(Path path)throws IOException{
        Path p=path.toAbsolutePath();Files.createDirectories(p.getParent());Path temp=p.resolveSibling(p.getFileName()+".tmp");
        try{
            try(DataOutputStream out=new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(temp)))){
                out.writeInt(MAGIC);out.writeInt(SAVE_VERSION);out.writeLong(seed);out.writeInt(WORLD_HEIGHT);out.writeInt(worldgenRevision);out.writeInt(edits.size());
                for(var entry:edits.entrySet()){
                    out.writeLong(entry.getKey());out.writeInt(entry.getValue().size());
                    for(var change:entry.getValue().entrySet()){out.writeInt(change.getKey());out.writeByte(change.getValue());}
                }
                out.writeInt(fluidLevels.size());
                for(var entry:fluidLevels.entrySet()) {
                    out.writeLong(entry.getKey());out.writeInt(entry.getValue().size());
                    for(var cell:entry.getValue().entrySet()){
                        out.writeInt(cell.getKey());out.writeByte(cell.getValue());
                    }
                }
            }
            try{Files.move(temp,p,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}
            catch(AtomicMoveNotSupportedException e){Files.move(temp,p,StandardCopyOption.REPLACE_EXISTING);}
        }finally{Files.deleteIfExists(temp);}
    }
    public void loadFrom(Path path)throws IOException{
        HashMap<Long,HashMap<Integer,Byte>> incoming=new HashMap<>();
        HashMap<Long,HashMap<Integer,Byte>> incomingFluidLevels=new HashMap<>();
        long newSeed=DEFAULT_SEED;boolean migrateLegacy=false;int incomingRevision=WORLDGEN_REVISION;
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
            }else if(v==2 || v==3 || v==4 || v==5){
                newSeed=in.readLong();
                if(v>=3){int savedHeight=in.readInt();if(savedHeight!=WORLD_HEIGHT)throw new IOException("Unsupported saved world height: "+savedHeight);}
                if(v>=5) {
                    incomingRevision=in.readInt();
                    if(incomingRevision==5 && WORLDGEN_REVISION==6) {
                        // v5->v6 uses the same numeric blocks and sparse-edit layout.
                        // Accept the world and upgrade its generator revision at the
                        // next save. Unmodified terrain may change: advise backup.
                        System.err.println("[Wildforge] Upgrading worldgen 5 -> 6. Back up your world: "
                                + "unmodified rivers and seabeds may regenerate differently.");
                        incomingRevision=WORLDGEN_REVISION;
                    } else if(incomingRevision!=WORLDGEN_REVISION)
                        throw new IOException("World generator revision "+incomingRevision+
                                " is incompatible with this build ("+WORLDGEN_REVISION+"). Back up the save and use a matching version.");
                }
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
                if(v>=4) {
                    int regions=in.readInt();
                    if(regions<0||regions>1000000)throw new IOException("Invalid fluid region count");
                    for(int n=0;n<regions;n++) {
                        long k=in.readLong();int countLevels=in.readInt();
                        if(countLevels<0||countLevels>CHUNK_SIZE*CHUNK_SIZE*WORLD_HEIGHT)
                            throw new IOException("Invalid fluid-level count");
                        HashMap<Integer,Byte> levels=incomingFluidLevels.computeIfAbsent(k,ignore->new HashMap<>());
                        Map<Integer,Byte> changes=incoming.get(k);
                        for(int j=0;j<countLevels;j++) {
                            int idx=in.readInt(),level=in.readUnsignedByte();
                            if(idx<0||idx>=CHUNK_SIZE*CHUNK_SIZE*WORLD_HEIGHT||level<1||level>7||
                               changes==null||!(changes.getOrDefault(idx,(byte)-1)==BlockType.FLOWING_WATER.id||
                               changes.getOrDefault(idx,(byte)-1)==BlockType.FLOWING_LAVA.id))
                                throw new IOException("Invalid fluid level or missing flowing block");
                            if(levels.putIfAbsent(idx,(byte)level)!=null)
                                throw new IOException("Duplicate fluid-level entry");
                        }
                    }
                }
            }else throw new IOException("Unsupported world save version: "+v);
            if(in.read()!=-1)throw new IOException("World save has trailing data");
        }
        // Commit only after complete validation.
        legacyVerticalMigration=migrateLegacy;
        initialSpawn=null;
        cancelGeneration();seed=newSeed;worldgenRevision=incomingRevision;initializeGenerator();edits.clear();edits.putAll(incoming);
        fluidLevels.clear();fluidLevels.putAll(incomingFluidLevels);
        chunks.clear();generateSpawnChunk(16,27);version++;
    }
    private static float fluidSideBase(byte current,byte[] neighbor,int idx,
                                       Map<Integer,Byte> levels,boolean exposed,int y,float top) {
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
        float neighborY=y+fluidSurfaceHeight(snapshotFluidLevel(other,levels,idx));
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
    /** Build only exposed faces. ChunkColumn neighbors are resolved once per rebuild. */
    private void rebuild(ChunkColumn c){
        BitSet all=new BitSet(SECTION_COUNT);
        all.set(0,SECTION_COUNT);
        float[][] layers=buildSelectedMeshes(snapshot(c),all);
        for(int section=0;section<SECTION_COUNT;section++) {
            c.sectionVertices[section]=layers[section];
            c.fluidVertices[section]=layers[section+SECTION_COUNT];
            c.sectionRevision[section]++;
            c.fluidRevision[section]++;
        }
    }
    // Never mix translucent fluids into the opaque / cutout terrain mesh.
    // Both streams use the same immutable neighbor snapshot and revision epoch.
    private static int snapshotLevel(MeshSnapshot snap,int x,int y,int z,byte fluidId) {
        byte[] blocks=snap.blocks();Map<Integer,Byte> levels=snap.flows();
        if(x<0) {blocks=snap.west();levels=snap.westFlows();x+=CHUNK_SIZE;}
        else if(x>=CHUNK_SIZE){blocks=snap.east();levels=snap.eastFlows();x-=CHUNK_SIZE;}
        if(z<0) {if(blocks!=snap.blocks())return -1;blocks=snap.north();levels=snap.northFlows();z+=CHUNK_SIZE;}
        else if(z>=CHUNK_SIZE){if(blocks!=snap.blocks())return -1;blocks=snap.south();levels=snap.southFlows();z-=CHUNK_SIZE;}
        if(blocks==null||y<0||y>=WORLD_HEIGHT)return -1;
        int i=index(x,y,z);byte id=blocks[i];
        if(!fluidId(id)||((id==BlockType.WATER.id||id==BlockType.FLOWING_WATER.id)!=
           (fluidId==BlockType.WATER.id||fluidId==BlockType.FLOWING_WATER.id)))return -1;
        return snapshotFluidLevel(id,levels,i);
    }
    private static float fluidCornerHeight(MeshSnapshot snap,int lx,int y,int lz,
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
    private static int fluidFlowCode(MeshSnapshot snap,int x,int y,int z,byte id) {
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
                            lz==CHUNK_SIZE-1?snap.southFlows():snap.flows(),showSouth,y,top);
                    float northBase=fluidSideBase(id,northSource,northColumn+y,
                            lz==0?snap.northFlows():snap.flows(),showNorth,y,top);
                    float eastBase=fluidSideBase(id,eastSource,eastColumn+y,
                            lx==CHUNK_SIZE-1?snap.eastFlows():snap.flows(),showEast,y,top);
                    float westBase=fluidSideBase(id,westSource,westColumn+y,
                            lx==0?snap.westFlows():snap.flows(),showWest,y,top);
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
    /** Only submerged plants remain water-overlay visuals pending waterlogged block states. */
    private void appendFoliage(MeshSnapshot snap, FloatArrayBuilder data, int section) {
        appendMarineDecorations(snap,data,section);
    }
    /** Marine decorations are deterministic lightweight cutout models, not solid cubes. */
    private void appendMarineDecorations(MeshSnapshot snap,FloatArrayBuilder out,int section) {
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
    private void crossPlantAt(FloatArrayBuilder data,int x,int y,int z,int layer,boolean ceiling) {
        if(!ceiling){crossPlant(data,x,y,z,layer,0.88f);return;}
        // Hang below the block above; roots don't float from the floor.
        float y0=y+.12f, y1=y+1f;
        doubleFace(data,x+.12f,y0,z+.12f,x+.88f,y0,z+.88f,
                x+.88f,y1,z+.88f,x+.12f,y1,z+.12f,layer,.92f);
        doubleFace(data,x+.12f,y0,z+.88f,x+.88f,y0,z+.12f,
                x+.88f,y1,z+.12f,x+.12f,y1,z+.88f,layer,.90f);
    }
    private void spike(FloatArrayBuilder data,int x,int y,int z,int layer,boolean hangs) {
        float baseY=hangs?y+1f:y,tipY=hangs?y+.08f:y+.92f;
        float min=x+.12f,max=x+.88f,left=z+.12f,right=z+.88f;
        // Two crossed, double-sided tapered planes; no solid cube or collision.
        doubleFace(data,min,baseY,left,max,baseY,right,x+.51f,tipY,z+.51f,
                x+.49f,tipY,z+.49f,layer,.92f);
        doubleFace(data,min,baseY,right,max,baseY,left,x+.51f,tipY,z+.49f,
                x+.49f,tipY,z+.51f,layer,.84f);
    }
    /** Three small prisms produce a solid 3D mineral cluster without a new shader. */
    private void crystalCluster(FloatArrayBuilder out,int x,int y,int z,int layer) {
        crystalSpike(out,x+.31f,y,z+.32f,.22f,.88f,layer);
        crystalSpike(out,x+.69f,y,z+.62f,.17f,.59f,layer);
        crystalSpike(out,x+.44f,y,z+.76f,.14f,.43f,layer);
    }
    private void crystalSpike(FloatArrayBuilder out,float x,float y,float z,
                              float radius,float height,int layer) {
        float tip=y+height;
        // Four tapered faces around a diamond cross section, double-sided.
        doubleFace(out,x-radius,y,z,x,y,z-radius,x,tip,z,x,tip,z,layer,.87f);
        doubleFace(out,x,y,z-radius,x+radius,y,z,x,tip,z,x,tip,z,layer,.98f);
        doubleFace(out,x+radius,y,z,x,y,z+radius,x,tip,z,x,tip,z,layer,.82f);
        doubleFace(out,x,y,z+radius,x-radius,y,z,x,tip,z,x,tip,z,layer,.92f);
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
