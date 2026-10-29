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
import java.util.Map;
import java.util.HashMap;
import java.util.HashSet;
import java.util.ArrayList;
import java.util.Comparator;
import org.joml.FrustumIntersection;

import static org.lwjgl.opengl.GL11.GL_BACK;
import static org.lwjgl.opengl.GL11.GL_BLEND;
import static org.lwjgl.opengl.GL11.GL_ONE_MINUS_SRC_ALPHA;
import static org.lwjgl.opengl.GL11.GL_SRC_ALPHA;
import static org.lwjgl.opengl.GL11.glBlendFunc;
import static org.lwjgl.opengl.GL11.glDisable;
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
import static org.lwjgl.opengl.GL11.glDepthMask;
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
    private final TitleScreenRenderer titleScreen;
    private float fov = 70f;

    public void setFov(float fieldOfView) {
        if (fieldOfView < 40f || fieldOfView > 120f) {
            throw new IllegalArgumentException("Invalid FOV: " + fieldOfView);
        }
        fov = fieldOfView;
    }

    public void setShowCrosshair(boolean enabled) {
        hud.setShowCrosshair(enabled);
    }

    public void setShowFps(boolean enabled) {
        hud.setShowFps(enabled);
    }

    public void setUiScale(float scale) {
        hud.setUiScale(scale);
    }

    public void setMeasuredFps(int fps) {
        hud.setMeasuredFps(fps);
    }
    public void setDebugOverlay(boolean enabled){hud.setDebugOverlay(enabled);}
    public void setDebugFrameStats(int avgFps, float avgMs, float p95Ms, float worstMs) {
        hud.setDebugFrameStats(avgFps,avgMs,p95Ms,worstMs);
    }
    public void setDebugStreaming(int loaded,int pending,int distance){
        hud.setDebugStreaming(loaded,pending,distance);
    }
    public void setDebugWorkload(int meshes,int lodJobs,int lodReady,long blockBytes){
        hud.setDebugWorkload(meshes,lodJobs,lodReady,blockBytes,gpuChunks.size(),lastTerrainUploads,lastTerrainUploadMicros);
    }

    public float[] uiScaleSliderTrack(int width, int height, int optionsPage) {
        return hud.uiScaleSliderTrack(width, height, optionsPage);
    }

    public int fovSliderValueAt(float x, float y, int width, int height, int optionsPage) {
        return hud.fovSliderValueAt(x, y, width, height, optionsPage);
    }

    public int fovSliderValueClamped(float x, int width, int height, int optionsPage) {
        return hud.fovSliderValueClamped(x, width, height, optionsPage);
    }

    public int renderDistanceSliderValueAt(float x, float y, int width, int height, int optionsPage) {
        return hud.renderDistanceSliderValueAt(x, y, width, height, optionsPage);
    }

    public int renderDistanceSliderValueClamped(float x, int width, int height, int optionsPage) {
        return hud.renderDistanceSliderValueClamped(x, width, height, optionsPage);
    }

    private int shaderProgram;
    private int terrainMvpLocation = -1;
    private int textureSamplerLocation = -1;

    private record GpuChunk(int vao, int vbo, int vertices, int revision, long firstVisibleNanos) {}
    private record MeshKey(int x,int section,int z) {}
    private final Map<MeshKey, GpuChunk> gpuChunks = new HashMap<>();
    private final java.util.Set<Long> fullyUploadedColumns = new java.util.HashSet<>();
    private int terrainVersion = -1;
    private int lastTerrainUploads;
    private long lastTerrainUploadMicros;
    private int lodCameraChunkX=Integer.MIN_VALUE, lodCameraChunkZ=Integer.MIN_VALUE;
    private static final int FULL_DETAIL_RADIUS=8;
    private int fogNearLocation, fogFarLocation, fogColorLocation;
    private int terrainCoverageLocation;
    private int fluidPassLocation, waterTextureLayerLocation, lavaTextureLayerLocation;

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
        this.titleScreen = new TitleScreenRenderer();

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
                "out float vDistance;\n" +
                "uniform vec3 uCamera;\n" +
                "void main(){\n" +
                "  vTexCoord = vec3(aTexCoord, aTextureLayer);\n" +
                "  vShade = aShade;\n" +
                "  vDistance = distance(aPosition, uCamera);\n" +
                "  gl_Position = uMvp * vec4(aPosition, 1.0);\n" +
                "}\n";

        String fragmentSource =
                "#version 330 core\n" +
                "in vec3 vTexCoord;\n" +
                "in float vShade;\n" +
                "in float vDistance;\n" +
                "uniform float uFogNear;\n" +
                "uniform float uFogFar;\n" +
                "uniform vec3 uFogColor;\n" +
                "uniform float uMeshCoverage;\n" +
                "uniform float uFluidPass;\n" +
                "uniform float uWaterTextureLayer;\n" +
                "uniform float uLavaTextureLayer;\n" +
                "uniform sampler2DArray uBlockTextures;\n" +
                "out vec4 fragColor;\n" +
                "void main(){\n" +
                "  vec4 texel = texture(uBlockTextures, vTexCoord);\n" +
                "  if(texel.a < 0.5) discard;\n" +
                // Complementary screen-door dithering: full geometry uses the
                // low samples and distant LOD the remaining samples.
                "  vec2 ip = mod(floor(gl_FragCoord.xy),4.0);\n" +
                "  float noise = fract(dot(ip,vec2(0.375,0.625)));\n" +
                "  if(uMeshCoverage >= 0.0 && noise >= uMeshCoverage) discard;\n" +
                "  if(uMeshCoverage < 0.0 && noise < (1.0 + uMeshCoverage)) discard;\n" +
                "  float fog = smoothstep(uFogNear, uFogFar, vDistance);\n" +
                // Explicit fluid pass; no magic texture-array layer indices.
                "  float opacity = texel.a;\n" +
                "  if(uFluidPass > 0.5) {\n" +
                "    bool water = abs(vTexCoord.z-uWaterTextureLayer)<0.5;\n" +
                "    bool lava = abs(vTexCoord.z-uLavaTextureLayer)<0.5;\n" +
                "    opacity = water ? 0.60 : (lava ? 0.94 : texel.a);\n" +
                "  }\n" +
                "  fragColor = vec4(mix(texel.rgb * vShade, uFogColor, fog), opacity);\n" +
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
        fogNearLocation=glGetUniformLocation(shaderProgram,"uFogNear");
        fogFarLocation=glGetUniformLocation(shaderProgram,"uFogFar");
        fogColorLocation=glGetUniformLocation(shaderProgram,"uFogColor");
        terrainCoverageLocation=glGetUniformLocation(shaderProgram,"uMeshCoverage");
        fluidPassLocation=glGetUniformLocation(shaderProgram,"uFluidPass");
        waterTextureLayerLocation=glGetUniformLocation(shaderProgram,"uWaterTextureLayer");
        lavaTextureLayerLocation=glGetUniformLocation(shaderProgram,"uLavaTextureLayer");

        requireUniform(terrainMvpLocation, "uMvp", "terrain");
        requireUniform(textureSamplerLocation, "uBlockTextures", "terrain");

        glUseProgram(shaderProgram);
        org.lwjgl.opengl.GL20.glUniform1f(terrainCoverageLocation,1.0f);
        org.lwjgl.opengl.GL20.glUniform1f(fluidPassLocation,0f);
        org.lwjgl.opengl.GL20.glUniform1f(waterTextureLayerLocation,BlockType.WATER.getTopTextureLayer());
        org.lwjgl.opengl.GL20.glUniform1f(lavaTextureLayerLocation,BlockType.LAVA.getTopTextureLayer());
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

    private GpuChunk uploadChunk(TerrainMesh.ChunkMesh mesh, long firstVisibleNanos) {
        int chunkVao=glGenVertexArrays(), chunkVbo=glGenBuffers();
        glBindVertexArray(chunkVao);
        glBindBuffer(GL_ARRAY_BUFFER,chunkVbo);
        glBufferData(GL_ARRAY_BUFFER,mesh.vertices(),GL_DYNAMIC_DRAW);
        int stride=TerrainMesh.FLOATS_PER_VERTEX*Float.BYTES;
        glVertexAttribPointer(0,3,GL_FLOAT,false,stride,0L);glEnableVertexAttribArray(0);
        glVertexAttribPointer(1,2,GL_FLOAT,false,stride,3L*Float.BYTES);glEnableVertexAttribArray(1);
        glVertexAttribPointer(2,1,GL_FLOAT,false,stride,5L*Float.BYTES);glEnableVertexAttribArray(2);
        glVertexAttribPointer(3,1,GL_FLOAT,false,stride,6L*Float.BYTES);glEnableVertexAttribArray(3);
        glBindVertexArray(0);glBindBuffer(GL_ARRAY_BUFFER,0);
        return new GpuChunk(chunkVao,chunkVbo,mesh.vertices().length/TerrainMesh.FLOATS_PER_VERTEX,mesh.revision(), firstVisibleNanos);
    }
    private void discard(GpuChunk c){glDeleteBuffers(c.vbo());glDeleteVertexArrays(c.vao());}
    private void initializeTerrain() {
        glEnable(GL_DEPTH_TEST);glEnable(GL_CULL_FACE);glCullFace(GL_BACK);glFrontFace(GL_CCW);
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

    private void updateTerrainIfNeeded(Camera camera){
        int cameraChunkX=(int)Math.floor(camera.getPosition().x/TerrainMesh.CHUNK_SIZE);
        int cameraChunkZ=(int)Math.floor(camera.getPosition().z/TerrainMesh.CHUNK_SIZE);
        if(terrainVersion==terrain.getVersion() && cameraChunkX==lodCameraChunkX && cameraChunkZ==lodCameraChunkZ && !terrain.lodBuildPending())return;
        lodCameraChunkX=cameraChunkX;lodCameraChunkZ=cameraChunkZ;
        List<TerrainMesh.ChunkMesh> source=terrain.chunkMeshesForView(cameraChunkX,cameraChunkZ,FULL_DETAIL_RADIUS);
        HashSet<MeshKey> live=new HashSet<>();
        int uploads=0;
        long uploadStart=System.nanoTime();
        boolean pending=false;
        for(var c:source){
            MeshKey key=new MeshKey(c.x(),c.section(),c.z());live.add(key);
            GpuChunk existing=gpuChunks.get(key);
            if(existing!=null && existing.revision()==c.revision())continue;
            if(uploads>=2){pending=true;continue;}
            if(existing!=null)discard(existing);
            gpuChunks.put(key,uploadChunk(c,existing==null?System.nanoTime():existing.firstVisibleNanos()));
            uploads++;
        }
        var it=gpuChunks.entrySet().iterator();
        while(it.hasNext()){var entry=it.next();if(!live.contains(entry.getKey())){discard(entry.getValue());it.remove();}}
        // Do not fade out detailed geometry until all its nonempty sections
        // have reached the GPU. The upload queue is intentionally bounded.
        java.util.Map<Long,Boolean> fullReady=new java.util.HashMap<>();
        for(var c:source) {
            if(c.section()<0)continue;
            long id=(((long)c.x())<<32) | (c.z()&0xffffffffL);
            GpuChunk resident=gpuChunks.get(new MeshKey(c.x(),c.section(),c.z()));
            boolean ready=resident!=null && resident.revision()==c.revision();
            fullReady.merge(id,ready,(a,b)->a&&b);
        }
        fullyUploadedColumns.clear();
        for(var e:fullReady.entrySet())if(e.getValue())fullyUploadedColumns.add(e.getKey());
        lastTerrainUploads=uploads;
        lastTerrainUploadMicros=(System.nanoTime()-uploadStart)/1000L;
        if(!pending)terrainVersion=terrain.getVersion();
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

    // Dropped block cubes must use the registry's face layers, not numeric block IDs.
    private int getTopTextureLayer(BlockType blockType) {
        return blockType.getTopTextureLayer();
    }

    private int getBottomTextureLayer(BlockType blockType) {
        return blockType.getBottomTextureLayer();
    }

    private int getSideTextureLayer(BlockType blockType) {
        return blockType.getSideTextureLayer();
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

    public void render(
            int width,
            int height,
            Camera camera,
            Player player,
            HudRenderer.ScreenMode screenMode,
            boolean useMenuBackground) {
        if (width <= 0 || height <= 0) {
            return;
        }

        glViewport(0, 0, width, height);
        boolean underwater=terrain.getBlockAt(
            (int)Math.floor(camera.getPosition().x),
            (int)Math.floor(camera.getPosition().y),
            (int)Math.floor(camera.getPosition().z)).isWater();
        glClearColor(underwater?.055f:.48f,underwater?.235f:.68f,underwater?.33f:.88f,1.0f);
        glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);

        if (screenMode == HudRenderer.ScreenMode.MAIN_MENU
                || screenMode == HudRenderer.ScreenMode.SAVE_SELECT
                || screenMode == HudRenderer.ScreenMode.WORLD_CREATE
                || screenMode == HudRenderer.ScreenMode.DELETE_CONFIRM
                || ((screenMode == HudRenderer.ScreenMode.OPTIONS
                        || screenMode == HudRenderer.ScreenMode.KEYBINDS) && useMenuBackground)) {
            titleScreen.render(width, height);
            return;
        }

        updateTerrainIfNeeded(camera);

        Matrix4f projection = new Matrix4f().perspective(
                (float) Math.toRadians(fov),
                (float) width / (float) height,
                0.1f,
                Math.max(120f, (terrain.renderDistance()+1)*TerrainMesh.CHUNK_SIZE*1.6f)
        );

        Matrix4f view = camera.getViewMatrix();
        Matrix4f mvp = projection.mul(view, new Matrix4f());

        glUseProgram(shaderProgram);

        try (MemoryStack stack = MemoryStack.stackPush()) {
            FloatBuffer matrixBuffer = stack.mallocFloat(16);
            mvp.get(matrixBuffer);
            glUniformMatrix4fv(terrainMvpLocation, false, matrixBuffer);
        }

        float far=(terrain.renderDistance()+0.35f)*TerrainMesh.CHUNK_SIZE;
        // Water absorbs light much faster than air. Only the CAMERA's medium
        // determines underwater fog, so coastlines remain clear from above.
        org.lwjgl.opengl.GL20.glUniform1f(fogNearLocation,underwater?1.5f:far*0.67f);
        org.lwjgl.opengl.GL20.glUniform1f(fogFarLocation,underwater?22f:far);
        glUniform3f(fogColorLocation,underwater?.055f:.48f,underwater?.235f:.68f,underwater?.33f:.88f);
        glUniform3f(glGetUniformLocation(shaderProgram,"uCamera"),camera.getPosition().x,camera.getPosition().y,camera.getPosition().z);
        blockTextures.bind(0);
        FrustumIntersection frustum=new FrustumIntersection(mvp);
        // Pass 1: opaque terrain and cutout foliage, with depth writes enabled.
        glDisable(GL_BLEND);
        glDepthMask(true);
        org.lwjgl.opengl.GL20.glUniform1f(fluidPassLocation,0f);
        for(var entry:gpuChunks.entrySet()){
            if(isFluidMesh(entry.getKey()))continue;
            drawTerrainMesh(entry.getKey(),entry.getValue(),camera,frustum);
        }
        glBindVertexArray(0);
        org.lwjgl.opengl.GL20.glUniform1f(terrainCoverageLocation,1f);

        // Dropped items are opaque. Draw them BEFORE water so submerged items
        // can be seen through the fluid rather than appearing pasted on top.
        renderWorldItems(mvp, player == null ? null : player.getWorldItems());

        // Pass 2: translucent fluids after all opaque geometry.
        // Depth testing stays on, but water must not write to the depth buffer.
        ArrayList<Map.Entry<MeshKey,GpuChunk>> visibleFluids=new ArrayList<>();
        for(var entry:gpuChunks.entrySet()) if(isFluidMesh(entry.getKey()))visibleFluids.add(entry);
        visibleFluids.sort(Comparator.comparingDouble((Map.Entry<MeshKey,GpuChunk> e) ->
            fluidDistanceSquared(e.getKey(),camera)).reversed());
        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA,GL_ONE_MINUS_SRC_ALPHA);
        glDepthMask(false);
        glDisable(GL_CULL_FACE); // visible from below the surface as well
        org.lwjgl.opengl.GL20.glUniform1f(fluidPassLocation,1f);
        for(var entry:visibleFluids)
            drawTerrainMesh(entry.getKey(),entry.getValue(),camera,frustum);
        glBindVertexArray(0);
        glDepthMask(true);
        glEnable(GL_CULL_FACE);
        glDisable(GL_BLEND);
        org.lwjgl.opengl.GL20.glUniform1f(fluidPassLocation,0f);
        org.lwjgl.opengl.GL20.glUniform1f(terrainCoverageLocation,1f);
        glBindTexture(GL_TEXTURE_2D_ARRAY, 0);
        glUseProgram(0);

        renderTargetOutline(mvp, camera, player);
    }

    private static boolean isFluidMesh(MeshKey key) {
        return key.section()==-2 || key.section()>=TerrainMesh.FLUID_SECTION_OFFSET;
    }

    private static double fluidDistanceSquared(MeshKey key,Camera camera) {
        double x=key.x()*TerrainMesh.CHUNK_SIZE+TerrainMesh.CHUNK_SIZE*0.5-camera.getPosition().x;
        double y=key.section()<0 ? 0 :
            (key.section()-TerrainMesh.FLUID_SECTION_OFFSET)*TerrainMesh.SECTION_HEIGHT
            +TerrainMesh.SECTION_HEIGHT*0.5-camera.getPosition().y;
        double z=key.z()*TerrainMesh.CHUNK_SIZE+TerrainMesh.CHUNK_SIZE*0.5-camera.getPosition().z;
        return x*x+y*y+z*z;
    }

    /** Shared frustum, LOD crossfade and arrival fade for opaque/fluid streams. */
    private void drawTerrainMesh(MeshKey key,GpuChunk chunk,Camera camera,FrustumIntersection frustum) {
        int cx=key.x(),cz=key.z();
        float x=cx*TerrainMesh.CHUNK_SIZE,z=cz*TerrainMesh.CHUNK_SIZE;
        int section=key.section();
        float bottom=section<0?0:(section%TerrainMesh.SECTION_COUNT)*TerrainMesh.SECTION_HEIGHT;
        float height=section<0?TerrainMesh.WORLD_HEIGHT:TerrainMesh.SECTION_HEIGHT;
        if(!frustum.testAab(x,bottom,z,x+TerrainMesh.CHUNK_SIZE,bottom+height,z+TerrainMesh.CHUNK_SIZE))return;
        if(chunk.vertices()==0)return;
        long column=(((long)cx)<<32) | (cz&0xffffffffL);
        boolean hasLod=gpuChunks.containsKey(new MeshKey(cx,-1,cz)) || gpuChunks.containsKey(new MeshKey(cx,-2,cz));
        boolean hasFull=fullyUploadedColumns.contains(column);
        float dx=Math.abs((x+8f-camera.getPosition().x)/TerrainMesh.CHUNK_SIZE);
        float dz=Math.abs((z+8f-camera.getPosition().z)/TerrainMesh.CHUNK_SIZE);
        float transition=Math.max(0f,Math.min(1f,(Math.max(dx,dz)-7f)/3f));
        float age=Math.max(0f,Math.min(1f,(System.nanoTime()-chunk.firstVisibleNanos())/700_000_000f));
        float coverage;
        if(hasLod&&hasFull) {
            GpuChunk farChunk=gpuChunks.get(new MeshKey(cx,-1,cz));
            if(farChunk==null)farChunk=gpuChunks.get(new MeshKey(cx,-2,cz));
            float farAge=Math.max(0f,Math.min(1f,
                (System.nanoTime()-farChunk.firstVisibleNanos())/700_000_000f));
            float mix=transition*farAge;
            coverage=section<0 ? -Math.max(.00001f,mix) : 1f-mix;
        } else if(section<0) coverage=-Math.max(.00001f,age);
        else if(hasLod) coverage=0f;
        else coverage=age;
        org.lwjgl.opengl.GL20.glUniform1f(terrainCoverageLocation,coverage);
        glBindVertexArray(chunk.vao());
        glDrawArrays(GL_TRIANGLES,0,chunk.vertices());
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
            HudRenderer.ScreenMode screenMode,
            int optionsPage
    ) {
        return hud.buttonAt(mouseX, mouseY, width, height, screenMode, optionsPage);
    }

    public void renderUi(
            int width,
            int height,
            Player player,
            float mouseX,
            float mouseY,
            HudRenderer.ScreenMode screenMode,
            String statusMessage,
            String[] visibleSaveLabels,
            boolean[] visibleSaveExists,
            int savePage,
            boolean hasPreviousSavePage,
            boolean hasNextSavePage,
            com.wildforge.core.GameSettings settings,
            String deleteWorldLabel,
            int optionsPage, String worldName, String worldSeed, boolean editingWorldName
    ) {
        hud.render(
                width, height, player, mouseX, mouseY,
                screenMode, statusMessage,
                visibleSaveLabels, visibleSaveExists,
                savePage, hasPreviousSavePage, hasNextSavePage,
                settings, deleteWorldLabel, optionsPage,worldName,worldSeed,editingWorldName
        );
    }

    public void close() {
        hud.close();
        titleScreen.close();
        blockTextures.close();

        for(GpuChunk chunk:gpuChunks.values())discard(chunk);
        gpuChunks.clear();
        if (shaderProgram != 0) glDeleteProgram(shaderProgram);

        if (outlineVao != 0) glDeleteVertexArrays(outlineVao);
        if (outlineVbo != 0) glDeleteBuffers(outlineVbo);
        if (outlineProgram != 0) glDeleteProgram(outlineProgram);

        if (worldItemsVao != 0) glDeleteVertexArrays(worldItemsVao);
        if (worldItemsVbo != 0) glDeleteBuffers(worldItemsVbo);
    }
}