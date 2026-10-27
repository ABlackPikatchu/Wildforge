
package com.wildforge.entity;

import com.wildforge.item.ItemType;
import com.wildforge.world.TerrainMesh;

import java.util.concurrent.ThreadLocalRandom;

/**
 * A physical stack of items dropped into the world.
 */
public final class DroppedItemEntity extends Entity {

    private static final float HALF_SIZE = 0.16f;
    private static final float GRAVITY = 18.0f;
    private static final float BOUNCE_DAMPING = 0.28f;
    private static final float MIN_BOUNCE_SPEED = 0.8f;
    private static final float GROUND_BOB_AMOUNT = 0.045f;
    private static final float HORIZONTAL_FRICTION = 0.04f;

    private final ItemType itemType;
    private final TerrainMesh terrain;
    private final float bobPhase;

    private int count;
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
        this.bobPhase = ThreadLocalRandom.current()
                .nextFloat(0.0f, (float) (Math.PI * 2.0));

        // Give each dropped stack a slightly different initial toss.
        ThreadLocalRandom random = ThreadLocalRandom.current();
        setVelocity(
                random.nextFloat(-1.2f, 1.2f),
                random.nextFloat(1.0f, 2.2f),
                random.nextFloat(-1.2f, 1.2f)
        );
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
     * Adds items without exceeding this item's maximum stack size.
     *
     * @return the number of items that could not fit
     */
    public int addItems(int amount) {
        if (amount <= 0) {
            return amount;
        }

        int space = itemType.getMaxStackSize() - count;
        int added = Math.min(space, amount);
        count += added;

        return amount - added;
    }

    /**
     * Removes items from this stack.
     *
     * @return the number of items actually removed
     */
    public int removeItems(int amount) {
        if (amount <= 0 || isRemoved()) {
            return 0;
        }

        int removed = Math.min(count, amount);
        count -= removed;

        if (count == 0) {
            remove();
        }

        return removed;
    }

    /**
     * Render-only bobbing; physical collision uses the real position.
     */
    public float getRenderY() {
        if (!grounded) {
            return y;
        }

        return y + (float) Math.sin(age * 3.0f + bobPhase)
                * GROUND_BOB_AMOUNT;
    }

    @Override
    public void update(float deltaTime) {
        if (!Float.isFinite(deltaTime) || deltaTime <= 0.0f || isRemoved()) {
            return;
        }

        // Avoid huge simulation steps after pauses or frame hitches.
        float dt = Math.min(deltaTime, 0.05f);
        age += dt;

        velocityY -= GRAVITY * dt;

        moveHorizontal(dt);
        moveVertical(dt);
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

        if (grounded) {
            float friction = (float) Math.pow(
                    HORIZONTAL_FRICTION, deltaTime
            );
            velocityX *= friction;
            velocityZ *= friction;
        }
    }

