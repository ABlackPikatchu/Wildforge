
package com.wildforge.world;

import com.wildforge.entity.DroppedItemEntity;
import com.wildforge.entity.Entity;
import com.wildforge.entity.EntityManager;
import com.wildforge.item.ItemType;
import com.wildforge.player.Player;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

/**
 * World-facing API for spawning, updating, and collecting dropped items.
 * The EntityManager owns their lifecycle.
 */
public final class WorldItemManager {

    private static final float PICKUP_HORIZONTAL_DISTANCE = 2.0f;
    private static final float PICKUP_VERTICAL_DISTANCE = 2.5f;

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

        int remaining = count;
        int maxStack = itemType.getMaxStackSize();

        while (remaining > 0) {
            int stackCount = Math.min(remaining, maxStack);

            entityManager.spawn(new DroppedItemEntity(
                    itemType,
                    stackCount,
                    x,
                    y,
                    z,
                    terrain
            ));

            remaining -= stackCount;
        }
    }

    public List<DroppedItemEntity> getItems() {
        List<DroppedItemEntity> result = new ArrayList<>();

        for (Entity entity : entityManager.getEntities()) {
            if (entity instanceof DroppedItemEntity) {
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

    public void update(float deltaTime, Player player) {
        if (deltaTime <= 0.0f || player == null) {
            return;
        }

        entityManager.removeRemoved();

        float playerCenterY = player.getFeetY() + 0.9f;
        float horizontalDistanceSquared =
                PICKUP_HORIZONTAL_DISTANCE * PICKUP_HORIZONTAL_DISTANCE;

        Iterator<Entity> iterator =
                entityManager.getEntities().iterator();

        // EntityManager exposes a read-only list. Mark collected entities
        // for removal; EntityManager cleans them up on its next update.
        while (iterator.hasNext()) {
            Entity entity = iterator.next();

            if (!(entity instanceof DroppedItemEntity)) {
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

        // A second update flushes removals safely after pickup.
        entityManager.update(deltaTime);
    }
}
