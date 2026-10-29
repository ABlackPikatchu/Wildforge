package com.wildforge.player;

import com.wildforge.content.ContentLoader;
import com.wildforge.core.KeyBindings;
import com.wildforge.item.ItemStack;
import com.wildforge.inventory.Inventory;
import com.wildforge.item.ItemType;
import com.wildforge.world.BlockType;
import com.wildforge.world.TerrainMesh;
import com.wildforge.world.WorldItemManager;

import org.joml.Vector3f;
import org.lwjgl.glfw.GLFW;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

public final class Player {

public static final int HOTBAR_SLOTS = Inventory.HOTBAR_SLOTS;
public static final int HOTBAR_START_INDEX = Inventory.HOTBAR_START_INDEX;
public static final int MAIN_INVENTORY_SLOTS = Inventory.MAIN_INVENTORY_SLOTS;
public static final int TOTAL_INVENTORY_SLOTS = Inventory.TOTAL_SLOTS;

private static final int PLAYER_SAVE_MAGIC = 0x5746504C;
private static final int PLAYER_SAVE_VERSION = 2;

private static final float WALK_SPEED = 5.0f;
private static final float JUMP_SPEED = 7.0f;
private static final float GRAVITY = 20.0f;
private static final float EYE_HEIGHT = 1.65f;
private static final float PLAYER_HEIGHT = 1.80f;
private static final float PLAYER_RADIUS = 0.30f;
private static final float MAX_STEP_HEIGHT = 1.01f;
private static final float MAX_STEP_DOWN_HEIGHT = 0.05f;
private float mouseSensitivity = 0.12f;
private KeyBindings keyBindings = new KeyBindings();

public void setKeyBindings(KeyBindings bindings) {
    if (bindings == null) throw new IllegalArgumentException("Keybindings are required");
    keyBindings = bindings;
}

public void setMouseSensitivity(float sensitivity) {
    if (!Float.isFinite(sensitivity) || sensitivity <= 0f || sensitivity > 1f) {
        throw new IllegalArgumentException("Invalid mouse sensitivity: " + sensitivity);
    }
    mouseSensitivity = sensitivity;
}

private static final float STEP_UP_SMOOTHING = 10.0f;
private static final float STEP_DOWN_SMOOTHING = 22.0f;

private static final float WALK_BOB_AMOUNT = 0.008f;
private static final float WALK_BOB_DISTANCE = 2.2f;

private final Camera camera = new Camera();

private final Inventory inventory = new Inventory();
private final WorldItemManager worldItems;
private final BlockInteraction blockInteraction;

private float x = 16.0f;
private float z = 27.0f;
private float feetY;

private float verticalVelocity = 0.0f;
private GameMode gameMode = GameMode.SURVIVAL;
private boolean flying;
public GameMode getGameMode() { return gameMode; }
public boolean isFlying() { return flying || gameMode == GameMode.SPECTATOR; }
public void setGameMode(GameMode next) {
    if (next == null) throw new IllegalArgumentException("Mode is required");
    gameMode = next;
    flying = next == GameMode.SPECTATOR || (next == GameMode.CREATIVE && flying);
    verticalVelocity = 0;
    grounded = false;
    updateCameraPosition();
}
public void setFlying(boolean enabled) {
    if (gameMode == GameMode.SURVIVAL && enabled) throw new IllegalStateException("Flying requires Creative or Spectator mode");
    flying = enabled || gameMode == GameMode.SPECTATOR;
    verticalVelocity = 0f;
    grounded = false;
}
/** Developer teleport; finite bounded coordinates prevent accidental invalid saves. */
public void teleport(float tx, float ty, float tz) {
    if (!Float.isFinite(tx) || !Float.isFinite(ty) || !Float.isFinite(tz) ||
        Math.abs(tx)>30_000_000f || Math.abs(tz)>30_000_000f ||
        ty<0f || ty+PLAYER_HEIGHT>TerrainMesh.WORLD_HEIGHT)
        throw new IllegalArgumentException("Position outside world bounds");
    x=tx; z=tz; feetY=ty; verticalVelocity=0f;
    grounded=false; stepCameraOffset=0; walkBobOffset=0;
    updateCameraPosition();
}

private boolean grounded = false;
private boolean jumpWasPressed = false;
private boolean flightJumpWasDown = false;
private long lastCreativeJumpTapNanos = 0;
/** Places a creative catalog item in the currently selected hotbar slot. */
public void creativeSelect(com.wildforge.item.ItemType type, int count) {
    if (gameMode != GameMode.CREATIVE || type == null) return;
    inventory.setStack(HOTBAR_START_INDEX + inventory.getSelectedHotbarSlot(),
        new ItemStack(type, Math.min(type.getMaxStackSize(), Math.max(1,count))));
}

private float swimExitGrace=0f;
private boolean inventoryOpen = false;
private boolean mouseCaptured = true;
private boolean inventoryKeyWasPressed = false;

// Camera-only offsets; these do not affect physical collision.
private float stepCameraOffset = 0.0f;
private float walkBobOffset = 0.0f;
private float walkBobPhase = 0.0f;

private double lastMouseX;
private double lastMouseY;
private boolean firstMouseMove = true;

private long windowHandle;

public Player(TerrainMesh terrain) {
    this(terrain, new WorldItemManager());
}

public Player(TerrainMesh terrain, WorldItemManager worldItems) {
    this(terrain, worldItems, null);
}

/** In-game constructor: provides the validated JSON content to block interactions. */
public Player(TerrainMesh terrain, WorldItemManager worldItems,
              ContentLoader.Content content) {
    if (terrain == null) {
        throw new IllegalArgumentException("Terrain cannot be null");
    }
    if (worldItems == null) {
        throw new IllegalArgumentException(
                "World item manager cannot be null");
    }

    this.worldItems = worldItems;
    this.blockInteraction = new BlockInteraction(inventory, worldItems, content);

    inventory.setStack(HOTBAR_START_INDEX,
            new ItemStack(ItemType.DIRT_BLOCK, 32));
    inventory.setStack(HOTBAR_START_INDEX + 1,
            new ItemStack(ItemType.STONE_BLOCK, 32));
    inventory.setStack(HOTBAR_START_INDEX + 2,
            new ItemStack(ItemType.GRASS_BLOCK, 16));

    if (terrain.initialSpawn() != null) {
        x = terrain.initialSpawn().x() + 0.5f;
        z = terrain.initialSpawn().z() + 0.5f;
    }
    feetY = terrain.getHeightAt(x, z);
    grounded = true;
    updateCameraPosition();
}

public float getX() {
    return x;
}

public float getZ() {
    return z;
}

public float getFeetY() {
    return feetY;
}

public WorldItemManager getWorldItems() {
    return worldItems;
}

public Camera getCamera() {
    return camera;
}

public boolean isMouseCaptured() {
    return mouseCaptured;
}

public boolean isInventoryOpen() {
    return inventoryOpen;
}

public int getSelectedSlot() {
    return inventory.getSelectedHotbarSlot();
}

public ItemStack getCursorStack() {
    return inventory.getCursorStack();
}

public ItemStack getInventoryStack(int slot) {
    return inventory.getStack(slot);
}

public ItemType getHotbarItem(int slot) {
    if (slot < 0 || slot >= HOTBAR_SLOTS) {
        return null;
    }

    ItemStack stack = getInventoryStack(HOTBAR_START_INDEX + slot);
    return stack == null ? null : stack.getItemType();
}

public ItemType getSelectedItem() {
    return getHotbarItem(inventory.getSelectedHotbarSlot());
}

public BlockType getSelectedBlock() {
    ItemType selectedItem = getSelectedItem();
    if (selectedItem == null || selectedItem.getPlacedBlock() == null) {
        return BlockType.AIR;
    }
    return selectedItem.getPlacedBlock();
}

public int getItemCount(ItemType itemType) {
    return inventory.getItemCount(itemType);
}

/** A held cursor stack is not serialized in version 1 player saves. */
public boolean hasCursorStack() {
    ItemStack stack = inventory.getCursorStack();
    return stack != null && !stack.isEmpty();
}

/** Saves the player's position, view direction, hotbar, and inventory. */
public void saveTo(Path path) throws IOException {
    if (hasCursorStack()) {
        throw new IOException("Held inventory items must be returned before saving");
    }
    if (path == null) {
        throw new IllegalArgumentException("Save path cannot be null");
    }

    Path absolutePath = path.toAbsolutePath();
    Files.createDirectories(absolutePath.getParent());
    Path temporaryPath = absolutePath.resolveSibling(
            absolutePath.getFileName().toString() + ".tmp");

    try {
        try (DataOutputStream output = new DataOutputStream(
                new BufferedOutputStream(
                        Files.newOutputStream(temporaryPath)))) {
            output.writeInt(PLAYER_SAVE_MAGIC);
            output.writeInt(PLAYER_SAVE_VERSION);
            output.writeFloat(x);
            output.writeFloat(z);
            output.writeFloat(feetY);
            output.writeFloat(verticalVelocity);
            output.writeFloat(camera.getYaw());
            output.writeFloat(camera.getPitch());
            output.writeInt(inventory.getSelectedHotbarSlot());

            for (int slot = 0; slot < TOTAL_INVENTORY_SLOTS; slot++) {
                ItemStack stack = inventory.getStack(slot);
                if (stack == null || stack.isEmpty()) {
                    // ID zero remains the empty-slot marker, matching
                    // existing player saves.
                    output.writeByte(0);
                    output.writeInt(0);
                } else {
                    output.writeByte(stack.getItemType().getId());
                    output.writeInt(stack.getCount());
                }
            }
            output.writeByte(gameMode.ordinal());
            output.writeBoolean(flying);
        }

        try {
            Files.move(
                    temporaryPath,
                    absolutePath,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE
            );
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(
                    temporaryPath,
                    absolutePath,
                    StandardCopyOption.REPLACE_EXISTING
            );
        }
    } finally {
        Files.deleteIfExists(temporaryPath);
    }
}

/** Loads player state while validating it against the current world. */
public void loadFrom(Path path, TerrainMesh terrain) throws IOException {
    if (path == null || terrain == null) {
        throw new IllegalArgumentException(
                "Save path and terrain are required");
    }

    float loadedX;
    float loadedZ;
    float loadedFeetY;
    float loadedVerticalVelocity;
    float loadedYaw;
    float loadedPitch;
    int loadedSelectedSlot;
    ItemStack[] loadedInventory = new ItemStack[TOTAL_INVENTORY_SLOTS];
    GameMode loadedMode = GameMode.SURVIVAL;
    boolean loadedFlying = false;

    try (DataInputStream input = new DataInputStream(
            new BufferedInputStream(Files.newInputStream(path)))) {
        if (input.readInt() != PLAYER_SAVE_MAGIC) {
            throw new IOException("Not a Wildforge player save");
        }

        int saveVersion = input.readInt();
        if (saveVersion != 1 && saveVersion != PLAYER_SAVE_VERSION) {
            throw new IOException(
                    "Unsupported player save version: " + saveVersion);
        }

        loadedX = input.readFloat();
        loadedZ = input.readFloat();
        loadedFeetY = input.readFloat();
        if (terrain.needsLegacyPlayerHeightShift()) loadedFeetY += 160.0f;
        loadedVerticalVelocity = input.readFloat();
        loadedYaw = input.readFloat();
        loadedPitch = input.readFloat();
        loadedSelectedSlot = input.readInt();

        if (!Float.isFinite(loadedX)
                || !Float.isFinite(loadedZ)
                || !Float.isFinite(loadedFeetY)
                || !Float.isFinite(loadedVerticalVelocity)
                || !Float.isFinite(loadedYaw)
                || !Float.isFinite(loadedPitch)
                                || loadedFeetY < 0.0f
                || loadedFeetY + PLAYER_HEIGHT > TerrainMesh.WORLD_HEIGHT
                || loadedSelectedSlot < 0
                || loadedSelectedSlot >= HOTBAR_SLOTS) {
            throw new IOException("Player save contains invalid values");
        }

        for (int slot = 0; slot < TOTAL_INVENTORY_SLOTS; slot++) {
            int typeId = input.readUnsignedByte();
            int count = input.readInt();

            if (typeId == 0) {
                if (count != 0) {
                    throw new IOException(
                            "Empty player slot has a nonzero count");
                }
                loadedInventory[slot] = null;
            } else {
                ItemType itemType = ItemType.fromId(typeId);
                if (itemType == null
                        || count < 1
                        || count > itemType.getMaxStackSize()) {
                    throw new IOException(
                            "Player save contains an invalid item stack");
                }
                loadedInventory[slot] =
                        new ItemStack(itemType, count);
            }
        }

        if (saveVersion >= 2) {
            int modeId = input.readUnsignedByte();
            if (modeId >= GameMode.values().length) throw new IOException("Invalid player game mode");
            loadedMode = GameMode.values()[modeId];
            loadedFlying = input.readBoolean();
        }
        if (input.read() != -1) {
            throw new IOException("Player save contains trailing data");
        }
    }

    inventory.setContents(loadedInventory);
    gameMode = loadedMode;
    flying = (loadedFlying && loadedMode == GameMode.CREATIVE) || loadedMode == GameMode.SPECTATOR;
    x = loadedX;
    z = loadedZ;
    feetY = loadedFeetY;
    verticalVelocity = loadedVerticalVelocity;
    inventory.setSelectedHotbarSlot(loadedSelectedSlot);
    camera.rotate(
            loadedYaw - camera.getYaw(),
            camera.getPitch() - loadedPitch
    );

    grounded = false;
    if (gameMode != GameMode.SPECTATOR && !isFlying() && !canOccupy(terrain, x, z, feetY)) {
        x = 16.0f;
        z = 27.0f;
        feetY = terrain.getHeightAt(x, z);
        verticalVelocity = 0.0f;
        grounded = true;
    } else {
        float ground = getFootprintGround(terrain, x, z);
        if (verticalVelocity <= 0.0f
                && Math.abs(feetY - ground) <= 0.06f) {
            feetY = ground;
            verticalVelocity = 0.0f;
            grounded = true;
        }
    }

    updateCameraPosition();
}

public void setInventoryOpen(boolean open) {
    if (!open) {
        returnCursorStackToInventory();
    }

    inventoryOpen = open;

    if (open) {
        releaseMouse();
    } else {
        captureMouse();
    }
}

public void toggleInventory() {
    setInventoryOpen(!inventoryOpen);
}

/**
 * Returns any stack held by the inventory cursor to the inventory.
 * If the inventory has no room, the cursor stack is left untouched.
 */
public void returnCursorStackToInventory() {
    inventory.returnCursorStackToInventory();
}

public void selectHotbarSlot(int slot) {
    inventory.selectHotbarSlot(slot);
}

public void scrollHotbar(double amount) {
    inventory.scrollHotbar(amount);
}

public void onMouseMove(double mouseX, double mouseY) {
    if (!mouseCaptured || inventoryOpen) {
        lastMouseX = mouseX;
        lastMouseY = mouseY;
        firstMouseMove = true;
        return;
    }

    if (firstMouseMove) {
        lastMouseX = mouseX;
        lastMouseY = mouseY;
        firstMouseMove = false;
        return;
    }

    float deltaX = (float) (mouseX - lastMouseX);
    float deltaY = (float) (mouseY - lastMouseY);

    lastMouseX = mouseX;
    lastMouseY = mouseY;

    camera.rotate(
            deltaX * mouseSensitivity,
            deltaY * mouseSensitivity
    );
}

public void moveMouse(float deltaX, float deltaY) {
    if (mouseCaptured && !inventoryOpen) {
        camera.rotate(
                deltaX * mouseSensitivity,
                deltaY * mouseSensitivity
        );
    }
}

public void captureMouse() {
    mouseCaptured = true;
    firstMouseMove = true;

    if (windowHandle != 0) {
        GLFW.glfwSetInputMode(
                windowHandle,
                GLFW.GLFW_CURSOR,
                GLFW.GLFW_CURSOR_DISABLED
        );
    }
}

public void releaseMouse() {
    mouseCaptured = false;
    firstMouseMove = true;

    if (windowHandle != 0) {
        GLFW.glfwSetInputMode(
                windowHandle,
                GLFW.GLFW_CURSOR,
                GLFW.GLFW_CURSOR_NORMAL
        );
    }
}

public void captureMouse(long window) {
    windowHandle = window;
    captureMouse();
}

public void releaseMouse(long window) {
    windowHandle = window;
    releaseMouse();
}

public void clickInventorySlot(int slot, boolean rightClick) {
    inventory.clickSlot(slot, rightClick);
}

public void clickInventorySlot(int slot, int mouseButton) {
    inventory.clickSlot(slot, mouseButton);
}

public boolean addItem(ItemType itemType, int amount) {
    return inventory.addItem(itemType, amount);
}

public boolean canAddItem(ItemType itemType) {
    return inventory.canAddItem(itemType);
}

public boolean canAddItem(ItemType itemType, int amount) {
    return inventory.canAddItem(itemType, amount);
}

public void mineBlock(TerrainMesh terrain) {
    if (gameMode != GameMode.SPECTATOR)
        blockInteraction.mineBlock(terrain, camera, gameMode == GameMode.CREATIVE);
}

public void placeBlock(TerrainMesh terrain) {
        if (gameMode == GameMode.SPECTATOR) return;
        blockInteraction.placeBlock(
            terrain,
            camera,
            x,
            z,
            feetY,
            PLAYER_RADIUS,
            PLAYER_HEIGHT, gameMode == GameMode.CREATIVE
    );
}

public void update(
        long window,
        TerrainMesh terrain,
        float deltaTime) {

    windowHandle = window;

    if (deltaTime <= 0.0f) {
        return;
    }

    boolean inventoryPressed =
            isKeyDown(window, keyBindings.get(KeyBindings.Action.INVENTORY));

    if (inventoryPressed && !inventoryKeyWasPressed &&
        !(gameMode==GameMode.CREATIVE && inventoryOpen)) {
        toggleInventory();
    }

    inventoryKeyWasPressed = inventoryPressed;

    for (int i = 0; i < HOTBAR_SLOTS; i++) {
        if (isKeyDown(window, keyBindings.get(KeyBindings.Action.at(KeyBindings.Action.HOTBAR_1.ordinal() + i)))) {
            selectHotbarSlot(i);
        }
    }

    if (inventoryOpen || !mouseCaptured) {
        return;
    }

    updateMovement(window, terrain, deltaTime);
}

private void updateMovement(
        long window,
        TerrainMesh terrain,
        float deltaTime) {

    float dt = Math.min(deltaTime, 0.05f);

    float smoothingRate = stepCameraOffset < 0.0f
            ? STEP_UP_SMOOTHING
            : STEP_DOWN_SMOOTHING;

    float smoothing =
            1.0f - (float) Math.exp(-smoothingRate * dt);

    stepCameraOffset += (0.0f - stepCameraOffset) * smoothing;

    Vector3f look = camera.getLookDirection();

    float forwardLength = (float) Math.sqrt(
            look.x * look.x + look.z * look.z);

    float forwardX = forwardLength > 0.0001f
            ? look.x / forwardLength : 0.0f;
    float forwardZ = forwardLength > 0.0001f
            ? look.z / forwardLength : 0.0f;

    float rightX = -forwardZ;
    float rightZ = forwardX;

    float moveX = 0.0f;
    float moveZ = 0.0f;

    if (isKeyDown(window, keyBindings.get(KeyBindings.Action.FORWARD))) {
        moveX += forwardX;
        moveZ += forwardZ;
    }
    if (isKeyDown(window, keyBindings.get(KeyBindings.Action.BACKWARD))) {
        moveX -= forwardX;
        moveZ -= forwardZ;
    }
    if (isKeyDown(window, keyBindings.get(KeyBindings.Action.RIGHT))) {
        moveX += rightX;
        moveZ += rightZ;
    }
    if (isKeyDown(window, keyBindings.get(KeyBindings.Action.LEFT))) {
        moveX -= rightX;
        moveZ -= rightZ;
    }

    float moveLength = (float) Math.sqrt(
            moveX * moveX + moveZ * moveZ);

    if (moveLength > 0.0001f) {
        moveX /= moveLength;
        moveZ /= moveLength;
    }

    // Double-tap Space to toggle Creative flight; holding Space still ascends.
    boolean jumpKeyDown = isKeyDown(window, keyBindings.get(KeyBindings.Action.JUMP));
    if (gameMode == GameMode.CREATIVE && jumpKeyDown && !flightJumpWasDown) {
        long now = System.nanoTime();
        if (lastCreativeJumpTapNanos != 0 && now - lastCreativeJumpTapNanos < 330_000_000L) {
            setFlying(!isFlying());
            lastCreativeJumpTapNanos = 0;
        } else lastCreativeJumpTapNanos = now;
    }
    flightJumpWasDown = jumpKeyDown;
    if (isFlying()) {
        float flySpeed = (sprintIsDown(window) ? 17f : 9f) * dt;
        float yMove = (isKeyDown(window, keyBindings.get(KeyBindings.Action.JUMP)) ? 1f : 0f)
                - (isKeyDown(window, GLFW.GLFW_KEY_LEFT_SHIFT) ? 1f : 0f);
        float targetX = x + moveX * flySpeed;
        float targetZ = z + moveZ * flySpeed;
        float targetY = Math.max(0f, Math.min(TerrainMesh.WORLD_HEIGHT - PLAYER_HEIGHT, feetY + yMove * flySpeed));
        if (gameMode == GameMode.SPECTATOR || canOccupy(terrain, targetX, z, feetY)) x = targetX;
        if (gameMode == GameMode.SPECTATOR || canOccupy(terrain, x, targetZ, feetY)) z = targetZ;
        if (gameMode == GameMode.SPECTATOR || canOccupy(terrain, x, z, targetY)) feetY = targetY;
        verticalVelocity = 0; grounded = false;
        stepCameraOffset = 0; walkBobOffset = 0;
        updateCameraPosition();
        return;
    }

    float startX = x;
    float startZ = z;

    float ground = getFootprintGround(terrain, x, z);

    if (verticalVelocity <= 0.0f
            && feetY >= ground - 0.06f
            && feetY <= ground + 0.06f) {

        feetY = ground;
        verticalVelocity = 0.0f;
        grounded = true;

    } else if (feetY > ground + 0.06f
            || feetY < ground - 0.06f) {

        grounded = false;
    }

    // Check the ACTUAL surface of fluid, not just its block ID. Shallow flows
    // should slow ankles, not submerge a player's head or trigger full swimming.
    int waterX=(int)Math.floor(x),waterZ=(int)Math.floor(z);
    float ankles=feetY+0.16f,waist=feetY+0.90f;
    boolean wetFeet=terrain.fluidSurfaceAt(waterX,(int)Math.floor(ankles),waterZ)>ankles;
    boolean torsoSubmerged=terrain.fluidSurfaceAt(waterX,(int)Math.floor(waist),waterZ)>waist;
    boolean swimming=wetFeet||torsoSubmerged;
    if(swimming) swimExitGrace=0.40f;
    else swimExitGrace=Math.max(0f,swimExitGrace-dt);
    boolean swimAssist=swimming || (swimExitGrace>0f &&
            isKeyDown(window,keyBindings.get(KeyBindings.Action.JUMP)));
    if(torsoSubmerged)grounded=false; // Water supports swimming, not a standing jump impulse.
    boolean jumpPressed = isKeyDown(window, keyBindings.get(KeyBindings.Action.JUMP));
    boolean sprintPressed = isKeyDown(window, keyBindings.get(KeyBindings.Action.SPRINT));
    boolean descendPressed = isKeyDown(window, GLFW.GLFW_KEY_LEFT_SHIFT);

    if (jumpPressed && !jumpWasPressed && grounded && !swimming) {
        verticalVelocity = JUMP_SPEED;
        grounded = false;
    }
    // Keep climbing while leaving the water; at the surface, held Space
    // provides gentle lift long enough to reach a one-block shore ledge.
    if (jumpPressed && swimAssist) grounded = false;

    jumpWasPressed = jumpPressed;

    float swimSpeed=torsoSubmerged ? (sprintPressed?1.05f:.60f) : (wetFeet?.84f:1f);
    float distance = WALK_SPEED * swimSpeed * (sprintPressed && !torsoSubmerged ? 1.55f : 1f) * dt;
    float[] current=torsoSubmerged?terrain.waterCurrentAt(waterX,(int)Math.floor(waist),waterZ):new float[]{0f,0f,0f};
    float totalX=moveX*distance+current[0]*dt;
    float totalZ=moveZ*distance+current[2]*dt;

    int steps = Math.max(1, (int) Math.ceil(
            Math.max(
                    Math.abs(totalX),
                    Math.abs(totalZ)) / 0.04f));

    float stepX = totalX / steps;
    float stepZ = totalZ / steps;

    for (int i = 0; i < steps; i++) {
        if (stepX != 0.0f) {
            float targetX = x + stepX;

            attemptHorizontalMove(terrain, targetX, z);
        }

        if (stepZ != 0.0f) {
            float targetZ = z + stepZ;

            attemptHorizontalMove(terrain, x, targetZ);
        }
    }

    if (!grounded) {
        float previousFeetY = feetY;

        if (swimAssist) {
            float thrust = jumpPressed ? (torsoSubmerged ? 13.5f : 18f) :
                    (descendPressed ? -8f : -3.8f);
            verticalVelocity += (thrust + current[1] * 2f) * dt;
            verticalVelocity *= (float)Math.exp(-2.6f*dt);
            verticalVelocity = Math.max(-2.5f, Math.min(jumpPressed ? 3.4f : 0.2f,verticalVelocity));
            grounded = false;
        } else {
            verticalVelocity -= GRAVITY * dt;
        }

        float nextFeetY = feetY + verticalVelocity * dt;
        ground = getFootprintGround(terrain, x, z);

        if (verticalVelocity <= 0.0f
                && previousFeetY >= ground - 0.001f
                && nextFeetY <= ground
                && canOccupy(terrain, x, z, ground)) {

            feetY = ground;
            verticalVelocity = 0.0f;
            grounded = true;

        } else if (canOccupy(terrain, x, z, nextFeetY)) {

            feetY = nextFeetY;

        } else {
            verticalVelocity = 0.0f;

            if (nextFeetY < previousFeetY) {
                ground = getFootprintGround(terrain, x, z);

                if (previousFeetY >= ground - 0.001f
                        && previousFeetY <= ground + 0.10f
                        && canOccupy(terrain, x, z, ground)) {
                    feetY = ground;
                    grounded = true;
                }
            }
        }
    } else {
        float currentGround = getFootprintGround(terrain, x, z);

        if (currentGround >= feetY - MAX_STEP_DOWN_HEIGHT
                && currentGround <= feetY + 0.06f
                && canOccupy(terrain, x, z, currentGround)) {

            feetY = currentGround;
            verticalVelocity = 0.0f;

        } else {
            grounded = false;
        }
    }

    float movedX = x - startX;
    float movedZ = z - startZ;

    float horizontalDistance = (float) Math.sqrt(
            movedX * movedX + movedZ * movedZ);

    float targetWalkBob = 0.0f;
    if (grounded && horizontalDistance > 0.0001f) {
        walkBobPhase += horizontalDistance
                * ((float) (Math.PI * 2.0) / WALK_BOB_DISTANCE);
        targetWalkBob = (float) Math.sin(walkBobPhase) * WALK_BOB_AMOUNT;
    }

    // Exponential smoothing is stable across different frame rates and
    // avoids the previous camera-position mutations that were immediately
    // overwritten by updateCameraPosition().
    float bobSmoothing = 1.0f - (float) Math.exp(-10.0f * dt);
    walkBobOffset += (targetWalkBob - walkBobOffset) * bobSmoothing;

    updateCameraPosition();
}

private void attemptHorizontalMove(
        TerrainMesh terrain,
        float targetX,
        float targetZ) {

    float currentGround =
            getFootprintGround(terrain, x, z);

    // Check every block column touched by the player's footprint. Looking
    // only at the center column causes the player to collide with a step
    // before the center crosses into that column, making auto-step
    // impossible.
    float targetGround =
            getHighestGroundUnderFootprint(
                    terrain, targetX, targetZ);

    boolean onGround = grounded
            && verticalVelocity <= 0.0f
            && Math.abs(feetY - currentGround) < 0.15f;

    if (onGround) {
        float difference = targetGround - feetY;

        if (difference > 0.05f) {
            if (difference > MAX_STEP_HEIGHT
                    || !canOccupy(
                            terrain, targetX, targetZ, targetGround)) {
                return;
            }

            stepCameraOffset =
                    feetY + stepCameraOffset - targetGround;

            x = targetX;
            z = targetZ;
            feetY = targetGround;

            verticalVelocity = 0.0f;
            grounded = true;
            return;
        }

        if (difference >= -MAX_STEP_DOWN_HEIGHT) {
            if (!canOccupy(
                    terrain, targetX, targetZ, targetGround)) {
                return;
            }

            if (difference < -0.05f) {
                stepCameraOffset =
                        feetY + stepCameraOffset - targetGround;
            }

            x = targetX;
            z = targetZ;
            feetY = targetGround;

            verticalVelocity = 0.0f;
            grounded = true;
            return;
        }

        if (canOccupy(terrain, targetX, targetZ, feetY)) {
            x = targetX;
            z = targetZ;
            grounded = false;
            verticalVelocity = 0.0f;
        }

        return;
    }

    if (canOccupy(terrain, targetX, targetZ, feetY)) {
        x = targetX;
        z = targetZ;
    }
}

private float getFootprintGround(
        TerrainMesh terrain,
        float centerX,
        float centerZ) {

    // Ground support comes from anywhere under the player's footprint,
    // but only at or below the feet. This keeps the player grounded while
    // stepping onto a block before their center crosses its grid boundary,
    // without mistaking a roof for the floor.
    return findHighestGroundUnderFootprint(
            terrain, centerX, centerZ, feetY + 0.06f);
}

private float getHighestGroundUnderFootprint(
        TerrainMesh terrain,
        float centerX,
        float centerZ) {

    return findHighestGroundUnderFootprint(
            terrain, centerX, centerZ, feetY + MAX_STEP_HEIGHT);
}

private float findHighestGroundUnderFootprint(
        TerrainMesh terrain,
        float centerX,
        float centerZ,
        float highestAllowedSurface) {

    int minX = (int) Math.floor(centerX - PLAYER_RADIUS + 0.001f);
    int maxX = (int) Math.floor(centerX + PLAYER_RADIUS - 0.001f);
    int minZ = (int) Math.floor(centerZ - PLAYER_RADIUS + 0.001f);
    int maxZ = (int) Math.floor(centerZ + PLAYER_RADIUS - 0.001f);

    // Fractional snow surfaces may lie inside the block at the current feet Y.
    int highestBlockToCheck = Math.min(
            TerrainMesh.WORLD_HEIGHT - 1,
            (int) Math.floor(highestAllowedSurface + 0.001f));

    float highestGround = Float.NEGATIVE_INFINITY;

    for (int bx = minX; bx <= maxX; bx++) {
        for (int bz = minZ; bz <= maxZ; bz++) {
            for (int by = highestBlockToCheck; by >= 0; by--) {
                BlockType floor=terrain.getBlockAt(bx,by,bz);
                if (floor.isSolid() && by+floor.collisionHeight() <= highestAllowedSurface+0.001f) {
                    highestGround = Math.max(highestGround, by+floor.collisionHeight());
                    break;
                }
            }
        }
    }

    return highestGround == Float.NEGATIVE_INFINITY
            ? 0.0f : highestGround;
}

private boolean canOccupy(
        TerrainMesh terrain,
        float targetX,
        float targetZ,
        float targetFeetY) {

    int minX = (int) Math.floor(
            targetX - PLAYER_RADIUS + 0.001f);
    int maxX = (int) Math.floor(
            targetX + PLAYER_RADIUS - 0.001f);

    int minZ = (int) Math.floor(
            targetZ - PLAYER_RADIUS + 0.001f);
    int maxZ = (int) Math.floor(
            targetZ + PLAYER_RADIUS - 0.001f);

    

    if (targetFeetY < 0.0f
            || targetFeetY + PLAYER_HEIGHT > TerrainMesh.WORLD_HEIGHT) {
        return false;
    }

    int minY = Math.max(
            0, (int) Math.floor(targetFeetY + 0.001f));

    int maxY = Math.min(
            TerrainMesh.WORLD_HEIGHT - 1,
            (int) Math.floor(
                    targetFeetY + PLAYER_HEIGHT - 0.001f));

    for (int bx = minX; bx <= maxX; bx++) {
        for (int bz = minZ; bz <= maxZ; bz++) {
            // Unloaded columns are not empty terrain. Prevent walking/falling
            // through the streaming frontier until collision data is ready.
            if (!terrain.isColumnLoaded(bx, bz)) return false;
            for (int by = minY; by <= maxY; by++) {
                BlockType obstacle = terrain.getBlockAt(bx, by, bz);
                if (obstacle.isSolid() && targetFeetY < by + obstacle.collisionHeight() - 0.0001f
                        && targetFeetY + PLAYER_HEIGHT > by + 0.0001f) {
                    return false;
                }
            }
        }
    }

    return true;
}

private void updateCameraPosition() {
    camera.setPosition(
            x,
            feetY + EYE_HEIGHT + stepCameraOffset + walkBobOffset,
            z
    );
}

private boolean sprintIsDown(long window) {
    return isKeyDown(window, keyBindings.get(KeyBindings.Action.SPRINT));
}
private boolean isKeyDown(long window, int key) {
    return GLFW.glfwGetKey(window, key) == GLFW.GLFW_PRESS;
}
}