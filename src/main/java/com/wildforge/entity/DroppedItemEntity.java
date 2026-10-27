
package com.wildforge.entity;

import com.wildforge.item.ItemType;
import com.wildforge.world.TerrainMesh;

/**
 * A physical stack of items dropped into the world.
 */
public final class DroppedItemEntity extends Entity {

    private static final float HALF_SIZE = 0.16f;
    private static final float GRAVITY = 18.0f;
    private static final float BOUNCE_DAMPING = 0.28f;
    private static final float MIN_BOUNCE_SPEED = 0.8f;

    private final ItemType itemType;
    private final int count;
    private final TerrainMesh terrain;

    private boolean grounded;

    public DroppedItemEntity(
            ItemType itemType,
            int count,
            float x,
            float y,
            float z,
            TerrainMesh terrain
    ) {
        super(x, y, z);

        if (itemType == null) {
            throw new IllegalArgumentException("Item type cannot be null");
        }
        if (count < 1 || count > itemType.getMaxStackSize()) {
            throw new IllegalArgumentException("Invalid dropped stack count");
        }
        if (terrain == null) {
            throw new IllegalArgumentException("Terrain cannot be null");
        }

        this.itemType = itemType;
        this.count = count;
        this.terrain = terrain;
    }

    public ItemType getItemType() {
        return itemType;
    }

    public int getCount() {
        return count;
    }

    public boolean isGrounded() {
        return grounded;
    }

    /**
     * Render-only bobbing. It does not change the physical position.
     */
    public float getRenderY() {
        if (!grounded) {
            return y;
        }

        return y + (float) Math.sin(age * 3.0f) * 0.045f;
    }

    @Override
    public void update(float deltaTime) {
        if (deltaTime <= 0.0f || isRemoved()) {
            return;
        }

        age += deltaTime;

        velocityY -= GRAVITY * deltaTime;

        moveHorizontal(deltaTime);
        moveVertical(deltaTime);
    }

    private void moveHorizontal(float deltaTime) {
        float nextX = x + velocityX * deltaTime;

        if (!collidesAt(nextX, y, z)) {
            x = nextX;
        } else {
            velocityX = 0.0f;
        }

        float nextZ = z + velocityZ * deltaTime;

        if (!collidesAt(x, y, nextZ)) {
            z = nextZ;
        } else {
            velocityZ = 0.0f;
        }

        // Ground friction gradually stops any initial horizontal motion.
        if (grounded) {
            float friction = (float) Math.pow(0.04, deltaTime);
            velocityX *= friction;
            velocityZ *= friction;
        }
    }

    private void moveVertical(float deltaTime) {
        float nextY = y + velocityY * deltaTime;

        if (!collidesAt(x, nextY, z)) {
            y = nextY;
            grounded = false;
            return;
        }

        if (velocityY < 0.0f) {
            // Find the top of the solid block immediately below the item.
            int blockX = (int) Math.floor(x);
            int blockZ = (int) Math.floor(z);
            int blockY = (int) Math.floor(nextY - HALF_SIZE);

            float floorTop = blockY + 1.0f;

            if (terrain.getBlockAt(blockX, blockY, blockZ).isSolid()) {
                y = floorTop + HALF_SIZE;
                grounded = true;

                float impactSpeed = -velocityY;
                if (impactSpeed >= MIN_BOUNCE_SPEED) {
                    velocityY = impactSpeed * BOUNCE_DAMPING;
                    grounded = false;
                } else {
                    velocityY = 0.0f;
                }

                return;
            }
        }

        // Collision with a ceiling or another solid surface.
        velocityY = 0.0f;
    }

    private boolean collidesAt(float centerX, float centerY, float centerZ) {
        float minX = centerX - HALF_SIZE;
        float maxX = centerX + HALF_SIZE;
        float minY = centerY - HALF_SIZE;
        float maxY = centerY + HALF_SIZE;
        float minZ = centerZ - HALF_SIZE;
        float maxZ = centerZ + HALF_SIZE;

        for (int bx = (int) Math.floor(minX);
             bx <= (int) Math.floor(maxX); bx++) {
            for (int by = (int) Math.floor(minY);
                 by <= (int) Math.floor(maxY); by++) {
                for (int bz = (int) Math.floor(minZ);
                     bz <= (int) Math.floor(maxZ); bz++) {

                    if (terrain.getBlockAt(bx, by, bz).isSolid()
                            && overlaps(
                                    minX, maxX,
                                    minY, maxY,
                                    minZ, maxZ,
                                    bx, by, bz)) {
                        return true;
                    }
                }
            }
        }

        return false;
    }

    private boolean overlaps(
            float minX, float maxX,
            float minY, float maxY,
            float minZ, float maxZ,
            int bx, int by, int bz
    ) {
        return maxX > bx && minX < bx + 1.0f
                && maxY > by && minY < by + 1.0f
                && maxZ > bz && minZ < bz + 1.0f;
    }
}
