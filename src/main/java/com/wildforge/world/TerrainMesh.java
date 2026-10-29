package com.wildforge.world;

import com.wildforge.worldgen.Biome;
import com.wildforge.worldgen.SpawnFinder;
import com.wildforge.world.mesh.FloatArrayBuilder;
import com.wildforge.world.fluid.FluidRules;
import com.wildforge.world.fluid.FluidEngine;

import com.wildforge.worldgen.WorldGenerator;
import com.wildforge.worldgen.WorldGenConfigLoader;
import com.wildforge.worldgen.BiomeDistribution;
import com.wildforge.worldgen.SeededNoise;
import com.wildforge.worldgen.CaveCarvers;
import com.wildforge.worldgen.CaveBiomePainter;
import com.wildforge.worldgen.StrataGenerator;
import com.wildforge.worldgen.OreFeatureGenerator;
import com.wildforge.worldgen.AquiferSampler;
import com.wildforge.worldgen.CaveDecorationCatalog;
import com.wildforge.worldgen.MarineSedimentCatalog;
import com.wildforge.worldgen.CaveWallCatalog;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Bounded, player-centered chunk cache with persistent sparse edits and fresh-world generation. */
public final class TerrainMesh implements AutoCloseable {
    public static final int CHUNK_SIZE=16, WORLD_HEIGHT=512, SECTION_HEIGHT=16, SECTION_COUNT=WORLD_HEIGHT/SECTION_HEIGHT;
    public static final int FLOATS_PER_VERTEX=7;
    // Fluid sections use a separate mesh stream (section + FLUID_SECTION_OFFSET).
    // No block IDs or save indexes change.
    public static final int FLUID_SECTION_OFFSET=SECTION_COUNT;
    private static final int MAGIC=0x57464F52, SAVE_VERSION=5;
    private static final long DEFAULT_SEED=0x57494C44464F5247L;
    /** Generator revision is independent of the binary save format. Never change an
     * old revision's semantics silently; reject incompatible future saves. */
    public static final int WORLDGEN_REVISION=WorldGenerator.CURRENT_REVISION;
    private int worldgenRevision=WORLDGEN_REVISION;
    public int worldgenRevision(){return worldgenRevision;}
    private final ChunkSource chunkSource = new ChunkSource();
    private final HashMap<Long,HashMap<Integer,Byte>> edits = new HashMap<>();
    private WorldGenerator generator;
    private SpawnFinder.Spawn initialSpawn;
    private BiomeDistribution distribution;
    private SeededNoise depositNoise;
    private StrataGenerator strata;
    private OreFeatureGenerator oreFeatures;
    private AquiferSampler aquifers;
    private CaveDecorationCatalog caveDecorations;
    private CaveWallCatalog caveWalls;
    private MarineSedimentCatalog marineSediments;
    private long seed=DEFAULT_SEED;
    // Fluid scheduling is transient world simulation state; TerrainMesh retains
    // sparse, on-disk fluid levels and provides the authoritative cell mutations.
    private final FluidEngine fluids = new FluidEngine(new FluidEngine.WorldAccess() {
        @Override public boolean loaded(int x,int z) { return isColumnLoaded(x,z); }
        @Override public boolean simulating(int x,int z) { return withinSimulation(x,z); }
        @Override public BlockType block(int x,int y,int z) { return getBlockAt(x,y,z); }
        @Override public int level(int x,int y,int z) { return getFluidLevelAt(x,y,z); }
        @Override public boolean change(int x,int y,int z,BlockType type) { return setBlock(x,y,z,type); }
        @Override public boolean flowInto(int x,int y,int z,BlockType type,int strength) {
            return tryFluid(x,y,z,type,strength);
        }
        @Override public void weaken(int x,int y,int z,int strength) { weakenFlow(x,y,z,strength); }
    }, WORLD_HEIGHT);
    private int simulationCenterX=1,simulationCenterZ=1;
    private static final byte LEGACY_FLOW_LEVEL = 6;
    // Sparse, persistent levels for FLOWING_* cells only. Source blocks are always level 7.
    // Indexing matches edits (chunk key + column-major block index), so unloaded fluids retain state.
    private final HashMap<Long,HashMap<Integer,Byte>> fluidLevels = new HashMap<>();
    private int version=0, renderDistance=8, simulationDistance=5;
    // Raw world generation is owned by ChunkSource. Section/LOD worker lifecycle,
    // revisions and stale result handling are owned by ChunkMeshScheduler.
    private final ChunkMeshScheduler meshScheduler = new ChunkMeshScheduler();
    @Override public void close() {
        chunkSource.close();
        meshScheduler.close();
    }
    private void cancelGeneration() {
        fluids.reset();
        chunkSource.reset();
        meshScheduler.reset();
    }
    private void generateSpawnChunk(int x, int z) {
        int cx = Math.floorDiv(x, CHUNK_SIZE), cz = Math.floorDiv(z, CHUNK_SIZE);
        ChunkColumn c = generate(cx, cz);
        applySavedEdits(c);
        chunkSource.install(c);
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
        try {
            var terrainSettings=WorldGenConfigLoader.loadBuiltIn();
            generator=new WorldGenerator(seed,terrainSettings,worldgenRevision);
            distribution=new BiomeDistribution(seed);
            depositNoise=new SeededNoise(seed);
            strata=StrataGenerator.loadBuiltIn(seed,terrainSettings.seaLevel());
            oreFeatures=OreFeatureGenerator.loadBuiltIn(seed);
            aquifers=new AquiferSampler(seed,terrainSettings);
            caveDecorations=CaveDecorationCatalog.loadBuiltIn();
            caveWalls=CaveWallCatalog.loadBuiltIn();
            marineSediments=MarineSedimentCatalog.loadBuiltIn();
        }
        catch(IOException e){ throw new IllegalStateException("Worldgen profile unavailable; run generateContent",e); }
    }
    public TerrainMesh(){initializeGenerator();generateSpawnChunk(16,27);}
    public void setDistances(int render,int simulation){
        renderDistance=Math.max(2,Math.min(24,render));
        int next=Math.max(2,Math.min(renderDistance,simulation));
        if(next!=simulationDistance) {
            simulationDistance=next;
            fluids.reclassify();
            fluids.wake();
        }
    }
    public int renderDistance(){return renderDistance;}
    public int simulationDistance(){return simulationDistance;}
    public int loadedChunkCount(){return chunkSource.size();}
    /** Approximate encoded block payload, excluding object overhead and CPU meshes. */
    public long residentBlockPayloadBytes(){long bytes=0;for(ChunkColumn c:chunkSource.columns())bytes+=c.compact==null?CHUNK_SIZE*CHUNK_SIZE*WORLD_HEIGHT:c.compact.storedBytes();return bytes;}
    /** Count of outstanding terrain generation jobs; queried on the game thread. */
    public int pendingGenerationCount(){return chunkSource.pendingCount();}
    /** True only when a terrain column is actually resident in memory. */
    public boolean isColumnLoaded(int worldX,int worldZ){
        return chunkSource.containsKey(key(Math.floorDiv(worldX,CHUNK_SIZE),Math.floorDiv(worldZ,CHUNK_SIZE)));
    }

