
package com.wildforge.world;

import com.wildforge.entity.DroppedItemEntity;
import com.wildforge.entity.Entity;
import com.wildforge.entity.EntityManager;
import com.wildforge.item.ItemType;
import com.wildforge.player.Player;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * World-facing API for spawning, updating, merging, and collecting drops.
 * EntityManager owns entity lifecycle and removal.
 */
public final class WorldItemManager {

    private static final float PICKUP_HORIZONTAL_DISTANCE = 1.0f;
    private static final float PICKUP_VERTICAL_DISTANCE = 1.25f;

    private static final float MERGE_HORIZONTAL_DISTANCE = 0.65f;
    private static final float MERGE_VERTICAL_DISTANCE = 0.5f;

    private final EntityManager entityManager = new EntityManager();

    public void spawn(
            ItemType itemType,
            int count,
            float x,
            float y,
            float z,
            TerrainMesh terrain
    ) {
        if (itemType == null || count <= 0 || terrain == null) {
            return;
        }

        ThreadLocalRandom random = ThreadLocalRandom.current();
        int remaining = count;
        int maxStack = itemType.getMaxStackSize();

        while (remaining > 0) {
            int stackCount = Math.min(remaining, maxStack);

            float spawnX = x + random.nextFloat(-0.25f, 0.25f);
            float spawnY = y + random.nextFloat(0.0f, 0.2f);
            float spawnZ = z + random.nextFloat(-0.25f, 0.25f);

            DroppedItemEntity item = new DroppedItemEntity(
                    itemType,
                    stackCount,
                    spawnX,
                    spawnY,
                    spawnZ,
                    terrain
            );

            entityManager.spawn(item);
            remaining -= stackCount;
        }
    }

    public List<DroppedItemEntity> getItems() {
        List<DroppedItemEntity> result = new ArrayList<>();

        for (Entity entity : entityManager.getEntities()) {
            if (entity instanceof DroppedItemEntity
                    && !entity.isRemoved()) {
                result.add((DroppedItemEntity) entity);
            }
        }

        return Collections.unmodifiableList(result);
    }

    public int size() {
        return entityManager.size();
    }

    public void clear() {
        entityManager.clear();
    }

    /**
     * Resolves drops that overlap a newly placed block.
     */
    public void onBlockPlaced(int blockX, int blockY, int blockZ) {
        for (DroppedItemEntity item : getItems()) {
            item.onBlockPlaced(blockX, blockY, blockZ);
        }
    }

    public void update(float deltaTime, Player player) {
        if (!Float.isFinite(deltaTime) || deltaTime <= 0.0f) {
            return;
        }

        entityManager.removeRemoved();

        // Physics first, then merge stacks that have settled close together.
        entityManager.update(deltaTime);
        mergeNearbyStacks();

        if (player != null) {
            collectNearbyItems(player);
        }

        // Clean up stacks collected or emptied during merging.
        entityManager.removeRemoved();
    }

    private void mergeNearbyStacks() {
        List<DroppedItemEntity> items = getItems();

        float horizontalDistanceSquared =
                MERGE_HORIZONTAL_DISTANCE * MERGE_HORIZONTAL_DISTANCE;

        for (int i = 0; i < items.size(); i++) {
            DroppedItemEntity first = items.get(i);

            if (first.isRemoved()
                    || first.getCount() >= first.getItemType().getMaxStackSize()) {
                continue;
            }

            for (int j = i + 1; j < items.size(); j++) {
                DroppedItemEntity second = items.get(j);

                if (second.isRemoved()
                        || first.getItemType() != second.getItemType()) {
                    continue;
                }

                float dx = first.getX() - second.getX();
                float dy = first.getY() - second.getY();
                float dz = first.getZ() - second.getZ();

                if (dx * dx + dz * dz > horizontalDistanceSquared
                        || Math.abs(dy) > MERGE_VERTICAL_DISTANCE) {
                    continue;
                }

                int remainder = first.addItems(second.getCount());
                int transferred = second.getCount() - remainder;

                if (transferred > 0) {
                    second.removeItems(transferred);
                }

                if (first.getCount()
                        >= first.getItemType().getMaxStackSize()) {
                    break;
                }
            }
        }
    }

    private void collectNearbyItems(Player player) {
        float playerCenterY = player.getFeetY() + 0.9f;
        float horizontalDistanceSquared =
                PICKUP_HORIZONTAL_DISTANCE * PICKUP_HORIZONTAL_DISTANCE;

        for (Entity entity : entityManager.getEntities()) {
            if (!(entity instanceof DroppedItemEntity)
                    || entity.isRemoved()) {
                continue;
            }

            DroppedItemEntity item = (DroppedItemEntity) entity;

            float dx = item.getX() - player.getX();
            float dz = item.getZ() - player.getZ();
            float dy = Math.abs(item.getY() - playerCenterY);

            if (dx * dx + dz * dz > horizontalDistanceSquared
                    || dy > PICKUP_VERTICAL_DISTANCE) {
                continue;
            }

            if (player.canAddItem(item.getItemType(), item.getCount())
                    && player.addItem(
                            item.getItemType(),
                            item.getCount())) {
                item.remove();
            }
        }
    }
}
