
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
    private BlockType miningType;
    private ItemType miningTool;
    private double miningSeconds;
    /** Target progress in [0,1], for future crack-overlay rendering. */
    public double miningProgress() {
        if(miningType==null)return 0;
        return Math.min(1.0, miningSeconds / MiningRules.seconds(miningType,hardness(miningType),miningTool));
    }
    public void stopMining() { miningType=null;miningTool=null;miningSeconds=0; }
    private float hardness(BlockType block) {
        BlockDefinition definition=content==null?null:content.block(block);
        return definition==null ? (MiningRules.preferred(block)==MiningRules.Tool.HAND?.35f:2f) : definition.getHardness();
    }
    /** Starts or continues mining the same block; does not instantly break in Survival. */
    public void tickMining(TerrainMesh terrain,Camera camera,ItemType held,float dt) {
        if(terrain==null||camera==null||dt<=0) {stopMining();return;}
        RayHit hit=raycastFromCamera(terrain,camera);
        if(hit==null||hit.block==BlockType.AIR){stopMining();return;}
        BlockDefinition definition=content==null?null:content.block(hit.block);
        if(definition==null?!hit.block.isBreakable():!definition.isBreakable()){stopMining();return;}
        if(miningType!=hit.block||mineX!=hit.x||mineY!=hit.y||mineZ!=hit.z||miningTool!=held){
            mineX=hit.x;mineY=hit.y;mineZ=hit.z;miningType=hit.block;miningTool=held;miningSeconds=0;
        }
        miningSeconds+=dt;
        if(miningProgress()>=1.0){
            boolean canHarvest=MiningRules.drops(hit.block,held);
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
        BlockDefinition block = content == null ? null : content.block(hit.block);
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
        if(!terrain.setBlock(hit.x,hit.y,hit.z,BlockType.AIR))return;
        if(creative || !harvest)return;
        if (block == null) {
            ItemType drop = hit.block.getDropItem();
            if (drop != null && hit.block.getDropCount() > 0) {
                worldItems.spawn(drop, hit.block.getDropCount(),
                        hit.x + 0.5f, hit.y + 0.35f, hit.z + 0.5f, terrain);
            }
        } else {
            for (BlockDefinition.Drop drop : block.getDrops()) {
                ItemType item = content.itemType(drop.item());
                // The current world-item system supports enum-backed items only.
                if (item == null) throw new IllegalStateException(
                        "Drop item is not supported by the legacy inventory: " + drop.item());
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

        if (terrain.getBlockAt(bx, by, bz) != BlockType.AIR) {
            return;
        }

        ItemType itemType = selected.getItemType();
        BlockType blockToPlace;
        if (content == null) {
            blockToPlace = itemType.getPlacedBlock();
        } else {
            ItemDefinition item = content.item(itemType);
            blockToPlace = item.getPlacesBlock() == null ? null
                    : content.blockType(item.getPlacesBlock());
        }
        if (blockToPlace == null || blockToPlace == BlockType.AIR) {
            return;
        }
        // Non-colliding foliage and sprites can be placed close to the player.
        // Ground and ceiling attachments require a support block.
        com.wildforge.world.BlockModels.Shape shape =
                com.wildforge.world.BlockModels.modelFor(BlockState.fromLegacy(blockToPlace)).shape();
        if (shape == com.wildforge.world.BlockModels.Shape.CROSS_PLANT ||
                shape == com.wildforge.world.BlockModels.Shape.SPIKE ||
                shape == com.wildforge.world.BlockModels.Shape.CEILING_ATTACHMENT) {
            boolean hangs=shape == com.wildforge.world.BlockModels.Shape.CEILING_ATTACHMENT
                    || blockToPlace == BlockType.STALACTITE;
            if (!terrain.getBlockAt(bx, by+(hangs?1:-1), bz).isSolid()) return;
        }

        if(blockToPlace.isSolid()
                && bx + 1 > playerX - playerRadius && bx < playerX + playerRadius
                && by + 1 > playerFeetY && by < playerFeetY + playerHeight
                && bz + 1 > playerZ - playerRadius && bz < playerZ + playerRadius) return;

        // The axis follows the face clicked: east/west X, north/south Z,
        // floor/ceiling Y. Horizontal logs drop the original log item.
        if(blockToPlace.isLog()) {
            int axis=(hit.placeX!=hit.x)?0:(hit.placeZ!=hit.z)?2:1;
            blockToPlace=BlockState.fromLegacy(blockToPlace)
                    .withAxis(BlockState.Axis.fromFaceAxis(axis)).toLegacyBlock();
        }
        if (terrain.setBlock(bx, by, bz, blockToPlace)) {
            worldItems.onBlockPlaced(bx, by, bz);
            if (!creative) inventory.removeItem(itemType, 1);
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
