package com.wildforge.world;

import com.wildforge.content.BlockDefinition;
import com.wildforge.content.BlockRegistry;
import com.wildforge.content.ResourceId;
import java.io.*;
import java.util.*;

/** No OpenGL needed: exact state identities, model resolution and seam meshing. */
public final class Phase8ANativeBlocksSmokeTest {
    private static void require(boolean ok,String message) {
        if(!ok)throw new AssertionError(message);
    }
    private static BlockDefinition definition(String id,int numeric,String model) {
        return new BlockDefinition(ResourceId.parse(id),numeric,false,true,.2f,null,
                ResourceId.parse("wildforge:"+model),null,null,null,List.of());
    }
    private static SectionMeshBuilder.Snapshot snapshot(int[] cells, int[] west) {
        byte[] legacy = bytes(cells);
        byte[] westLegacy = west==null?null:bytes(west);
        return new SectionMeshBuilder.Snapshot(0,0,legacy,westLegacy,null,null,null,
                Map.of(),Map.of(),Map.of(),Map.of(),Map.of(),null,
                new SectionMeshBuilder.NativeStates(cells,west,null,null,null));
    }
    private static byte[] bytes(int[] ids) {
        byte[] raw = new byte[ids.length];
        for(int i=0;i<ids.length;i++)raw[i]=BlockStateRegistry.GLOBAL.legacyRenderId(ids[i]);
        return raw;
    }
    private static int visibleFloats(SectionMeshBuilder.Snapshot snap,int sy) {
        BitSet sections=new BitSet();sections.set(sy);
        return SectionMeshBuilder.buildSelectedMeshes(snap,sections)[sy].length;
    }
    public static void main(String[] args) throws Exception {
        BlockRegistry blocks=new BlockRegistry();
        blocks.register(definition("wildforge:shimmer_fern",4096,"block/cross_plant"));
        blocks.register(definition("example:glowing_leaves",4097,"block/leaves"));
        BlockModels.install(blocks);
        com.wildforge.content.BlockCatalog.install(blocks);
        var registry=BlockStateRegistry.GLOBAL;
        BlockState fern=registry.baseState(ResourceId.parse("wildforge:shimmer_fern"));
        BlockState leaf=registry.baseState(ResourceId.parse("example:glowing_leaves"));
        require(fern.resourceId().toString().equals("wildforge:shimmer_fern"),"registered identity");
        require(BlockModels.modelFor(fern).shape()==BlockModels.Shape.CROSS_PLANT,"custom plant model");
        require(BlockModels.modelFor(leaf).pass()==BlockModels.Pass.CUTOUT,"custom leaves model");
        require(!BlockBehaviors.forBlock(fern.resourceId()).solid(),"registered plant is not solid stone");
        require(BlockBehaviors.forBlock(fern.resourceId()).support()==BlockBehaviors.Support.BELOW,
                "registered plant needs ground support");
        var hit=new TerrainMesh.RayHit(3,30,-7,3,30,-6,fern);
        require(hit.state.equals(fern) && hit.block==BlockType.STONE,
                "targeting preserves name, even with a legacy geometry fallback");
        int fernId=registry.idFor(fern.withProperty("age","3"));
        require(fernId>255 && registry.byId(fernId).properties().get("age").equals("3"),"extra property");
        require(registry.idFromKey(registry.persistentKey(fernId))==fernId,"stable named codec");
        try {registry.idFromKey("example:unregistered_block");throw new AssertionError("unknown block silently accepted");}
        catch(IllegalArgumentException expected) { /* saves must reject unknown block types */ }
        byte[] empty=new byte[16*16*512];
        PaletteColumn column=new PaletteColumn(empty);
        int ix=ChunkCoordinates.localIndex(0,31,15);
        column.setStateId(ix,fernId);
        require(column.copyStateIds()[ix]==fernId,"native state column snapshot");
        require(column.copyRaw()[ix]==BlockType.STONE.id,"legacy adapter intentionally lossy");
        ChunkColumn chunk=new ChunkColumn(-3,7);
        chunk.setStateId(ix,fernId);
        require(chunk.copyStateIds()[ix]==fernId && chunk.stateId(0,31,15)==fernId,"live column native state");
        Map<Long,Map<Integer,Integer>> edits=Map.of(ChunkCoordinates.pack(-3,7),Map.of(ix,fernId));
        ByteArrayOutputStream sink=new ByteArrayOutputStream();
        StateEditCodec.write(new DataOutputStream(sink),edits,registry);
        var loaded=StateEditCodec.read(new DataInputStream(new ByteArrayInputStream(sink.toByteArray())),registry);
        require(loaded.get(ChunkCoordinates.pack(-3,7)).get(ix)==fernId,"reloaded custom block and property");

        // Native geometry must use the model of the registered ID, not its stone fallback.
        int[] center=new int[16*16*512];
        int a=ChunkCoordinates.localIndex(8,18,8);
        center[a]=registry.idFor(fern);
        int plantGeometry=visibleFloats(snapshot(center,null),1);
        center[a]=Byte.toUnsignedInt(BlockType.STONE.id);
        int cubeGeometry=visibleFloats(snapshot(center,null),1);
        require(plantGeometry>0 && plantGeometry<cubeGeometry,"plant renderer ignores byte fallback");

        // A cutout neighbor on the west chunk edge cannot occlude a stone side.
        Arrays.fill(center,0);
        center[ChunkCoordinates.localIndex(0,18,7)]=Byte.toUnsignedInt(BlockType.STONE.id);
        int[] neighbor=new int[16*512];
        neighbor[7*512+18]=registry.idFor(leaf);
        int visible=visibleFloats(snapshot(center,neighbor),1);
        neighbor[7*512+18]=Byte.toUnsignedInt(BlockType.STONE.id);
        int occluded=visibleFloats(snapshot(center,neighbor),1);
        require(visible>occluded,"native chunk-seam occlusion differs from stone fallback");
        require(visible-occluded==6*7,"exactly one quad was revealed");
        System.out.println("Phase 8A native blocks PASS: stable IDs, save, models, palette and seam visibility");
    }
}
