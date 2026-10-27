package com.wildforge.render;

import com.wildforge.item.ItemType;
import com.wildforge.player.Camera;
import com.wildforge.player.Player;
import com.wildforge.world.BlockType;
import com.wildforge.world.TerrainMesh;
import com.wildforge.entity.DroppedItemEntity;
import com.wildforge.world.WorldItemManager;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.system.MemoryStack;

import java.nio.FloatBuffer;
import java.util.List;

import static org.lwjgl.opengl.GL11.GL_BACK;
import static org.lwjgl.opengl.GL11.GL_CCW;
import static org.lwjgl.opengl.GL11.GL_COLOR_BUFFER_BIT;
import static org.lwjgl.opengl.GL11.GL_CULL_FACE;
import static org.lwjgl.opengl.GL11.GL_DEPTH_BUFFER_BIT;
import static org.lwjgl.opengl.GL11.GL_DEPTH_TEST;
import static org.lwjgl.opengl.GL11.GL_FALSE;
import static org.lwjgl.opengl.GL11.GL_FLOAT;
import static org.lwjgl.opengl.GL11.GL_LINES;
import static org.lwjgl.opengl.GL11.GL_TRIANGLES;
import static org.lwjgl.opengl.GL11.glBindTexture;
import static org.lwjgl.opengl.GL11.glClear;
import static org.lwjgl.opengl.GL11.glClearColor;
import static org.lwjgl.opengl.GL11.glCullFace;
import static org.lwjgl.opengl.GL11.glDrawArrays;
import static org.lwjgl.opengl.GL11.glEnable;
import static org.lwjgl.opengl.GL11.glFrontFace;
import static org.lwjgl.opengl.GL11.glLineWidth;
import static org.lwjgl.opengl.GL11.glViewport;
import static org.lwjgl.opengl.GL15.GL_ARRAY_BUFFER;
import static org.lwjgl.opengl.GL15.GL_DYNAMIC_DRAW;
import static org.lwjgl.opengl.GL15.glBindBuffer;
import static org.lwjgl.opengl.GL15.glBufferData;
import static org.lwjgl.opengl.GL15.glBufferSubData;
import static org.lwjgl.opengl.GL15.glDeleteBuffers;
import static org.lwjgl.opengl.GL15.glGenBuffers;
import static org.lwjgl.opengl.GL20.GL_COMPILE_STATUS;
import static org.lwjgl.opengl.GL20.GL_FRAGMENT_SHADER;
import static org.lwjgl.opengl.GL20.GL_LINK_STATUS;
import static org.lwjgl.opengl.GL20.GL_VERTEX_SHADER;
import static org.lwjgl.opengl.GL20.glAttachShader;
import static org.lwjgl.opengl.GL20.glCompileShader;
import static org.lwjgl.opengl.GL20.glCreateProgram;
import static org.lwjgl.opengl.GL20.glCreateShader;
import static org.lwjgl.opengl.GL20.glDeleteProgram;
import static org.lwjgl.opengl.GL20.glDeleteShader;
import static org.lwjgl.opengl.GL20.glEnableVertexAttribArray;
import static org.lwjgl.opengl.GL20.glGetProgramInfoLog;
import static org.lwjgl.opengl.GL20.glGetProgrami;
import static org.lwjgl.opengl.GL20.glGetShaderInfoLog;
import static org.lwjgl.opengl.GL20.glGetShaderi;
import static org.lwjgl.opengl.GL20.glGetUniformLocation;
import static org.lwjgl.opengl.GL20.glLinkProgram;
import static org.lwjgl.opengl.GL20.glShaderSource;
import static org.lwjgl.opengl.GL20.glUniform1i;
import static org.lwjgl.opengl.GL20.glUniform3f;
import static org.lwjgl.opengl.GL20.glUniformMatrix4fv;
import static org.lwjgl.opengl.GL20.glUseProgram;
import static org.lwjgl.opengl.GL20.glVertexAttribPointer;
import static org.lwjgl.opengl.GL30.GL_TEXTURE_2D_ARRAY;
import static org.lwjgl.opengl.GL30.glBindVertexArray;
import static org.lwjgl.opengl.GL30.glDeleteVertexArrays;
import static org.lwjgl.opengl.GL30.glGenVertexArrays;

