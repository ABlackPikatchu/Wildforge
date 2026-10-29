
package com.wildforge.player;

import com.wildforge.content.BlockDefinition;
import com.wildforge.content.ContentLoader;
import com.wildforge.content.ItemDefinition;
import com.wildforge.content.RegistryContentAdapter;
import com.wildforge.inventory.Inventory;
import com.wildforge.item.ItemStack;
import com.wildforge.item.ItemType;
import com.wildforge.world.BlockType;
import com.wildforge.world.BlockState;
import com.wildforge.world.TerrainMesh;
import com.wildforge.world.TerrainMesh.RayHit;
import com.wildforge.world.WorldItemManager;
import org.joml.Vector3f;

public final class BlockInteraction {
    private static final float REACH = 6.0f;

    private final Inventory inventory;
    private final WorldItemManager worldItems;
    private final RegistryContentAdapter content;
    private int mineX,mineY,mineZ;
    private BlockState miningType;
    private ItemDefinition miningTool;
    private double miningSeconds;
    /** Target progress in [0,1], for future crack-overlay rendering. */
    public double miningProgress() {
        if(miningType==null)return 0;
        return Math.min(1.0, miningSeconds / MiningRules.seconds(
                content == null ? null : content.block(miningType),
                miningType.toLegacyBlock(), hardness(miningType), miningTool));
    }
    public void stopMining() { miningType=null;miningTool=null;miningSeconds=0; }
    private float hardness(BlockState state) {
        BlockType fallback=state.toLegacyBlock();
        BlockDefinition definition=content==null?null:content.block(state);
        return definition==null ? (MiningRules.preferred(fallback)==MiningRules.Tool.HAND?.35f:2f)
                : definition.getHardness();
    }
    /** Starts or continues mining the same block; does not instantly break in Survival. */
    /** Enum compatibility for older tools/tests; the active player passes definitions. */
    public void tickMining(TerrainMesh terrain,Camera camera,ItemType held,float dt) {
        tickMining(terrain,camera,held==null?null:com.wildforge.content.ItemCatalog.legacy(held),dt);
    }
    public void tickMining(TerrainMesh terrain,Camera camera,ItemDefinition held,float dt) {
        if(terrain==null||camera==null||dt<=0) {stopMining();return;}
        RayHit hit=raycastFromCamera(terrain,camera);
        if(hit==null||hit.block==BlockType.AIR){stopMining();return;}
        BlockDefinition definition=content==null?null:content.block(hit.state);
        if(definition==null?!hit.block.isBreakable():!definition.isBreakable()){stopMining();return;}
        if(!hit.state.equals(miningType)||mineX!=hit.x||mineY!=hit.y||mineZ!=hit.z||!java.util.Objects.equals(miningTool==null?null:miningTool.getId(),
                    held==null?null:held.getId())){
            mineX=hit.x;mineY=hit.y;mineZ=hit.z;miningType=hit.state;miningTool=held;miningSeconds=0;
        }
        miningSeconds+=dt;
        if(miningProgress()>=1.0){
            boolean canHarvest=MiningRules.drops(definition, hit.block, held);
            stopMining();
            breakHit(terrain,hit,definition,false,canHarvest);
        }
    }


    public BlockInteraction(Inventory inventory, WorldItemManager worldItems) {
        this(inventory, worldItems, null);
    }

    public BlockInteraction(Inventory inventory, WorldItemManager worldItems,
                            ContentLoader.Content gameContent) {
        if (inventory == null || worldItems == null) {
            throw new IllegalArgumentException(
                    "Inventory and world item manager cannot be null");
        }

        this.inventory = inventory;
        this.worldItems = worldItems;
        this.content = gameContent == null ? null : new RegistryContentAdapter(gameContent);
    }

    /**
     * Mines the targeted breakable block and spawns its drop in the world.
     */
    public void mineBlock(TerrainMesh terrain, Camera camera) {
        mineBlock(terrain,camera,false);
    }
    public void mineBlock(TerrainMesh terrain, Camera camera, boolean creative) {
        if (terrain == null || camera == null) {
            return;
        }

        RayHit hit = raycastFromCamera(terrain, camera);
        if (hit == null || hit.block == BlockType.AIR) return;

        // JSON drives breakability and drops in the live game; enum fallback
        // remains available for standalone legacy tests and tools.
        BlockDefinition block = content == null ? null : content.block(hit.state);
        if (block == null ? !hit.block.isBreakable() : !block.isBreakable()) return;

        if (!creative) {
            // Survival starts a timed action; fixed-step updates advance progress.
            return;
        }
        stopMining();
        breakHit(terrain,hit,block,true,false);
    }

