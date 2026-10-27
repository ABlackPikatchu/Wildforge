
package com.wildforge.world;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;

public final class TerrainMesh {

    public static final int CHUNK_SIZE = 16;
    public static final int WORLD_CHUNKS = 4;
    public static final int WORLD_SIZE = CHUNK_SIZE * WORLD_CHUNKS;
    public static final int WORLD_HEIGHT = 32;

    // x, y, z, u, v, texture layer, face shade
    public static final int FLOATS_PER_VERTEX = 7;

    private static final int SAVE_MAGIC = 0x57464F52;
    private static final int SAVE_VERSION = 1;

    public static final class RayHit {
        public final int x, y, z;
        public final int placeX, placeY, placeZ;
        public final BlockType block;

        RayHit(
                int x, int y, int z,
                int placeX, int placeY, int placeZ,
                BlockType block
        ) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.placeX = placeX;
            this.placeY = placeY;
            this.placeZ = placeZ;
            this.block = block;
        }
    }

    private static final class Chunk {
        final int chunkX;
        final int chunkZ;
        float[] vertices = new float[0];

        Chunk(int x, int z) {
            chunkX = x;
            chunkZ = z;
        }
    }

    /** Primitive float buffer used while building chunk meshes. */
    private static final class FloatArrayBuilder {
        private float[] values = new float[1024];
        private int size;

        void add(float value) {
            ensureCapacity(size + 1);
            values[size++] = value;
        }

        float[] toArray() {
            return Arrays.copyOf(values, size);
        }

        private void ensureCapacity(int required) {
            if (required <= values.length) {
                return;
            }

            int nextCapacity = values.length;
            while (nextCapacity < required) {
                nextCapacity = Math.max(nextCapacity + 1, nextCapacity * 2);
            }
            values = Arrays.copyOf(values, nextCapacity);
        }
    }

    private final byte[][][] blocks =
            new byte[WORLD_SIZE][WORLD_HEIGHT][WORLD_SIZE];

    private final Chunk[][] chunks =
            new Chunk[WORLD_CHUNKS][WORLD_CHUNKS];

    private int version = 0;

    public TerrainMesh() {
        generateWorld();

        for (int cx = 0; cx < WORLD_CHUNKS; cx++) {
            for (int cz = 0; cz < WORLD_CHUNKS; cz++) {
                chunks[cx][cz] = new Chunk(cx, cz);
                rebuildChunk(chunks[cx][cz]);
            }
        }
    }

    /** Resets this instance to the current built-in starter world. */
    public void resetWorld() {
        for (int x = 0; x < WORLD_SIZE; x++) {
            for (int y = 0; y < WORLD_HEIGHT; y++) {
                Arrays.fill(blocks[x][y], BlockType.AIR.id);
            }
        }

        generateWorld();

        for (Chunk[] row : chunks) {
            for (Chunk chunk : row) {
                rebuildChunk(chunk);
            }
        }

        version++;
    }

    private void generateWorld() {
        for (int x = 0; x < WORLD_SIZE; x++) {
            for (int z = 0; z < WORLD_SIZE; z++) {
                double hills =
                        Math.sin(x * 0.19) * 1.7
                        + Math.cos(z * 0.16) * 1.5
                        + Math.sin((x + z) * 0.11) * 1.2;

                int surface = 5 + (int) Math.round(hills);
                surface = Math.max(
                        2,
                        Math.min(WORLD_HEIGHT - 2, surface)
                );

                for (int y = 0; y <= surface; y++) {
                    BlockType type;

                    if (y == surface) {
                        type = BlockType.GRASS;
                    } else if (y >= surface - 3) {
                        type = BlockType.DIRT;
                    } else {
                        type = BlockType.STONE;
                    }

                    blocks[x][y][z] = type.id;
                }
            }
        }
    }

    private boolean inside(int x, int y, int z) {
        return x >= 0 && x < WORLD_SIZE
                && y >= 0 && y < WORLD_HEIGHT
                && z >= 0 && z < WORLD_SIZE;
    }

    public BlockType getBlockAt(int x, int y, int z) {
        if (!inside(x, y, z)) {
            return BlockType.AIR;
        }

        return BlockType.fromId(blocks[x][y][z]);
    }

    public boolean setBlock(
            int x, int y, int z,
            BlockType type
    ) {
        if (!inside(x, y, z) || type == null) {
            return false;
        }

        if (getBlockAt(x, y, z) == type) {
            return false;
        }

        blocks[x][y][z] = type.id;

        int cx = x / CHUNK_SIZE;
        int cz = z / CHUNK_SIZE;

        rebuildChunk(chunks[cx][cz]);

        if (x % CHUNK_SIZE == 0 && cx > 0) {
            rebuildChunk(chunks[cx - 1][cz]);
        }

        if (x % CHUNK_SIZE == CHUNK_SIZE - 1
                && cx < WORLD_CHUNKS - 1) {
            rebuildChunk(chunks[cx + 1][cz]);
        }

        if (z % CHUNK_SIZE == 0 && cz > 0) {
            rebuildChunk(chunks[cx][cz - 1]);
        }

        if (z % CHUNK_SIZE == CHUNK_SIZE - 1
                && cz < WORLD_CHUNKS - 1) {
            rebuildChunk(chunks[cx][cz + 1]);
        }

        version++;
        return true;
    }

    public float getHeightAt(float x, float z) {
        int bx = Math.max(
                0, Math.min(WORLD_SIZE - 1, (int) x)
        );

        int bz = Math.max(
                0, Math.min(WORLD_SIZE - 1, (int) z)
        );

        for (int y = WORLD_HEIGHT - 1; y >= 0; y--) {
            if (getBlockAt(bx, y, bz).isSolid()) {
                return y + 1.0f;
            }
        }

        return 0.0f;
    }

    public int getVersion() {
        return version;
    }

    public float[] getVertices() {
        int total = 0;

        for (Chunk[] row : chunks) {
            for (Chunk chunk : row) {
                total += chunk.vertices.length;
            }
        }

        float[] result = new float[total];
        int offset = 0;

        for (Chunk[] row : chunks) {
            for (Chunk chunk : row) {
                System.arraycopy(
                        chunk.vertices, 0,
                        result, offset,
                        chunk.vertices.length
                );

                offset += chunk.vertices.length;
            }
        }

        return result;
    }

    public int getVertexCount() {
        int total = 0;

        for (Chunk[] row : chunks) {
            for (Chunk chunk : row) {
                total += chunk.vertices.length / FLOATS_PER_VERTEX;
            }
        }

        return total;
    }

    /** Saves the block data using a versioned binary format. */
    public void saveTo(Path path) throws IOException {
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
                output.writeInt(SAVE_MAGIC);
                output.writeInt(SAVE_VERSION);
                output.writeInt(WORLD_SIZE);
                output.writeInt(WORLD_HEIGHT);
                output.writeInt(WORLD_SIZE);

                for (int x = 0; x < WORLD_SIZE; x++) {
                    for (int y = 0; y < WORLD_HEIGHT; y++) {
                        output.write(blocks[x][y]);
                    }
                }
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

    /** Loads block data, leaving the current world unchanged on failure. */
    public void loadFrom(Path path) throws IOException {
        if (path == null) {
            throw new IllegalArgumentException("Save path cannot be null");
        }

        byte[][][] loadedBlocks =
                new byte[WORLD_SIZE][WORLD_HEIGHT][WORLD_SIZE];

        try (DataInputStream input = new DataInputStream(
                new BufferedInputStream(Files.newInputStream(path)))) {
            if (input.readInt() != SAVE_MAGIC) {
                throw new IOException("Not a Wildforge world save");
            }

            int saveVersion = input.readInt();
            if (saveVersion != SAVE_VERSION) {
                throw new IOException(
                        "Unsupported world save version: " + saveVersion);
            }

            int savedWidth = input.readInt();
            int savedHeight = input.readInt();
            int savedDepth = input.readInt();

            if (savedWidth != WORLD_SIZE
                    || savedHeight != WORLD_HEIGHT
                    || savedDepth != WORLD_SIZE) {
                throw new IOException(
                        "World save dimensions do not match this build");
            }

            for (int x = 0; x < WORLD_SIZE; x++) {
                for (int y = 0; y < WORLD_HEIGHT; y++) {
                    input.readFully(loadedBlocks[x][y]);

                    for (byte blockId : loadedBlocks[x][y]) {
                        if (!BlockType.isValidId(blockId)) {
                            throw new IOException(
                                    "World save contains an unknown block id");
                        }
                    }
                }
            }

            if (input.read() != -1) {
                throw new IOException("World save contains trailing data");
            }
        }

        for (int x = 0; x < WORLD_SIZE; x++) {
            for (int y = 0; y < WORLD_HEIGHT; y++) {
                System.arraycopy(
                        loadedBlocks[x][y], 0,
                        blocks[x][y], 0,
                        WORLD_SIZE
                );
            }
        }

        for (Chunk[] row : chunks) {
            for (Chunk chunk : row) {
                rebuildChunk(chunk);
            }
        }

        version++;
    }

    private void rebuildChunk(Chunk chunk) {
        FloatArrayBuilder data = new FloatArrayBuilder();

        int startX = chunk.chunkX * CHUNK_SIZE;
        int startZ = chunk.chunkZ * CHUNK_SIZE;

        for (int x = startX; x < startX + CHUNK_SIZE; x++) {
            for (int z = startZ; z < startZ + CHUNK_SIZE; z++) {
                for (int y = 0; y < WORLD_HEIGHT; y++) {
                    BlockType block = getBlockAt(x, y, z);

                    if (block == BlockType.AIR) {
                        continue;
                    }

                    // Positive Z face.
                    if (getBlockAt(x, y, z + 1) == BlockType.AIR) {
                        face(data,
                                x, y, z + 1,
                                x + 1, y, z + 1,
                                x + 1, y + 1, z + 1,
                                x, y + 1, z + 1,
                                block.getSideTextureLayer(), 0.85f);
                    }

                    // Negative Z face.
                    if (getBlockAt(x, y, z - 1) == BlockType.AIR) {
                        face(data,
                                x + 1, y, z,
                                x, y, z,
                                x, y + 1, z,
                                x + 1, y + 1, z,
                                block.getSideTextureLayer(), 0.75f);
                    }

                    // Positive X face.
                    if (getBlockAt(x + 1, y, z) == BlockType.AIR) {
                        face(data,
                                x + 1, y, z + 1,
                                x + 1, y, z,
                                x + 1, y + 1, z,
                                x + 1, y + 1, z + 1,
                                block.getSideTextureLayer(), 0.80f);
                    }

                    // Negative X face.
                    if (getBlockAt(x - 1, y, z) == BlockType.AIR) {
                        face(data,
                                x, y, z,
                                x, y, z + 1,
                                x, y + 1, z + 1,
                                x, y + 1, z,
                                block.getSideTextureLayer(), 0.80f);
                    }

                    // Top face.
                    if (getBlockAt(x, y + 1, z) == BlockType.AIR) {
                        face(data,
                                x, y + 1, z + 1,
                                x + 1, y + 1, z + 1,
                                x + 1, y + 1, z,
                                x, y + 1, z,
                                block.getTopTextureLayer(), 1.0f);
                    }

                    // Bottom face.
                    if (getBlockAt(x, y - 1, z) == BlockType.AIR) {
                        face(data,
                                x, y, z,
                                x + 1, y, z,
                                x + 1, y, z + 1,
                                x, y, z + 1,
                                block.getBottomTextureLayer(), 0.55f);
                    }
                }
            }
        }

        chunk.vertices = data.toArray();
    }

    private void face(
            FloatArrayBuilder out,
            float ax, float ay, float az,
            float bx, float by, float bz,
            float cx, float cy, float cz,
            float dx, float dy, float dz,
            int textureLayer,
            float shade
    ) {
        // Six vertices forming two triangles. Each vertex contains
        // XYZ, UV, texture layer, and face shade. Append directly to the
        // primitive buffer to avoid per-face arrays and boxed Float objects.
        vertex(out, ax, ay, az, 0f, 1f, textureLayer, shade);
        vertex(out, bx, by, bz, 1f, 1f, textureLayer, shade);
        vertex(out, cx, cy, cz, 1f, 0f, textureLayer, shade);

        vertex(out, ax, ay, az, 0f, 1f, textureLayer, shade);
        vertex(out, cx, cy, cz, 1f, 0f, textureLayer, shade);
        vertex(out, dx, dy, dz, 0f, 0f, textureLayer, shade);
    }

    private void vertex(
            FloatArrayBuilder out,
            float x, float y, float z,
            float u, float v,
            int textureLayer,
            float shade
    ) {
        out.add(x);
        out.add(y);
        out.add(z);
        out.add(u);
        out.add(v);
        out.add(textureLayer);
        out.add(shade);
    }

    public RayHit raycast(
            float ox, float oy, float oz,
            float dx, float dy, float dz,
            float reach
    ) {
        float length = (float) Math.sqrt(
                dx * dx + dy * dy + dz * dz
        );

        if (length == 0.0f || reach < 0.0f) {
            return null;
        }

        dx /= length;
        dy /= length;
        dz /= length;

        int x = (int) Math.floor(ox);
        int y = (int) Math.floor(oy);
        int z = (int) Math.floor(oz);

        BlockType startingBlock = getBlockAt(x, y, z);
        if (startingBlock != BlockType.AIR) {
            return new RayHit(
                    x, y, z,
                    x, y, z,
                    startingBlock
            );
        }

        int stepX = dx > 0.0f ? 1 : dx < 0.0f ? -1 : 0;
        int stepY = dy > 0.0f ? 1 : dy < 0.0f ? -1 : 0;
        int stepZ = dz > 0.0f ? 1 : dz < 0.0f ? -1 : 0;

        double tDeltaX = stepX == 0
                ? Double.POSITIVE_INFINITY
                : Math.abs(1.0 / dx);
        double tDeltaY = stepY == 0
                ? Double.POSITIVE_INFINITY
                : Math.abs(1.0 / dy);
        double tDeltaZ = stepZ == 0
                ? Double.POSITIVE_INFINITY
                : Math.abs(1.0 / dz);

        double tMaxX = stepX > 0
                ? (x + 1.0 - ox) / dx
                : stepX < 0
                        ? (ox - x) / -dx
                        : Double.POSITIVE_INFINITY;
        double tMaxY = stepY > 0
                ? (y + 1.0 - oy) / dy
                : stepY < 0
                        ? (oy - y) / -dy
                        : Double.POSITIVE_INFINITY;
        double tMaxZ = stepZ > 0
                ? (z + 1.0 - oz) / dz
                : stepZ < 0
                        ? (oz - z) / -dz
                        : Double.POSITIVE_INFINITY;

        while (true) {
            int previousX = x;
            int previousY = y;
            int previousZ = z;
            double distance;

            // Advance through the next voxel boundary. Ties are resolved
            // one axis at a time, yielding a face-adjacent placement cell.
            if (tMaxX <= tMaxY && tMaxX <= tMaxZ) {
                distance = tMaxX;
                x += stepX;
                tMaxX += tDeltaX;
            } else if (tMaxY <= tMaxZ) {
                distance = tMaxY;
                y += stepY;
                tMaxY += tDeltaY;
            } else {
                distance = tMaxZ;
                z += stepZ;
                tMaxZ += tDeltaZ;
            }

            if (distance > reach) {
                return null;
            }

            BlockType block = getBlockAt(x, y, z);
            if (block != BlockType.AIR) {
                return new RayHit(
                        x, y, z,
                        previousX, previousY, previousZ,
                        block
                );
            }
        }
    }

}