public final class Renderer {

    private static final float TARGET_REACH = 6.0f;
    private static final float OUTLINE_OFFSET = 0.002f;
    private static final float ITEM_HALF_SIZE = 0.16f;

    private final TerrainMesh terrain;
    private final HudRenderer hud;
    private final BlockTextures blockTextures;

    private int shaderProgram;
    private int terrainMvpLocation = -1;
    private int textureSamplerLocation = -1;

    private int vao;
    private int vbo;
    private int vertexCount;
    private int terrainVersion = -1;

    private int outlineProgram;
    private int outlineMvpLocation = -1;
    private int outlineColorLocation = -1;
    private int outlineVao;
    private int outlineVbo;

    private int worldItemsVao;
    private int worldItemsVbo;

    public Renderer(TerrainMesh terrain) {
        this.terrain = terrain;
        this.blockTextures = new BlockTextures();
        this.hud = new HudRenderer(blockTextures);

        initializeShaders();
        initializeTerrain();
        initializeOutline();
        initializeWorldItems();
    }

    private void initializeShaders() {
        String vertexSource =
                "#version 330 core\n" +
                "layout(location=0) in vec3 aPosition;\n" +
                "layout(location=1) in vec2 aTexCoord;\n" +
                "layout(location=2) in float aTextureLayer;\n" +
                "layout(location=3) in float aShade;\n" +
                "uniform mat4 uMvp;\n" +
                "out vec3 vTexCoord;\n" +
                "out float vShade;\n" +
                "void main(){\n" +
                "  vTexCoord = vec3(aTexCoord, aTextureLayer);\n" +
                "  vShade = aShade;\n" +
                "  gl_Position = uMvp * vec4(aPosition, 1.0);\n" +
                "}\n";

        String fragmentSource =
                "#version 330 core\n" +
                "in vec3 vTexCoord;\n" +
                "in float vShade;\n" +
                "uniform sampler2DArray uBlockTextures;\n" +
                "out vec4 fragColor;\n" +
                "void main(){\n" +
                "  vec4 texel = texture(uBlockTextures, vTexCoord);\n" +
                "  fragColor = vec4(texel.rgb * vShade, texel.a);\n" +
                "}\n";

        int vertexShader = compileShader(GL_VERTEX_SHADER, vertexSource);
        int fragmentShader = compileShader(GL_FRAGMENT_SHADER, fragmentSource);

        shaderProgram = glCreateProgram();
        glAttachShader(shaderProgram, vertexShader);
        glAttachShader(shaderProgram, fragmentShader);
        glLinkProgram(shaderProgram);

        if (glGetProgrami(shaderProgram, GL_LINK_STATUS) == GL_FALSE) {
            throw new IllegalStateException(
                    "Terrain shader link failed: " + glGetProgramInfoLog(shaderProgram)
            );
        }

        glDeleteShader(vertexShader);
        glDeleteShader(fragmentShader);

        terrainMvpLocation = glGetUniformLocation(shaderProgram, "uMvp");
        textureSamplerLocation = glGetUniformLocation(shaderProgram, "uBlockTextures");

        requireUniform(terrainMvpLocation, "uMvp", "terrain");
        requireUniform(textureSamplerLocation, "uBlockTextures", "terrain");

        glUseProgram(shaderProgram);
        glUniform1i(textureSamplerLocation, 0);
        glUseProgram(0);
    }

    private void requireUniform(int location, String name, String programName) {
        if (location < 0) {
            throw new IllegalStateException(
                    "Required uniform '" + name + "' missing from "
                            + programName + " shader program"
            );
        }
    }

    private int compileShader(int type, String source) {
        int shader = glCreateShader(type);
        glShaderSource(shader, source);
        glCompileShader(shader);

        if (glGetShaderi(shader, GL_COMPILE_STATUS) == GL_FALSE) {
            String log = glGetShaderInfoLog(shader);
            glDeleteShader(shader);
            throw new IllegalStateException("Shader compile failed: " + log);
        }

        return shader;
    }

