
package com.wildforge.entity;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Owns entities that are currently active in the world.
 */
public final class EntityManager {

    private final List<Entity> entities = new ArrayList<>();
    private final List<Entity> pendingAdd = new ArrayList<>();
    private boolean updating;

    public void spawn(Entity entity) {
        if (entity == null) {
            throw new IllegalArgumentException(
                    "Cannot spawn a null entity"
            );
        }

        if (entity.isRemoved()) {
            throw new IllegalArgumentException(
                    "Cannot spawn an entity that was removed"
            );
        }

        if (updating) {
            pendingAdd.add(entity);
        } else {
            entities.add(entity);
        }
    }

    public List<Entity> getEntities() {
        return Collections.unmodifiableList(entities);
    }

    public int size() {
        return entities.size();
    }

    public void clear() {
        entities.clear();
        pendingAdd.clear();
    }

    public void update(float deltaTime) {
        update(deltaTime,entity->true);
    }

    /** Freeze entities outside the active simulation radius without despawning them. */
    public void update(float deltaTime, java.util.function.Predicate<Entity> shouldSimulate) {
        java.util.Objects.requireNonNull(shouldSimulate,"shouldSimulate");
        if (!Float.isFinite(deltaTime) || deltaTime <= 0.0f) {
            return;
        }

        // Keep simulation steps bounded.
        float dt = Math.min(deltaTime, 0.05f);

        updating = true;
        try {
            for (Entity entity : entities) {
                if (!entity.isRemoved() && shouldSimulate.test(entity)) {
                    entity.update(dt);
                }
            }
        } finally {
            updating = false;
        }

        entities.removeIf(Entity::isRemoved);

        for (Entity entity : pendingAdd) {
            if (!entity.isRemoved()) {
                entities.add(entity);
            }
        }

        pendingAdd.clear();
    }

    public void removeRemoved() {
        entities.removeIf(Entity::isRemoved);
        pendingAdd.removeIf(Entity::isRemoved);
    }
}
