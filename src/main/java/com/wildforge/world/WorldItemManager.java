
package com.wildforge.world;

import com.wildforge.item.ItemType;
import com.wildforge.player.Player;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

/**
 * Owns physical item drops for a world.
 */
public final class WorldItemManager {
    private static final float PICKUP_DISTANCE = 1.5f;

    private final List<WorldItem> items = new ArrayList<>();

    public void spawn(
            ItemType itemType,
            int count,
            float x,
            float y,
            float z
    ) {
        if (itemType == null || count <= 0) {
            return;
        }

        int remaining = count;
        int maxStack = itemType.getMaxStackSize();

        while (remaining > 0) {
            int stackCount = Math.min(remaining, maxStack);
            items.add(new WorldItem(itemType, stackCount, x, y, z));
            remaining -= stackCount;
        }
    }

    public List<WorldItem> getItems() {
        return Collections.unmodifiableList(items);
    }

    public int size() {
        return items.size();
    }

    public void clear() {
        items.clear();
    }

    /**
     * Advances drop animation and collects nearby stacks when the
     * player's inventory can accept the entire stack.
     */
    public void update(float deltaTime, Player player) {
        if (deltaTime <= 0.0f || player == null) {
            return;
        }

        for (WorldItem item : items) {
            item.update(deltaTime);
        }

        float playerCenterY = player.getFeetY() + 0.8f;
        float pickupDistanceSquared =
                PICKUP_DISTANCE * PICKUP_DISTANCE;

        Iterator<WorldItem> iterator = items.iterator();
        while (iterator.hasNext()) {
            WorldItem item = iterator.next();

            float dx = item.getX() - player.getX();
            float dy = item.getY() - playerCenterY;
            float dz = item.getZ() - player.getZ();

            float distanceSquared =
                    dx * dx + dy * dy + dz * dz;

            if (distanceSquared > pickupDistanceSquared) {
                continue;
            }

            ItemType type = item.getItemType();
            int count = item.getCount();

            // Never delete a drop unless the entire stack was added.
            if (player.canAddItem(type, count)
                    && player.addItem(type, count)) {
                iterator.remove();
            }
        }
    }
}