    private void initializeTerrain() {
        float[] vertices = terrain.getVertices();
        vertexCount = terrain.getVertexCount();

        vao = glGenVertexArrays();
        vbo = glGenBuffers();

        glBindVertexArray(vao);
        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        glBufferData(GL_ARRAY_BUFFER, vertices, GL_DYNAMIC_DRAW);

        int stride = TerrainMesh.FLOATS_PER_VERTEX * Float.BYTES;

        glVertexAttribPointer(0, 3, GL_FLOAT, false, stride, 0L);
        glEnableVertexAttribArray(0);

        glVertexAttribPointer(1, 2, GL_FLOAT, false, stride, 3L * Float.BYTES);
        glEnableVertexAttribArray(1);

        glVertexAttribPointer(2, 1, GL_FLOAT, false, stride, 5L * Float.BYTES);
        glEnableVertexAttribArray(2);

        glVertexAttribPointer(3, 1, GL_FLOAT, false, stride, 6L * Float.BYTES);
        glEnableVertexAttribArray(3);

        glBindBuffer(GL_ARRAY_BUFFER, 0);
        glBindVertexArray(0);

        glEnable(GL_DEPTH_TEST);
        glEnable(GL_CULL_FACE);
        glCullFace(GL_BACK);
        glFrontFace(GL_CCW);

        terrainVersion = terrain.getVersion();
    }

    private void initializeOutline() {
        String vertexSource =
                "#version 330 core\n" +
                "layout(location=0) in vec3 aPosition;\n" +
                "uniform mat4 uMvp;\n" +
                "void main(){\n" +
                "  gl_Position = uMvp * vec4(aPosition, 1.0);\n" +
                "}\n";

        String fragmentSource =
                "#version 330 core\n" +
                "uniform vec3 uColor;\n" +
                "out vec4 fragColor;\n" +
                "void main(){\n" +
                "  fragColor = vec4(uColor, 1.0);\n" +
                "}\n";

        int vertexShader = compileShader(GL_VERTEX_SHADER, vertexSource);
        int fragmentShader = compileShader(GL_FRAGMENT_SHADER, fragmentSource);

        outlineProgram = glCreateProgram();
        glAttachShader(outlineProgram, vertexShader);
        glAttachShader(outlineProgram, fragmentShader);
        glLinkProgram(outlineProgram);

        if (glGetProgrami(outlineProgram, GL_LINK_STATUS) == GL_FALSE) {
            throw new IllegalStateException(
                    "Outline shader link failed: " + glGetProgramInfoLog(outlineProgram)
            );
        }

        glDeleteShader(vertexShader);
        glDeleteShader(fragmentShader);

        outlineMvpLocation = glGetUniformLocation(outlineProgram, "uMvp");
        outlineColorLocation = glGetUniformLocation(outlineProgram, "uColor");

        requireUniform(outlineMvpLocation, "uMvp", "outline");
        requireUniform(outlineColorLocation, "uColor", "outline");

        outlineVao = glGenVertexArrays();
        outlineVbo = glGenBuffers();

        glBindVertexArray(outlineVao);
        glBindBuffer(GL_ARRAY_BUFFER, outlineVbo);

        glBufferData(GL_ARRAY_BUFFER, 24L * 3L * Float.BYTES, GL_DYNAMIC_DRAW);

        glVertexAttribPointer(0, 3, GL_FLOAT, false, 3 * Float.BYTES, 0L);
        glEnableVertexAttribArray(0);

        glBindBuffer(GL_ARRAY_BUFFER, 0);
        glBindVertexArray(0);
    }

    private void initializeWorldItems() {
        worldItemsVao = glGenVertexArrays();
        worldItemsVbo = glGenBuffers();

        glBindVertexArray(worldItemsVao);
        glBindBuffer(GL_ARRAY_BUFFER, worldItemsVbo);

        int stride = TerrainMesh.FLOATS_PER_VERTEX * Float.BYTES;

        glVertexAttribPointer(0, 3, GL_FLOAT, false, stride, 0L);
        glEnableVertexAttribArray(0);

        glVertexAttribPointer(1, 2, GL_FLOAT, false, stride, 3L * Float.BYTES);
        glEnableVertexAttribArray(1);

        glVertexAttribPointer(2, 1, GL_FLOAT, false, stride, 5L * Float.BYTES);
        glEnableVertexAttribArray(2);

        glVertexAttribPointer(3, 1, GL_FLOAT, false, stride, 6L * Float.BYTES);
        glEnableVertexAttribArray(3);

        glBindBuffer(GL_ARRAY_BUFFER, 0);
        glBindVertexArray(0);
    }

