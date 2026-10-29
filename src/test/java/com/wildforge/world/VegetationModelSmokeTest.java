package com.wildforge.world;

import com.wildforge.item.ItemType;
import com.wildforge.worldgen.WorldGenerator;
import java.util.HashSet;
import java.util.Set;

/** Headless 3B tests: saves, model metadata, deterministic tree positions and drops. */
public final class VegetationModelSmokeTest {
    private VegetationModelSmokeTest() {}
    private static void require(boolean value, String message) {
        if(!value)throw new AssertionError(message);
    }
    public static void main(String[] args) {
        Set<Integer> ids=new HashSet<>();
        for(BlockType block:BlockType.values()) {
            require(ids.add(Byte.toUnsignedInt(block.getId())),"duplicate block save ID "+block);
            require(BlockType.fromId(block.getId())==block,"invalid legacy encoding "+block);
        }
        require(BlockType.values().length==195,"expected 195 blocks");
        require(ItemType.values().length==202,"expected 202 items");
        for(int id=155;id<=178;id++) {
            BlockType block=BlockType.fromId((byte)id);
            require(block!=BlockType.AIR && block.isBreakable() && block.isSolid()==(id<=165),
                    "wrong foliage collision: "+id);
            require(block.getDropItem()!=null && block.getDropCount()==1,"missing harvested item: "+block);
            require(block.getDropItem().getPlacedBlock()==block,"cannot replant: "+block);
            BlockModels.Model model=BlockModels.modelFor(BlockState.fromLegacy(block));
            require(model.pass()==BlockModels.Pass.CUTOUT && !model.fullCubeOcclusion(),"bad model pass: "+block);
        }
        for(int id=187;id<=194;id++) {
            BlockType plant=BlockType.fromId((byte)id);
            require(!plant.isSolid() && plant.isBreakable() && plant.getDropItem()!=null,
                    "missing newly registered vegetation: "+plant);
            require(plant.getDropItem().getPlacedBlock()==plant,"new plant cannot be replanted: "+plant);
            require(BlockModels.modelFor(BlockState.fromLegacy(plant)).shape()==BlockModels.Shape.CROSS_PLANT,
                    "new plant must render as cutout: "+plant);
        }
        for(WorldGenerator.TreeType species:WorldGenerator.TreeType.values()) {
            BlockType leaves=TreeFoliage.leafFor(species);
            require(leaves.getDropItem()!=null,"leaves missing item: "+species);
            require(BlockModels.modelFor(BlockState.fromLegacy(leaves)).shape()==BlockModels.Shape.CUBE,
                "leaves must have 3D cube geometry: "+species);
            WorldGenerator.Tree tree=new WorldGenerator.Tree(-29,51,13,species);
            Set<String> first=new HashSet<>(),second=new HashSet<>();
            TreeFoliage.generate(tree,100,(x,y,z,b)->{
                require(b==leaves,"wrong foliage species");
                require(y>1 && y<512,"leaf outside world height");
                first.add(x+":"+y+":"+z);
            });
            TreeFoliage.generate(tree,100,(x,y,z,b)->second.add(x+":"+y+":"+z));
            require(!first.isEmpty() && first.equals(second),"non-deterministic canopy: "+species);
            require(first.size()>10,"missing canopy: "+species);
        }
        require(BlockModels.modelFor(BlockState.fromLegacy(BlockType.CRYSTAL_CLUSTER)).shape()
                ==BlockModels.Shape.CRYSTAL,"crystal cluster should be 3D geometry");
        require(BlockType.CRYSTAL_CLUSTER.getDropItem()==ItemType.CRYSTAL_CLUSTER_BLOCK,
                "cluster drops must be harvestable");
        require(BlockModels.modelFor(BlockState.fromLegacy(BlockType.STALACTITE)).shape()
                ==BlockModels.Shape.SPIKE,"stalactite must be a pointed model");
        require(BlockModels.modelFor(BlockState.fromLegacy(BlockType.HANGING_ROOTS)).shape()
                ==BlockModels.Shape.CEILING_ATTACHMENT,"roots must hang from cave ceiling");
        require(BlockModels.modelFor(BlockState.fromLegacy(BlockType.FERN_PLANT)).shape()
                ==BlockModels.Shape.CROSS_PLANT,"fern must be a crossed plant model");
        require(BlockType.fromId((byte)0)==BlockType.AIR && BlockType.fromId((byte)154)==BlockType.PALM_LOG_Z,
                "historical save ID changed");
        System.out.println("Vegetation check passed: 33 harvestable types, 11 deterministic canopy layouts, 195 save-stable block IDs");
    }
}
