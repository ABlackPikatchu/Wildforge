
package com.wildforge.world;

import com.wildforge.entity.DroppedItemEntity;
import com.wildforge.core.save.DroppedItemStorage;
import com.wildforge.entity.Entity;
import com.wildforge.entity.EntityManager;
import com.wildforge.item.ItemType;
import com.wildforge.content.ItemDefinition;
import com.wildforge.content.ItemCatalog;
import com.wildforge.player.Player;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

public final class WorldItemManager {

    private static final float MAGNET_DISTANCE = 1.0f;
    private static final float PICKUP_HORIZONTAL_DISTANCE = 0.5f;
    private static final float PICKUP_VERTICAL_DISTANCE = 1.0f;

    private static final float MERGE_HORIZONTAL_DISTANCE = 0.65f;
    private static final float MERGE_VERTICAL_DISTANCE = 0.5f;

    private final EntityManager entityManager = new EntityManager();

    public void spawn(ItemType type,int count,float x,float y,float z,TerrainMesh terrain) {
        if(type!=null)spawn(ItemCatalog.legacy(type),count,x,y,z,terrain);
    }

    public void spawn(
            ItemDefinition itemType,
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

            entityManager.spawn(new DroppedItemEntity(
                    itemType,
                    stackCount,
                    spawnX,
                    spawnY,
                    spawnZ,
                    terrain
            ));

            remaining -= stackCount;
        }
    }

    /** Used only by validated save decoding; spawn() remains randomized for gameplay. */
    public void restore(DroppedItemStorage.Stack stack, TerrainMesh terrain) {
        ItemDefinition type = ItemCatalog.require(stack.id());
        DroppedItemEntity entity = new DroppedItemEntity(type,stack.count(),
                stack.x(),stack.y(),stack.z(),terrain);
        entity.restoreMotion(stack.vx(),stack.vy(),stack.vz(),stack.age());
        entityManager.spawn(entity);
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
        return getItems().size();
    }

    public void clear() {
        entityManager.clear();
    }

    public void onBlockPlaced(int blockX, int blockY, int blockZ) {
        for (DroppedItemEntity item : getItems()) {
            item.onBlockPlaced(blockX, blockY, blockZ);
        }
    }

    public void update(float deltaTime, Player player) {
        update(deltaTime,player,null);
    }

    /** Update only nearby items; distant drops remain present but their physics freezes. */
    public void update(float deltaTime, Player player, TerrainMesh terrain) {
        if (!Float.isFinite(deltaTime) || deltaTime <= 0.0f) {
            return;
        }

        float dt = Math.min(deltaTime, 0.05f);
        entityManager.removeRemoved();

        // Attract items before their physics update.
        if (player != null && player.getGameMode() != com.wildforge.player.GameMode.SPECTATOR) {
            float magnetDistanceSquared =
                    MAGNET_DISTANCE * MAGNET_DISTANCE;

            for (DroppedItemEntity item : getItems()) {
                float dx = player.getX() - item.getX();
                float dz = player.getZ() - item.getZ();

                if (dx * dx + dz * dz <= magnetDistanceSquared) {
                    item.attractToward(player.getX(), player.getZ(), dt);
                }
            }
        }

        // Physics runs even if no player reference is available.
        entityManager.update(dt,entity->terrain==null ||
                terrain.isWithinSimulationDistance(entity.getX(),entity.getZ()));

        mergeNearbyStacks(terrain);

        if (player != null && player.getGameMode() != com.wildforge.player.GameMode.SPECTATOR) {
            collectNearbyItems(player);
        }

        entityManager.removeRemoved();
    }

    private void mergeNearbyStacks(TerrainMesh terrain) {
        List<DroppedItemEntity> items = getItems();
        float horizontalDistanceSquared =
                MERGE_HORIZONTAL_DISTANCE * MERGE_HORIZONTAL_DISTANCE;

        for (int i = 0; i < items.size(); i++) {
            DroppedItemEntity first = items.get(i);
            if(terrain!=null && !terrain.isWithinSimulationDistance(first.getX(),first.getZ()))continue;

            if (first.isRemoved()
                    || first.getCount() >= first.maxStackSize()) {
                continue;
            }

            for (int j = i + 1; j < items.size(); j++) {
                DroppedItemEntity second = items.get(j);
                if(terrain!=null && !terrain.isWithinSimulationDistance(second.getX(),second.getZ()))continue;

                if (second.isRemoved()
                        || !first.itemId().equals(second.itemId())) {
                    continue;
                }

                float dx = first.getX() - second.getX();
                float dy = first.getY() - second.getY();
                float dz = first.getZ() - second.getZ();

                if (dx * dx + dz * dz > horizontalDistanceSquared
                        || Math.abs(dy) > MERGE_VERTICAL_DISTANCE) {
                    continue;
                }

                int originalSecondCount = second.getCount();
                int remainder = first.addItems(originalSecondCount);
                int transferred = originalSecondCount - remainder;

                if (transferred > 0) {
                    second.removeItems(transferred);
                }

                if (first.getCount()
                        >= first.maxStackSize()) {
                    break;
                }
            }
        }
    }

    private void collectNearbyItems(Player player) {
        float playerCenterY = player.getFeetY() + 0.9f;
        float horizontalDistanceSquared =
                PICKUP_HORIZONTAL_DISTANCE * PICKUP_HORIZONTAL_DISTANCE;

        for (DroppedItemEntity item : getItems()) {
            if (item.isRemoved()) {
                continue;
            }

            float dx = item.getX() - player.getX();
            float dz = item.getZ() - player.getZ();
            float dy = Math.abs(item.getY() - playerCenterY);

            if (dx * dx + dz * dz > horizontalDistanceSquared
                    || dy > PICKUP_VERTICAL_DISTANCE) {
                continue;
            }

            if (player.canAddItem(item.definition(), item.getCount())
                    && player.addItem(item.definition(), item.getCount())) {
                item.remove();
            }
        }
    }
}