    public String biomeNameAt(int worldX,int worldZ) {
        return generator.surface(worldX,worldZ).biome().name().replace('_',' ');
    }
    public int activeFluidUpdates(){return fluids.activeCount();}
    public int sleepingFluidUpdates(){return fluids.sleepingCount();}
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
    /** Chosen at new-world creation; may be null for loaded worlds. */
    public SpawnFinder.Spawn initialSpawn(){return initialSpawn;}
    public void resetWorld(){initialSpawn=null;cancelGeneration();edits.clear();fluidLevels.clear();seed=DEFAULT_SEED;worldgenRevision=WORLDGEN_REVISION;initializeGenerator();generateSpawnChunk(16,27);version++;}
    /** Nonblocking player-centered streaming: one worker produces raw columns, main thread owns meshes. */
    public void streamAround(float x,float z){
        int cx=Math.floorDiv((int)Math.floor(x),CHUNK_SIZE);
        int cz=Math.floorDiv((int)Math.floor(z),CHUNK_SIZE);
        if(cx!=simulationCenterX || cz!=simulationCenterZ) {
            simulationCenterX=cx;simulationCenterZ=cz;
            fluids.reclassify();
            fluids.wake();
        }
        boolean changed = chunkSource.evictOutside(cx, cz, renderDistance, c -> {
            long removed = key(c.cx, c.cz);
            meshScheduler.evict(removed);
            fluids.discardColumn(removed);
            // Neighbors must expose their newly visible outer faces after eviction.
            markMeshDirty(c.cx - 1, c.cz);
            markMeshDirty(c.cx + 1, c.cz);
            markMeshDirty(c.cx, c.cz - 1);
            markMeshDirty(c.cx, c.cz + 1);
        }) > 0;

        // Install at most one completed raw column per frame; never mesh on the
        // generation worker and never accept a canceled/out-of-range request.
        ChunkColumn completed = chunkSource.pollCompleted();
        if (completed != null) {
            applySavedEdits(completed);
            completed.compact();
            chunkSource.install(completed);
            resumeEditedFlows(completed);
            activateGeneratedRiverFalls(completed);
            activateFluidSeams(completed);
            fluids.wake();
            markMeshDirty(completed.cx, completed.cz);
            markMeshDirty(completed.cx - 1, completed.cz);
            markMeshDirty(completed.cx + 1, completed.cz);
            markMeshDirty(completed.cx, completed.cz - 1);
            markMeshDirty(completed.cx, completed.cz + 1);
            changed = true;
        }
        chunkSource.requestNearest(cx, cz, renderDistance, 2, this::generate);
        // Only the game thread installs completed meshes. The scheduler checks
        // epoch and generation before touching a column's render cache.
        if(meshScheduler.installSections(chunkSource,1)>0)changed=true;
        meshScheduler.scheduleSections(chunkSource,cx,cz,(target,sections)->{
            SectionMeshBuilder.Snapshot snapshot=snapshot(target);
            return ()->SectionMeshBuilder.buildSelectedMeshes(snapshot,sections);
        });
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
        if (!chunkSource.containsKey(k) || sections.isEmpty()) return;
        meshScheduler.markDirty(k,sections,chunkSource.get(k));
    }
    private SectionMeshBuilder.Snapshot snapshot(ChunkColumn c) {
        ChunkColumn w=chunkSource.get(key(c.cx-1,c.cz)), e=chunkSource.get(key(c.cx+1,c.cz));
        ChunkColumn n=chunkSource.get(key(c.cx,c.cz-1)), so=chunkSource.get(key(c.cx,c.cz+1));
        return new SectionMeshBuilder.Snapshot(c.cx,c.cz,c.copyBlocks(),
            edge(w,CHUNK_SIZE-1,true),edge(e,0,true),edge(n,CHUNK_SIZE-1,false),edge(so,0,false),
            flowSnapshot(c.cx,c.cz),flowSnapshot(c.cx-1,c.cz),flowSnapshot(c.cx+1,c.cz),
            flowSnapshot(c.cx,c.cz-1),flowSnapshot(c.cx,c.cz+1),generator);
    }
    /** Only copy the 16x512 boundary of adjacent chunks. Formerly every mesh
     * job copied five entire 16x512x16 columns (~640 KiB per request). */
    private static byte[] edge(ChunkColumn column,int axis,boolean fixedX) {
        if(column==null)return null;
        byte[] out=new byte[CHUNK_SIZE*WORLD_HEIGHT];
        for(int p=0;p<CHUNK_SIZE;p++)for(int y=0;y<WORLD_HEIGHT;y++)
            out[p*WORLD_HEIGHT+y]=column.get(fixedX?index(axis,y,p):index(p,y,axis));
        return out;
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
    public int pendingMeshSectionCount() {return meshScheduler.pendingMeshSectionCount();}
    public int pendingMeshCount() {return meshScheduler.pendingMeshCount();}
    public int pendingLodCount() {return meshScheduler.pendingLodCount();}
    public int readyLodCount() {int n=0;for(ChunkColumn c:chunkSource.columns())if(c.render.hasDistant())n++;return n;}
    private ChunkColumn generate(int cx,int cz) {
        ChunkColumn c=new ChunkColumn(cx,cz);
        // Generation passes correspond to the major stages of Minecraft-style
        // chunk construction. Keep rules independent of load order.
        return new com.wildforge.worldgen.ChunkGenerationPipeline.Builder<ChunkColumn>()
            .add("density-and-surface", this::buildBaseTerrain)
            .add("cave-carvers", column -> new CaveCarvers(seed).carve(column.blocks,column.cx,column.cz,generator))
            .add("cave-surface", column -> CaveBiomePainter.paint(column.blocks,column.cx,column.cz,generator,WORLD_HEIGHT,caveWalls))
            .add("ores", column -> oreFeatures.generate(column.blocks,column.cx,column.cz,WORLD_HEIGHT,generator))
            .add("aquifers-and-waterbodies", this::placeNaturalFluids)
            .add("cave-decorations", this::populateCaveDecorations)
            .add("surface-features", this::populateFeatures)
            .add("snow-cover", this::populateSnowCover)
            .build().run(c);
    }

    private void buildBaseTerrain(ChunkColumn c) {
        int cx=c.cx,cz=c.cz;
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
            // Sample the full signed-density transition, including cliff overhangs.
            // Deep rock remains inexpensive because density short-circuits there.
            int highest=Math.min(WORLD_HEIGHT-2,top+26);
            for(int y=0;y<=highest;y++){
                if(y>0 && !generator.isSolidTerrain(wx,y,wz,top,slope))continue;
                if(y>=5 && generator.isCave(wx,y,wz,top))continue;
                // Rock overhangs above the original height receive rock faces,
                // rather than floating copies of biome topsoil.
                // Ordinary stone dominates the crust. Deeper strata transition to
                // darker slate; biome-specific rock occurs as sparse coherent lenses.
                BlockType rock=strata.rockAt(wx,y,wz,geologicalRock);
                // Bedrock is the first irreversible terrain stage, never an ore or biome accent.
                BlockType b=strata.bedrockAt(wx,y,wz)?BlockType.BEDROCK:
                        y>top?rock:y==top?topBlock:y>=top-soilDepth?filler:rock;
                c.blocks[index(lx,y,lz)]=b.id;
            }
        }
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
            // Only fill air already carved into caves. Aquifer field uses XYZ
            // and local water tables, so open mineshaft-like water columns disappear.
            int top=Math.min(surface.surfaceY()-17,sea-10);
            for(int y=5;y<top;y++) {
                int idx=index(lx,y,lz);
                if(c.blocks[idx]!=BlockType.AIR.id)continue;
                BlockType fluid=aquifers.fluidAt(wx,y,wz,surface);
                if(fluid!=BlockType.AIR)c.blocks[idx]=fluid.id;
            }
        }
    }

    /** Source blocks contain full fluid; flow levels are compact, persistent 1..7 values. */
    public int getFluidLevelAt(int x,int y,int z) {
        if(y<0||y>=WORLD_HEIGHT)return 0;
        int cx=Math.floorDiv(x,CHUNK_SIZE),cz=Math.floorDiv(z,CHUNK_SIZE);
        ChunkColumn c=chunkSource.get(key(cx,cz));
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
        ChunkColumn c=chunkSource.get(key(cx,cz));if(c==null)return false;
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
    private void scheduleFluid(int x,int y,int z) { fluids.schedule(x,y,z); }
    /** When the adjacent column arrives, reopen fluid/air contacts on both
     * sides of the seam. Without this, an earlier failed spill is never retried.
     * Only one-block-wide border strips are examined, not entire ocean volumes.
     */
    private void activateFluidSeams(ChunkColumn loaded) {
        for(int[] dir:new int[][]{{1,0},{-1,0},{0,1},{0,-1}}) {
            ChunkColumn neighbor=chunkSource.get(key(loaded.cx+dir[0],loaded.cz+dir[1]));
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
    /** Compatibility entry point for callers with elapsed wall-clock time. */
    public void tickFluids(float elapsedSeconds) { fluids.advance(elapsedSeconds); }
    /** The authoritative world tick is driven by GameSimulation (20 Hz). */
    public void stepFluids() { fluids.step(); }
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
        ChunkColumn c=chunkSource.get(chunkKey);if(c==null)return false;
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

    /** Downgrade existing flow metadata without changing its save-format block ID. */
    private void weakenFlow(int x,int y,int z,int level) {
        int cx=Math.floorDiv(x,CHUNK_SIZE),cz=Math.floorDiv(z,CHUNK_SIZE);
        long k=key(cx,cz);
        ChunkColumn column=chunkSource.get(k);
        if(column==null)return;
        int i=index(Math.floorMod(x,CHUNK_SIZE),y,Math.floorMod(z,CHUNK_SIZE));
        BlockType existing=BlockType.fromId(column.get(i));
        if(existing!=BlockType.FLOWING_WATER && existing!=BlockType.FLOWING_LAVA)return;
        if(getFluidLevelAt(x,y,z)==level)return;
        storeFlowLevel(k,i,existing,level);
        edits.computeIfAbsent(k,unused->new HashMap<>()).put(i,existing.id);
        markFluidMeshDirty(x,y,z);
        version++;
    }

    /** Marine palettes are local to the ocean type and only cover a thin exposed seabed. */
    private BlockType seabedAt(int x,int z,int y,int sea,int slope,WorldGenerator.Surface surface) {
        if(surface.waterBody()==WorldGenerator.WaterBody.RIVER)
            return slope>5?BlockType.WATERWORN_STONE:BlockType.RIVERBED_SEDIMENT;
        if(surface.waterBody()==WorldGenerator.WaterBody.LAKE)
            return slope>4?BlockType.GRAVEL:BlockType.CLAY;
        WorldGenerator.OceanBiome ocean=generator.oceanBiomeAt(x,z,surface);
        java.util.List<MarineSedimentCatalog.Entry> palette=marineSediments.forOcean(ocean);
        // Continuous multi-scale noise with soft weighted competition. Independent
        // coherent fields avoid visible square patch boundaries at 9-block intervals.
        // Use small-scale variation only as a perturbation, not a cell selector.
        // Two independent continuous warps break up straight mineral borders.
        // Smaller, organic lenses blend at shallow and deep-ocean transitions.
        double warpX=depositNoise.fbm2(x/115.0,z/115.0,3,0x39e3L)*15.0;
        double warpZ=depositNoise.fbm2(x/115.0,z/115.0,3,0x7bd2L)*15.0;
        double u=(x+warpX)/25.0,v=(z+warpZ)/25.0;
        double best=Double.NEGATIVE_INFINITY;
        BlockType selected=palette.get(0).block();
        for(int i=0;i<palette.size();i++) {
            long salt=0x523eaL+0x9E3779B97F4A7C15L*i;
            double coarse=depositNoise.fbm2(u,v,3,salt);
            double detail=depositNoise.fbm2(u*2.7,v*2.7,2,salt^0x72feL);
            // Weight remains meaningful without overwhelming local variation.
            double score=0.75*Math.log(palette.get(i).weight())+2.6*coarse+0.30*detail;
            if(score>best){best=score;selected=palette.get(i).block();}
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
                CaveDecorationCatalog.Rule feature=caveDecorations.get(biome);
                BlockType decor=floor?feature.floor():feature.ceiling();
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
    public BlockType getBlockAt(int x,int y,int z){
        if(y<0||y>=WORLD_HEIGHT)return BlockType.AIR;
        ChunkColumn c=chunkSource.get(key(Math.floorDiv(x,CHUNK_SIZE),Math.floorDiv(z,CHUNK_SIZE)));
        if(c==null)return BlockType.AIR;
        return BlockType.fromId(c.get(index(Math.floorMod(x,CHUNK_SIZE),y,Math.floorMod(z,CHUNK_SIZE))));
    }
    /** Logical-state API; compact byte IDs remain the current chunk encoding. */
    public BlockState getBlockStateAt(int x,int y,int z) {
        return BlockState.fromLegacy(getBlockAt(x,y,z));
    }

    public boolean setBlockState(int x,int y,int z,BlockState state) {
        return setBlock(x,y,z,java.util.Objects.requireNonNull(state).toLegacyBlock());
    }

    public boolean setBlock(int x,int y,int z,BlockType type){
        if(type==null||y<0||y>=WORLD_HEIGHT)return false;
        int cx=Math.floorDiv(x,CHUNK_SIZE),cz=Math.floorDiv(z,CHUNK_SIZE);
        ChunkColumn c=chunkSource.get(key(cx,cz));if(c==null)return false;
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
    /** Choose one representation per column; section=-1 identifies a distant mesh.
     * Builds far meshes on a worker from private block snapshots. Until a far mesh is
     * ready the existing detailed sections remain visible (no terrain holes).
     */
    public boolean lodBuildPending(){return meshScheduler.lodBuildPending();}
    public java.util.List<ChunkMesh> chunkMeshesForView(int cameraChunkX,int cameraChunkZ,int detailRadius){
        if(meshScheduler.installDistant(chunkSource)>0)version++;
        meshScheduler.setLodBuildPending(false);
        java.util.ArrayList<ChunkColumn> ordered=new java.util.ArrayList<>(chunkSource.columns());
        ordered.sort(java.util.Comparator.comparingLong(c->{
            long dx=(long)c.cx-cameraChunkX,dz=(long)c.cz-cameraChunkZ;
            return dx*dx+dz*dz;
        }));
        java.util.ArrayList<ChunkMesh> result=new java.util.ArrayList<>(chunkSource.size()*4);
        for(ChunkColumn c:ordered){
            int radius=Math.max(Math.abs(c.cx-cameraChunkX),Math.abs(c.cz-cameraChunkZ));
            long k=key(c.cx,c.cz);
            // Both meshes exist in the transition ring for a dithered crossfade.
            boolean needsFar = radius>=Math.max(2,detailRadius-2);
            boolean needsNear = radius<=detailRadius+2;
            if(needsFar){
                if(!c.render.hasDistant()){
                    meshScheduler.setLodBuildPending(true);
                    if(!meshScheduler.hasDistantJob(k)){
                        // Never read mutable live blocks from a worker thread.
                        byte[] snapshot=c.copyBlocks();
                        int chunkX=c.cx,chunkZ=c.cz;
                        Map<Integer,Byte> flows=flowSnapshot(chunkX,chunkZ);
                        WorldGenerator generatorSnapshot=generator;
                        meshScheduler.requestDistant(k,c,()->{
                            ChunkColumn immutable=new ChunkColumn(chunkX,chunkZ);
                            immutable.blocks=snapshot;
                            return DistantMeshBuilder.build(immutable,flows,generatorSnapshot);
                        });

                    }
                }else if(c.render.distantTerrain().length>0){
                    result.add(new ChunkMesh(c.cx,-1,c.cz,c.render.distantRevision(),c.render.distantTerrain()));
                    if(c.render.distantFluids()!=null && c.render.distantFluids().length>0)
                        result.add(new ChunkMesh(c.cx,-2,c.cz,c.render.distantRevision(),c.render.distantFluids()));
                }
                // Keep fully detailed geometry until the far mesh is ready.
                if(!needsNear && c.render.hasDistant())continue;
            }
            for(int section=0;section<SECTION_COUNT;section++) {
                if(c.render.terrain(section).length>0)
                    result.add(new ChunkMesh(c.cx,section,c.cz,c.render.terrainRevision(section),c.render.terrain(section)));
                if(c.render.fluid(section).length>0)
                    result.add(new ChunkMesh(c.cx,section+FLUID_SECTION_OFFSET,c.cz,c.render.fluidRevision(section),c.render.fluid(section)));
            }
        }
        return result;
    }

    public java.util.List<ChunkMesh> chunkMeshes(){
        java.util.ArrayList<ChunkMesh> result=new java.util.ArrayList<>(chunkSource.size()*SECTION_COUNT);
        for(ChunkColumn c:chunkSource.columns()) for(int section=0;section<SECTION_COUNT;section++) {
            if(c.render.terrain(section).length>0)result.add(new ChunkMesh(c.cx,section,c.cz,c.render.terrainRevision(section),c.render.terrain(section)));
            if(c.render.fluid(section).length>0)result.add(new ChunkMesh(c.cx,section+FLUID_SECTION_OFFSET,c.cz,c.render.fluidRevision(section),c.render.fluid(section)));
        }
        return result;
    }
    public void newWorld(long newSeed){
        cancelGeneration();edits.clear();fluidLevels.clear();seed=newSeed;worldgenRevision=WORLDGEN_REVISION;initializeGenerator();
        initialSpawn=SpawnFinder.find(generator,512);
        generateSpawnChunk(initialSpawn.x(),initialSpawn.z());
        if(initialSpawn.platform()) createEmergencySpawnPlatform(initialSpawn);
        version++;
    }
    /** Persist a small walkable starting platform only if an unusually ocean-heavy
     * seed has no qualifying land within the bounded search. Located entirely
     * inside a single loaded chunk; edits survive saves and subsequent streaming. */
    private void createEmergencySpawnPlatform(SpawnFinder.Spawn spawn) {
        int groundY = spawn.y() - 1;
        for (int dx = -3; dx <= 3; dx++) for (int dz = -3; dz <= 3; dz++) {
            int x = spawn.x() + dx, z = spawn.z() + dz;
            setBlock(x, groundY, z, BlockType.STONE);
            // A player must not start embedded in foliage, fluids or debris.
            for (int dy = 1; dy <= 3; dy++) setBlock(x, groundY + dy, z, BlockType.AIR);
        }
        System.out.println("Spawn search found no walkable land nearby; generated a safe starter platform at "
                + spawn.x() + ", " + spawn.y() + ", " + spawn.z());
    }
    public int getVersion(){return version;}
    public float[] getVertices(){
        int n=0;for(ChunkColumn c:chunkSource.columns()) n+=c.render.floatCount();
        float[] all=new float[n];int offset=0;
        for(ChunkColumn c:chunkSource.columns()) {
            c.render.copyFloats(all, offset);
            offset += c.render.floatCount();
        }
        return all;
    }
    public int getVertexCount(){int n=0;for(ChunkColumn c:chunkSource.columns())n+=c.render.floatCount();return n/FLOATS_PER_VERTEX;}
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
    /** Only saves with the current binary format and generation revision are supported.
     * Older terrain uses different noise and cannot safely be reinterpreted by this generator.
     * Back up old saves and start a new world when switching generator revisions.
     */
    public void loadFrom(Path path)throws IOException{
        HashMap<Long,HashMap<Integer,Byte>> incoming=new HashMap<>();
        HashMap<Long,HashMap<Integer,Byte>> levels=new HashMap<>();
        long newSeed;
        try(DataInputStream in=new DataInputStream(new BufferedInputStream(Files.newInputStream(path)))){
            if(in.readInt()!=MAGIC)throw new IOException("Not a Wildforge world save");
            int version=in.readInt();
            if(version!=SAVE_VERSION)throw new IOException("Old save format version "+version+
                    " is not supported by fresh-world generation; back up the old save and create a new world");
            newSeed=in.readLong();
            int height=in.readInt(),revision=in.readInt();
            if(height!=WORLD_HEIGHT||revision!=WORLDGEN_REVISION)
                throw new IOException("This world uses generator revision "+revision+
                        "; fresh Wildforge requires revision "+WORLDGEN_REVISION+
                        ". Create a new world; old terrain will not be reinterpreted");
            int chunks=in.readInt();
            if(chunks<0||chunks>1000000)throw new IOException("Invalid chunk count");
            for(int n=0;n<chunks;n++){
                long k=in.readLong();int count=in.readInt();
                if(count<0||count>CHUNK_SIZE*CHUNK_SIZE*WORLD_HEIGHT)
                    throw new IOException("Invalid edit count");
                HashMap<Integer,Byte> editsAt=new HashMap<>();
                for(int i=0;i<count;i++){
                    int at=in.readInt();byte id=in.readByte();
                    if(at<0||at>=CHUNK_SIZE*CHUNK_SIZE*WORLD_HEIGHT||!BlockType.isValidId(id)
                            ||editsAt.putIfAbsent(at,id)!=null)throw new IOException("Invalid or duplicate edit");
                }
                if(incoming.putIfAbsent(k,editsAt)!=null)throw new IOException("Duplicate chunk record");
            }
            int regions=in.readInt();
            if(regions<0||regions>1000000)throw new IOException("Invalid fluid region count");
            for(int n=0;n<regions;n++){
                long k=in.readLong();int size=in.readInt();
                if(size<0||size>CHUNK_SIZE*CHUNK_SIZE*WORLD_HEIGHT)throw new IOException("Invalid fluid count");
                HashMap<Integer,Byte> values=new HashMap<>();
                Map<Integer,Byte> editsAt=incoming.get(k);
                for(int i=0;i<size;i++){
                    int index=in.readInt(),level=in.readUnsignedByte();
                    byte id=editsAt==null?-1:editsAt.getOrDefault(index,(byte)-1);
                    if(index<0||index>=CHUNK_SIZE*CHUNK_SIZE*WORLD_HEIGHT||level<1||level>7||
                       (id!=BlockType.FLOWING_WATER.id&&id!=BlockType.FLOWING_LAVA.id)||
                        values.putIfAbsent(index,(byte)level)!=null)
                        throw new IOException("Invalid flowing fluid entry");
                }
                if(levels.putIfAbsent(k,values)!=null)throw new IOException("Duplicate fluid region");
            }
            if(in.read()!=-1)throw new IOException("World save has trailing bytes");
        }
        initialSpawn=null;
        cancelGeneration();seed=newSeed;worldgenRevision=WORLDGEN_REVISION;initializeGenerator();
        edits.clear();edits.putAll(incoming);
        fluidLevels.clear();fluidLevels.putAll(levels);
        generateSpawnChunk(16,27);version++;
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

    /** Build only exposed faces. ChunkColumn neighbors are resolved once per rebuild. */
    private void rebuild(ChunkColumn c){
        BitSet all=new BitSet(SECTION_COUNT);
        all.set(0,SECTION_COUNT);
        float[][] layers=SectionMeshBuilder.buildSelectedMeshes(snapshot(c),all);
        for(int section=0;section<SECTION_COUNT;section++) {
            c.render.installSection(section, layers[section], layers[section+SECTION_COUNT]);
        }
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
