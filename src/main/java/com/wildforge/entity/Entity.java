
package com.wildforge.entity;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Base class for entities that exist in the world.
 */
public abstract class Entity {

    private static final AtomicLong NEXT_ID = new AtomicLong(1L);

    private final long id = NEXT_ID.getAndIncrement();

    protected float x;
    protected float y;
    protected float z;

    protected float velocityX;
    protected float velocityY;
    protected float velocityZ;

    protected float yaw;
    protected float pitch;
    protected float age;

    private boolean removed;

    protected Entity(float x, float y, float z) {
        setPosition(x, y, z);
    }

    public final long getId() {
        return id;
    }

    public final float getX() {
        return x;
    }

    public final float getY() {
        return y;
    }

    public final float getZ() {
        return z;
    }

    public final float getVelocityX() {
        return velocityX;
    }

    public final float getVelocityY() {
        return velocityY;
    }

    public final float getVelocityZ() {
        return velocityZ;
    }

    public final float getYaw() {
        return yaw;
    }

    public final float getPitch() {
        return pitch;
    }

    public final float getAge() {
        return age;
    }

    public final boolean isRemoved() {
        return removed;
    }

    public final void setPosition(float x, float y, float z) {
        if (!Float.isFinite(x)
                || !Float.isFinite(y)
                || !Float.isFinite(z)) {
            throw new IllegalArgumentException(
                    "Entity position must be finite"
            );
        }

        this.x = x;
        this.y = y;
        this.z = z;
    }

    public final void setVelocity(
            float velocityX,
            float velocityY,
            float velocityZ
    ) {
        if (!Float.isFinite(velocityX)
                || !Float.isFinite(velocityY)
                || !Float.isFinite(velocityZ)) {
            throw new IllegalArgumentException(
                    "Entity velocity must be finite"
            );
        }

        this.velocityX = velocityX;
        this.velocityY = velocityY;
        this.velocityZ = velocityZ;
    }

    public final void setRotation(float yaw, float pitch) {
        if (!Float.isFinite(yaw) || !Float.isFinite(pitch)) {
            throw new IllegalArgumentException(
                    "Entity rotation must be finite"
            );
        }

        this.yaw = yaw;
        this.pitch = pitch;
    }

    public final void remove() {
        removed = true;
    }

    /**
     * Called once per simulation update.
     * Specialized entities implement their own behavior.
     */
    public abstract void update(float deltaTime);
}