    private void moveVertical(float deltaTime) {
        float oldY = y;
        float nextY = y + velocityY * deltaTime;

        if (!collidesAt(x, nextY, z)) {
            y = nextY;
            grounded = false;
            return;
        }

        if (velocityY <= 0.0f) {
            // Find the highest solid block top crossed while falling.
            float oldBottom = oldY - HALF_SIZE;
            float nextBottom = nextY - HALF_SIZE;
            float highestFloor = Float.NEGATIVE_INFINITY;

            int minX = (int) Math.floor(x - HALF_SIZE);
            int maxX = (int) Math.floor(x + HALF_SIZE);
            int minZ = (int) Math.floor(z - HALF_SIZE);
            int maxZ = (int) Math.floor(z + HALF_SIZE);
            int minY = (int) Math.floor(nextBottom);
            int maxY = (int) Math.floor(oldBottom);

            for (int bx = minX; bx <= maxX; bx++) {
                for (int by = minY; by <= maxY; by++) {
                    for (int bz = minZ; bz <= maxZ; bz++) {
                        if (!terrain.getBlockAt(bx, by, bz).isSolid()) {
                            continue;
                        }

                        float blockTop = by + 1.0f;

                        if (oldBottom >= blockTop - 0.0001f
                                && nextBottom <= blockTop
                                && blockTop > highestFloor) {
                            highestFloor = blockTop;
                        }
                    }
                }
            }

            if (highestFloor != Float.NEGATIVE_INFINITY) {
                y = highestFloor + HALF_SIZE;

                float impactSpeed = -velocityY;
                if (impactSpeed >= MIN_BOUNCE_SPEED) {
                    velocityY = impactSpeed * BOUNCE_DAMPING;
                    grounded = false;
                } else {
                    velocityY = 0.0f;
                    grounded = true;
                }

                return;
            }
        } else {
            // Find the lowest ceiling underside crossed while rising.
            float oldTop = oldY + HALF_SIZE;
            float nextTop = nextY + HALF_SIZE;
            float lowestCeiling = Float.POSITIVE_INFINITY;

            int minX = (int) Math.floor(x - HALF_SIZE);
            int maxX = (int) Math.floor(x + HALF_SIZE);
            int minZ = (int) Math.floor(z - HALF_SIZE);
            int maxZ = (int) Math.floor(z + HALF_SIZE);
            int minY = (int) Math.floor(oldTop);
            int maxY = (int) Math.floor(nextTop);

            for (int bx = minX; bx <= maxX; bx++) {
                for (int by = minY; by <= maxY; by++) {
                    for (int bz = minZ; bz <= maxZ; bz++) {
                        if (!terrain.getBlockAt(bx, by, bz).isSolid()) {
                            continue;
                        }

                        float blockBottom = by;

                        if (oldTop <= blockBottom + 0.0001f
                                && nextTop >= blockBottom
                                && blockBottom < lowestCeiling) {
                            lowestCeiling = blockBottom;
                        }
                    }
                }
            }

            if (lowestCeiling != Float.POSITIVE_INFINITY) {
                y = lowestCeiling - HALF_SIZE;
                velocityY = 0.0f;
                grounded = false;
                return;
            }
        }

        // Defensive fallback for overlaps or unusual terrain geometry.
        velocityY = 0.0f;
        grounded = false;
    }

    /**
     * Moves an item out of a newly placed solid block if they overlap.
     */
    public void onBlockPlaced(int blockX, int blockY, int blockZ) {
        if (isRemoved()) {
            return;
        }

        if (!overlapsBlockAt(x, y, z, blockX, blockY, blockZ)) {
            return;
        }

        float[][] candidates = {
                {x, blockY + 1.0f + HALF_SIZE, z},
                {x, blockY - HALF_SIZE, z},
                {blockX - HALF_SIZE, y, z},
                {blockX + 1.0f + HALF_SIZE, y, z},
                {x, y, blockZ - HALF_SIZE},
                {x, y, blockZ + 1.0f + HALF_SIZE}
        };

        float bestX = x;
        float bestY = y;
        float bestZ = z;
        float bestDistance = Float.POSITIVE_INFINITY;
        boolean foundPosition = false;

        for (float[] candidate : candidates) {
            if (collidesAt(candidate[0], candidate[1], candidate[2])) {
                continue;
            }

            float dx = candidate[0] - x;
            float dy = candidate[1] - y;
            float dz = candidate[2] - z;
            float distance = dx * dx + dy * dy + dz * dz;

            if (distance < bestDistance) {
                bestDistance = distance;
                bestX = candidate[0];
                bestY = candidate[1];
                bestZ = candidate[2];
                foundPosition = true;
            }
        }

        // If the immediate faces are blocked, look for space above.
        if (!foundPosition) {
            for (float offset = 0.25f; offset <= 4.0f; offset += 0.25f) {
                float candidateY =
                        blockY + 1.0f + HALF_SIZE + offset;

                if (!collidesAt(x, candidateY, z)) {
                    bestX = x;
                    bestY = candidateY;
                    bestZ = z;
                    foundPosition = true;
                    break;
                }
            }
        }

        if (foundPosition) {
            setPosition(bestX, bestY, bestZ);
            velocityY = Math.max(0.0f, velocityY);
            grounded = false;
        }
    }

    private boolean overlapsBlockAt(
            float centerX,
            float centerY,
            float centerZ,
            int blockX,
            int blockY,
            int blockZ
    ) {
        return centerX + HALF_SIZE > blockX
                && centerX - HALF_SIZE < blockX + 1.0f
                && centerY + HALF_SIZE > blockY
                && centerY - HALF_SIZE < blockY + 1.0f
                && centerZ + HALF_SIZE > blockZ
                && centerZ - HALF_SIZE < blockZ + 1.0f;
    }

    private boolean collidesAt(
            float centerX,
            float centerY,
            float centerZ
    ) {
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
                            && overlapsBlockAt(
                                    centerX, centerY, centerZ,
                                    bx, by, bz)) {
                        return true;
                    }
                }
            }
        }

        return false;
    }
}
