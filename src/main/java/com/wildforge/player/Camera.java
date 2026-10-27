
package com.wildforge.player;

import org.joml.Matrix4f;
import org.joml.Vector3f;

public final class Camera {
    private final Vector3f position = new Vector3f();
    private float yaw = -90.0f;
    private float pitch = 0.0f;

    public Vector3f getPosition() {
        return position;
    }

    public void setPosition(float x, float y, float z) {
        position.set(x, y, z);
    }

    public void rotate(float deltaX, float deltaY) {
        yaw += deltaX;
        pitch -= deltaY;
        pitch = Math.max(-89.0f, Math.min(89.0f, pitch));
    }

    public Vector3f getLookDirection() {
        float yawRadians = (float) Math.toRadians(yaw);
        float pitchRadians = (float) Math.toRadians(pitch);

        return new Vector3f(
            (float) (Math.cos(pitchRadians) * Math.sin(yawRadians)),
            (float) Math.sin(pitchRadians),
            (float) (-Math.cos(pitchRadians) * Math.cos(yawRadians))
        ).normalize();
    }

    public Matrix4f getViewMatrix() {
        Vector3f direction = getLookDirection();

        return new Matrix4f().lookAt(
            position,
            new Vector3f(position).add(direction),
            new Vector3f(0.0f, 1.0f, 0.0f)
        );
    }

    public float getYaw() {
        return yaw;
    }

    public float getPitch() {
        return pitch;
    }

}