    private void updateTerrainIfNeeded() {
        if (terrainVersion == terrain.getVersion()) {
            return;
        }

        float[] vertices = terrain.getVertices();
        vertexCount = terrain.getVertexCount();

        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        glBufferData(GL_ARRAY_BUFFER, vertices, GL_DYNAMIC_DRAW);
        glBindBuffer(GL_ARRAY_BUFFER, 0);

        terrainVersion = terrain.getVersion();
    }

    private void renderWorldItems(Matrix4f mvp, WorldItemManager manager) {
        if (manager == null) {
            return;
        }

        List<DroppedItemEntity> items = manager.getItems();
        if (items.isEmpty()) {
            return;
        }

        int visibleCopies = 0;
        for (DroppedItemEntity item : items) {
            if (!item.isRemoved()) {
                visibleCopies += getVisibleItemCopies(item.getCount());
            }
        }

        if (visibleCopies == 0) {
            return;
        }

        float[] vertices = new float[
                visibleCopies * 36 * TerrainMesh.FLOATS_PER_VERTEX
        ];

        int offset = 0;

        for (DroppedItemEntity item : items) {
            if (item.isRemoved()) {
                continue;
            }

            BlockType blockType = getItemBlockType(item.getItemType());
            offset = appendItemCube(vertices, offset, item, blockType);
        }

        if (offset == 0) {
            return;
        }

        glUseProgram(shaderProgram);

        try (MemoryStack stack = MemoryStack.stackPush()) {
            FloatBuffer matrixBuffer = stack.mallocFloat(16);
            mvp.get(matrixBuffer);
            glUniformMatrix4fv(terrainMvpLocation, false, matrixBuffer);
        }

        blockTextures.bind(0);

        glBindVertexArray(worldItemsVao);
        glBindBuffer(GL_ARRAY_BUFFER, worldItemsVbo);
        glBufferData(GL_ARRAY_BUFFER, vertices, GL_DYNAMIC_DRAW);
        glDrawArrays(GL_TRIANGLES, 0, offset / TerrainMesh.FLOATS_PER_VERTEX);

        glBindBuffer(GL_ARRAY_BUFFER, 0);
        glBindVertexArray(0);
    }

    private int getVisibleItemCopies(int count) {
        if (count <= 1) {
            return 1;
        }
        if (count <= 4) {
            return 2;
        }
        if (count <= 16) {
            return 3;
        }
        return 4;
    }

    private BlockType getItemBlockType(ItemType itemType) {
        if (itemType == null) {
            return BlockType.DIRT;
        }

        BlockType placedBlock = itemType.getPlacedBlock();
        if (placedBlock != null) {
            return placedBlock;
        }

        if (itemType == ItemType.COAL) {
            return BlockType.COAL_ORE;
        }

        return BlockType.DIRT;
    }

    private int getTopTextureLayer(BlockType blockType) {
        if (blockType == BlockType.GRASS) {
            return 0;
        }
        return getSideTextureLayer(blockType);
    }

    private int getBottomTextureLayer(BlockType blockType) {
        if (blockType == BlockType.GRASS) {
            return 2;
        }
        return getSideTextureLayer(blockType);
    }

    private int getSideTextureLayer(BlockType blockType) {
        if (blockType == BlockType.GRASS) {
            return 1;
        }

        return blockType.getId();
    }