    private void breakHit(TerrainMesh terrain, RayHit hit, BlockDefinition block,
                          boolean creative, boolean harvest) {
        if(!terrain.setBlockState(hit.x,hit.y,hit.z,BlockState.fromLegacy(BlockType.AIR)))return;
        if(creative || !harvest)return;
        if (block == null) {
            ItemType drop = hit.block.getDropItem();
            if (drop != null && hit.block.getDropCount() > 0) {
                worldItems.spawn(drop, hit.block.getDropCount(),
                        hit.x + 0.5f, hit.y + 0.35f, hit.z + 0.5f, terrain);
            }
        } else {
            for (BlockDefinition.Drop drop : block.getDrops()) {
                ItemDefinition item = com.wildforge.content.ItemCatalog.require(drop.item());
                worldItems.spawn(item, drop.count(), hit.x + 0.5f,
                        hit.y + 0.35f, hit.z + 0.5f, terrain);
            }
        }
    }

    /**
     * Places the selected hotbar item's block in the adjacent raycast cell.
     * Placement is rejected if the cell is occupied or overlaps the player.
     */
    public void placeBlock(TerrainMesh terrain, Camera camera,
                           float playerX,float playerZ,float playerFeetY,
                           float playerRadius,float playerHeight) {
        placeBlock(terrain,camera,playerX,playerZ,playerFeetY,playerRadius,playerHeight,false);
    }
    public void placeBlock(
            TerrainMesh terrain,
            Camera camera,
            float playerX,
            float playerZ,
            float playerFeetY,
            float playerRadius,
            float playerHeight, boolean creative
    ) {
        if (terrain == null || camera == null) {
            return;
        }

        ItemStack selected = inventory.getSelectedStack();
        if (selected == null || selected.isEmpty()) {
            return;
        }

        RayHit hit = raycastFromCamera(terrain, camera);
        if (hit == null) {
            return;
        }

        int bx = hit.placeX;
        int by = hit.placeY;
        int bz = hit.placeZ;

        if (terrain.getBlockStateAt(bx, by, bz).toLegacyBlock() != BlockType.AIR) {
            return;
        }

        ItemType itemType = selected.getItemType();
        ItemDefinition item = selected.definition();
        BlockState stateToPlace;
        if(content==null) {
            BlockType fallback=itemType==null?null:itemType.getPlacedBlock();
            stateToPlace=fallback==null?null:BlockState.fromLegacy(fallback);
        } else {
            var id=item.getPlacesBlock();
            if(id==null)return;
            stateToPlace=com.wildforge.world.BlockStateRegistry.GLOBAL.baseState(id);
        }
        if(stateToPlace==null || stateToPlace.toLegacyBlock()==BlockType.AIR)return;
        // Use the registered block's geometry/support, not its legacy numeric alias.
        var behavior=com.wildforge.world.BlockBehaviors.forBlock(stateToPlace.resourceId());
        if(behavior==null)behavior=com.wildforge.world.BlockBehaviors.forBlock(stateToPlace.toLegacyBlock());
        if(behavior!=null && !behavior.canPlace(terrain,new com.wildforge.world.BlockPos(bx,by,bz)))return;

        if((behavior==null?stateToPlace.toLegacyBlock().isSolid():behavior.solid())
                && bx + 1 > playerX - playerRadius && bx < playerX + playerRadius
                && by + 1 > playerFeetY && by < playerFeetY + playerHeight
                && bz + 1 > playerZ - playerRadius && bz < playerZ + playerRadius) return;

        // The axis follows the face clicked: east/west X, north/south Z,
        // floor/ceiling Y. Horizontal logs drop the original log item.
        if(stateToPlace.isLog()) {
            int axis=(hit.placeX!=hit.x)?0:(hit.placeZ!=hit.z)?2:1;
            stateToPlace=stateToPlace.withAxis(BlockState.Axis.fromFaceAxis(axis));
        }
        if (terrain.setBlockState(bx, by, bz, stateToPlace)) {
            worldItems.onBlockPlaced(bx, by, bz);
            if (!creative) inventory.removeItem(item, 1);
        }
    }

    private RayHit raycastFromCamera(TerrainMesh terrain, Camera camera) {
        Vector3f origin = camera.getPosition();
        Vector3f direction = camera.getLookDirection();

        return terrain.raycast(
                origin.x, origin.y, origin.z,
                direction.x, direction.y, direction.z,
                REACH
        );
    }
}
