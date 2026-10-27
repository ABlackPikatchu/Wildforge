package com.wildforge.player;

import com.wildforge.inventory.Inventory;
import com.wildforge.item.ItemStack;
import com.wildforge.item.ItemType;
import com.wildforge.world.BlockType;
import com.wildforge.world.TerrainMesh;
import com.wildforge.world.TerrainMesh.RayHit;
import org.joml.Vector3f;

/**
 * Handles player-driven block targeting, mining, drops, and placement.
 *
 * This class deliberately leaves movement, collision, camera control,
 * inventory storage, and world storage in their existing classes.
 */
public final class BlockInteraction {
    private static final float REACH = 6.0f;

    private final Inventory inventory;

    public BlockInteraction(Inventory inventory) {
        if (inventory == null) {
            throw new IllegalArgumentException("Inventory cannot be null");
        }
        this.inventory = inventory;
    }

    /**
     * Mines the targeted breakable block. If the block has a drop, mining
     * is refused unless the complete drop can fit in the inventory.
     */
    public void mineBlock(TerrainMesh terrain, Camera camera) {
        if (terrain == null || camera == null) {
            return;
        }

        RayHit hit = raycastFromCamera(terrain, camera);
        if (hit == null || hit.block == BlockType.AIR
                || !hit.block.isBreakable()) {
            return;
        }

        ItemType dropItem = hit.block.getDropItem();
        int dropCount = hit.block.getDropCount();

        // Do not destroy a block if its complete drop cannot be collected.
        // Blocks with no drop can still be mined with a full inventory.
        if (dropItem != null && dropCount > 0
                && !inventory.canAddItem(dropItem, dropCount)) {
            return;
        }

        if (terrain.setBlock(hit.x, hit.y, hit.z, BlockType.AIR)
                && dropItem != null && dropCount > 0) {
            inventory.addItem(dropItem, dropCount);
        }
    }

    /**
     * Places the selected hotbar item's block in the adjacent raycast cell.
     * Placement is rejected if the cell is occupied or overlaps the player.
     */
    public void placeBlock(
            TerrainMesh terrain,
            Camera camera,
            float playerX,
            float playerZ,
            float playerFeetY,
            float playerRadius,
            float playerHeight) {

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

        // Prevent placing a solid block inside the player's collision body.
        if (bx + 1 > playerX - playerRadius
                && bx < playerX + playerRadius
                && by + 1 > playerFeetY
                && by < playerFeetY + playerHeight
                && bz + 1 > playerZ - playerRadius
                && bz < playerZ + playerRadius) {
            return;
        }

        ItemType itemType = selected.getItemType();
        BlockType blockToPlace = itemType.getPlacedBlock();
        if (blockToPlace == null || blockToPlace == BlockType.AIR) {
            return;
        }

        if (terrain.setBlock(bx, by, bz, blockToPlace)) {
            inventory.removeItem(itemType, 1);
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