    private int appendItemCube(
            float[] out,
            int offset,
            DroppedItemEntity item,
            BlockType blockType
    ) {
        int copies = getVisibleItemCopies(item.getCount());

        for (int i = 0; i < copies; i++) {
            float offsetX = 0.0f;
            float offsetY = 0.0f;
            float offsetZ = 0.0f;
            float angleOffset = 0.0f;

            if (copies == 2) {
                offsetX = i == 0 ? -0.055f : 0.055f;
                offsetZ = i == 0 ? 0.035f : -0.035f;
                angleOffset = i * 0.65f;
            } else if (copies == 3) {
                offsetX = (i - 1) * 0.065f;
                offsetZ = i == 1 ? 0.045f : -0.035f;
                offsetY = i == 1 ? 0.025f : 0.0f;
                angleOffset = i * 0.8f;
            } else if (copies >= 4) {
                offsetX = (i % 2 == 0) ? -0.065f : 0.065f;
                offsetZ = (i < 2) ? -0.055f : 0.055f;
                offsetY = (i == 0 || i == 3) ? 0.025f : 0.0f;
                angleOffset = i * 0.7f;
            }

            offset = appendSingleItemCube(
                    out,
                    offset,
                    item,
                    blockType,
                    offsetX,
                    offsetY,
                    offsetZ,
                    angleOffset
            );
        }

        return offset;
    }

    private int appendSingleItemCube(
            float[] out,
            int offset,
            DroppedItemEntity item,
            BlockType blockType,
            float offsetX,
            float offsetY,
            float offsetZ,
            float angleOffset
    ) {
        float half = ITEM_HALF_SIZE;

        float[][] corners = {
                {-half, -half, -half},
                { half, -half, -half},
                { half,  half, -half},
                {-half,  half, -half},
                {-half, -half,  half},
                { half, -half,  half},
                { half,  half,  half},
                {-half,  half,  half}
        };

        int[][] faces = {
                {4, 5, 6, 4, 6, 7},
                {1, 0, 3, 1, 3, 2},
                {5, 1, 2, 5, 2, 6},
                {0, 4, 7, 0, 7, 3},
                {7, 6, 2, 7, 2, 3},
                {0, 1, 5, 0, 5, 4}
        };

        float[] shades = {1.0f, 0.8f, 0.85f, 0.65f, 1.0f, 0.55f};

        float[][] uv = {
                {0, 1}, {1, 1}, {1, 0},
                {0, 1}, {1, 0}, {0, 0}
        };

        float angle = item.getAge() * 1.5f + angleOffset;
        float cos = (float) Math.cos(angle);
        float sin = (float) Math.sin(angle);

        float centerX = item.getX() + offsetX;
        float centerY = item.getRenderY() + offsetY;
        float centerZ = item.getZ() + offsetZ;

        for (int face = 0; face < faces.length; face++) {
            int textureLayer;

            if (face == 4) {
                textureLayer = getTopTextureLayer(blockType);
            } else if (face == 5) {
                textureLayer = getBottomTextureLayer(blockType);
            } else {
                textureLayer = getSideTextureLayer(blockType);
            }

            for (int vertex = 0; vertex < 6; vertex++) {
                float[] corner = corners[faces[face][vertex]];

                float rotatedX = corner[0] * cos + corner[2] * sin;
                float rotatedZ = -corner[0] * sin + corner[2] * cos;

                out[offset++] = centerX + rotatedX;
                out[offset++] = centerY + corner[1];
                out[offset++] = centerZ + rotatedZ;

                out[offset++] = uv[vertex][0];
                out[offset++] = uv[vertex][1];
                out[offset++] = textureLayer;
                out[offset++] = shades[face];
            }
        }

        return offset;
    }

    public void render(int width, int height, Camera camera, Player player) {
        if (width <= 0 || height <= 0) {
            return;
        }

        glViewport(0, 0, width, height);
        glClearColor(0.48f, 0.68f, 0.88f, 1.0f);
        glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);

        updateTerrainIfNeeded();

        Matrix4f projection = new Matrix4f().perspective(
                (float) Math.toRadians(70.0f),
                (float) width / (float) height,
                0.1f,
                200.0f
        );

        Matrix4f view = camera.getViewMatrix();
        Matrix4f mvp = projection.mul(view, new Matrix4f());

        glUseProgram(shaderProgram);

        try (MemoryStack stack = MemoryStack.stackPush()) {
            FloatBuffer matrixBuffer = stack.mallocFloat(16);
            mvp.get(matrixBuffer);
            glUniformMatrix4fv(terrainMvpLocation, false, matrixBuffer);
        }

        blockTextures.bind(0);

        glBindVertexArray(vao);
        glDrawArrays(GL_TRIANGLES, 0, vertexCount);
        glBindVertexArray(0);

        renderWorldItems(mvp, player == null ? null : player.getWorldItems());

        glBindTexture(GL_TEXTURE_2D_ARRAY, 0);
        glUseProgram(0);

        renderTargetOutline(mvp, camera, player);
    }

    private void renderTargetOutline(Matrix4f mvp, Camera camera, Player player) {
        if (player == null || player.isInventoryOpen() || !player.isMouseCaptured()) {
            return;
        }

        Vector3f origin = camera.getPosition();
        Vector3f direction = camera.getLookDirection();

        TerrainMesh.RayHit hit = terrain.raycast(
                origin.x, origin.y, origin.z,
                direction.x, direction.y, direction.z,
                TARGET_REACH
        );

        if (hit == null || hit.block == BlockType.AIR) {
            return;
        }

        float[] vertices = createOutlineVertices(hit.x, hit.y, hit.z);

        glBindBuffer(GL_ARRAY_BUFFER, outlineVbo);
        glBufferSubData(GL_ARRAY_BUFFER, 0L, vertices);
        glBindBuffer(GL_ARRAY_BUFFER, 0);

        glUseProgram(outlineProgram);

        try (MemoryStack stack = MemoryStack.stackPush()) {
            FloatBuffer matrixBuffer = stack.mallocFloat(16);
            mvp.get(matrixBuffer);
            glUniformMatrix4fv(outlineMvpLocation, false, matrixBuffer);
        }

        glUniform3f(outlineColorLocation, 0.08f, 0.07f, 0.05f);
        glLineWidth(1.5f);

        glBindVertexArray(outlineVao);
        glDrawArrays(GL_LINES, 0, 24);
        glBindVertexArray(0);

        glUseProgram(0);
    }

    private float[] createOutlineVertices(int x, int y, int z) {
        float x0 = x - OUTLINE_OFFSET;
        float y0 = y - OUTLINE_OFFSET;
        float z0 = z - OUTLINE_OFFSET;

        float x1 = x + 1.0f + OUTLINE_OFFSET;
        float y1 = y + 1.0f + OUTLINE_OFFSET;
        float z1 = z + 1.0f + OUTLINE_OFFSET;

        float[][] corners = {
                {x0, y0, z0},
                {x1, y0, z0},
                {x1, y1, z0},
                {x0, y1, z0},
                {x0, y0, z1},
                {x1, y0, z1},
                {x1, y1, z1},
                {x0, y1, z1}
        };

        int[][] edges = {
                {0, 1}, {1, 2}, {2, 3}, {3, 0},
                {4, 5}, {5, 6}, {6, 7}, {7, 4},
                {0, 4}, {1, 5}, {2, 6}, {3, 7}
        };

        float[] vertices = new float[24 * 3];
        int index = 0;

        for (int[] edge : edges) {
            for (int cornerIndex : edge) {
                float[] corner = corners[cornerIndex];
                vertices[index++] = corner[0];
                vertices[index++] = corner[1];
                vertices[index++] = corner[2];
            }
        }

        return vertices;
    }

    public HudRenderer.MenuButton menuButtonAt(
            float mouseX,
            float mouseY,
            int width,
            int height,
            HudRenderer.ScreenMode screenMode
    ) {
        return hud.buttonAt(mouseX, mouseY, width, height, screenMode);
    }

    public void renderUi(
            int width,
            int height,
            Player player,
            float mouseX,
            float mouseY,
            HudRenderer.ScreenMode screenMode,
            String statusMessage,
            boolean[] saveSlotsExist
    ) {
        hud.render(
                width, height, player, mouseX, mouseY,
                screenMode, statusMessage, saveSlotsExist
        );
    }

    public void close() {
        hud.close();
        blockTextures.close();

        if (vao != 0) glDeleteVertexArrays(vao);
        if (vbo != 0) glDeleteBuffers(vbo);
        if (shaderProgram != 0) glDeleteProgram(shaderProgram);

        if (outlineVao != 0) glDeleteVertexArrays(outlineVao);
        if (outlineVbo != 0) glDeleteBuffers(outlineVbo);
        if (outlineProgram != 0) glDeleteProgram(outlineProgram);

        if (worldItemsVao != 0) glDeleteVertexArrays(worldItemsVao);
        if (worldItemsVbo != 0) glDeleteBuffers(worldItemsVbo);
    }
}