package com.wildforge.render;

import org.lwjgl.BufferUtils;
import java.nio.FloatBuffer;
import java.util.List;

import static org.lwjgl.opengl.GL11.GL_BLEND;
import static org.lwjgl.opengl.GL11.GL_CULL_FACE;
import static org.lwjgl.opengl.GL11.GL_DEPTH_TEST;
import static org.lwjgl.opengl.GL11.GL_FALSE;
import static org.lwjgl.opengl.GL11.GL_FLOAT;
import static org.lwjgl.opengl.GL11.GL_ONE_MINUS_SRC_ALPHA;
import static org.lwjgl.opengl.GL11.GL_SRC_ALPHA;
import static org.lwjgl.opengl.GL11.GL_TRIANGLES;
import static org.lwjgl.opengl.GL11.glBindTexture;
import static org.lwjgl.opengl.GL11.glBlendFunc;
import static org.lwjgl.opengl.GL11.glDisable;
import static org.lwjgl.opengl.GL11.glDrawArrays;
import static org.lwjgl.opengl.GL11.glEnable;
import static org.lwjgl.opengl.GL11.glIsEnabled;
import static org.lwjgl.opengl.GL13.GL_TEXTURE0;
import static org.lwjgl.opengl.GL13.glActiveTexture;
import static org.lwjgl.opengl.GL15.GL_ARRAY_BUFFER;
import static org.lwjgl.opengl.GL15.GL_DYNAMIC_DRAW;
import static org.lwjgl.opengl.GL15.glBindBuffer;
import static org.lwjgl.opengl.GL15.glBufferData;
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
import static org.lwjgl.opengl.GL20.glUniform2f;
import static org.lwjgl.opengl.GL20.glUseProgram;
import static org.lwjgl.opengl.GL20.glVertexAttribPointer;
import static org.lwjgl.opengl.GL30.GL_TEXTURE_2D_ARRAY;
import static org.lwjgl.opengl.GL30.glBindVertexArray;
import static org.lwjgl.opengl.GL30.glDeleteVertexArrays;
import static org.lwjgl.opengl.GL30.glGenVertexArrays;

/** Owns only the GL resources that upload colored pixels and textured cube icons. */
final class UiBatchRenderer {
    private int shaderProgram;
    private int vao;
    private int vbo;
    private int resolutionLocation;

    private final BlockTextures blockTextures;
    private int iconShaderProgram;
    private int iconVao;
    private int iconVbo;
    private int iconResolutionLocation;
    private int iconTextureLocation;
    UiBatchRenderer(BlockTextures blockTextures) {
        this.blockTextures = blockTextures;
        shaderProgram = createShaderProgram();
        iconShaderProgram = createIconShaderProgram();

        vao = glGenVertexArrays();
        vbo = glGenBuffers();

        glBindVertexArray(vao);
        glBindBuffer(GL_ARRAY_BUFFER, vbo);

        glEnableVertexAttribArray(0);
        glVertexAttribPointer(0, 2, GL_FLOAT, false,
                6 * Float.BYTES, 0L);

        glEnableVertexAttribArray(1);
        glVertexAttribPointer(1, 4, GL_FLOAT, false,
                6 * Float.BYTES, 2L * Float.BYTES);

        glBindBuffer(GL_ARRAY_BUFFER, 0);
        glBindVertexArray(0);

        resolutionLocation =
                glGetUniformLocation(shaderProgram, "uResolution");

        iconVao = glGenVertexArrays();
        iconVbo = glGenBuffers();

        glBindVertexArray(iconVao);
        glBindBuffer(GL_ARRAY_BUFFER, iconVbo);

        glEnableVertexAttribArray(0);
        glVertexAttribPointer(0, 2, GL_FLOAT, false,
                6 * Float.BYTES, 0L);

        glEnableVertexAttribArray(1);
        glVertexAttribPointer(1, 2, GL_FLOAT, false,
                6 * Float.BYTES, 2L * Float.BYTES);

        glEnableVertexAttribArray(2);
        glVertexAttribPointer(2, 1, GL_FLOAT, false,
                6 * Float.BYTES, 4L * Float.BYTES);

        glEnableVertexAttribArray(3);
        glVertexAttribPointer(3, 1, GL_FLOAT, false,
                6 * Float.BYTES, 5L * Float.BYTES);

        glBindBuffer(GL_ARRAY_BUFFER, 0);
        glBindVertexArray(0);

        iconResolutionLocation =
                glGetUniformLocation(
                        iconShaderProgram, "uResolution");
        iconTextureLocation =
                glGetUniformLocation(
                        iconShaderProgram, "uBlockTextures");
        glUseProgram(iconShaderProgram);
        glUniform1i(iconTextureLocation, 0);
        glUseProgram(0);
    }


    void uploadAndDraw(
            List<Float> data, int width, int height) {

        if (data.isEmpty()) {
            return;
        }

        FloatBuffer buffer =
                BufferUtils.createFloatBuffer(data.size());

        for (float value : data) {
            buffer.put(value);
        }
        buffer.flip();

        // Preserve the OpenGL state used by the 3D renderer.
        boolean depthWasEnabled = glIsEnabled(GL_DEPTH_TEST);
        boolean cullWasEnabled = glIsEnabled(GL_CULL_FACE);
        boolean blendWasEnabled = glIsEnabled(GL_BLEND);

        // HUD elements are 2D and must not be culled.
        glDisable(GL_DEPTH_TEST);
        glDisable(GL_CULL_FACE);
        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);

        glUseProgram(shaderProgram);
        glUniform2f(resolutionLocation, width, height);

        glBindVertexArray(vao);
        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        glBufferData(GL_ARRAY_BUFFER, buffer, GL_DYNAMIC_DRAW);

        glDrawArrays(GL_TRIANGLES, 0, data.size() / 6);

        glBindBuffer(GL_ARRAY_BUFFER, 0);
        glBindVertexArray(0);
        glUseProgram(0);

        // Restore the original state.
        if (blendWasEnabled) {
            glEnable(GL_BLEND);
        } else {
            glDisable(GL_BLEND);
        }

        if (depthWasEnabled) {
            glEnable(GL_DEPTH_TEST);
        } else {
            glDisable(GL_DEPTH_TEST);
        }

        if (cullWasEnabled) {
            glEnable(GL_CULL_FACE);
        } else {
            glDisable(GL_CULL_FACE);
        }
    }

    void uploadAndDrawIcons(
            List<Float> data, int width, int height) {

        if (data.isEmpty()) {
            return;
        }

        FloatBuffer buffer =
                BufferUtils.createFloatBuffer(data.size());
        for (float value : data) {
            buffer.put(value);
        }
        buffer.flip();

        boolean depthWasEnabled = glIsEnabled(GL_DEPTH_TEST);
        boolean cullWasEnabled = glIsEnabled(GL_CULL_FACE);
        boolean blendWasEnabled = glIsEnabled(GL_BLEND);

        glDisable(GL_DEPTH_TEST);
        glDisable(GL_CULL_FACE);
        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);

        glUseProgram(iconShaderProgram);
        glUniform2f(iconResolutionLocation, width, height);

        glActiveTexture(GL_TEXTURE0);
        blockTextures.bind(0);

        glBindVertexArray(iconVao);
        glBindBuffer(GL_ARRAY_BUFFER, iconVbo);
        glBufferData(GL_ARRAY_BUFFER, buffer, GL_DYNAMIC_DRAW);
        glDrawArrays(GL_TRIANGLES, 0, data.size() / 6);

        glBindBuffer(GL_ARRAY_BUFFER, 0);
        glBindVertexArray(0);
        glUseProgram(0);
        glBindTexture(GL_TEXTURE_2D_ARRAY, 0);

        if (blendWasEnabled) {
            glEnable(GL_BLEND);
        } else {
            glDisable(GL_BLEND);
        }
        if (depthWasEnabled) {
            glEnable(GL_DEPTH_TEST);
        } else {
            glDisable(GL_DEPTH_TEST);
        }
        if (cullWasEnabled) {
            glEnable(GL_CULL_FACE);
        } else {
            glDisable(GL_CULL_FACE);
        }
    }


    private int createShaderProgram() {
        String vertexSource = """
                #version 330 core
                layout (location = 0) in vec2 aPosition;
                layout (location = 1) in vec4 aColor;

                uniform vec2 uResolution;
                out vec4 vColor;

                void main() {
                    vec2 position = vec2(
                        (aPosition.x / uResolution.x) * 2.0 - 1.0,
                        1.0 - (aPosition.y / uResolution.y) * 2.0
                    );
                    gl_Position = vec4(position, 0.0, 1.0);
                    vColor = aColor;
                }
                """;

        String fragmentSource = """
                #version 330 core
                in vec4 vColor;
                out vec4 FragColor;

                void main() {
                    FragColor = vColor;
                }
                """;

        int vertexShader = glCreateShader(GL_VERTEX_SHADER);
        glShaderSource(vertexShader, vertexSource);
        glCompileShader(vertexShader);

        if (glGetShaderi(vertexShader, GL_COMPILE_STATUS) == GL_FALSE) {
            String log = glGetShaderInfoLog(vertexShader);
            glDeleteShader(vertexShader);
            throw new IllegalStateException(
                    "HUD vertex shader failed: " + log);
        }

        int fragmentShader = glCreateShader(GL_FRAGMENT_SHADER);
        glShaderSource(fragmentShader, fragmentSource);
        glCompileShader(fragmentShader);

        if (glGetShaderi(fragmentShader, GL_COMPILE_STATUS)
                == GL_FALSE) {
            String log = glGetShaderInfoLog(fragmentShader);
            glDeleteShader(vertexShader);
            glDeleteShader(fragmentShader);
            throw new IllegalStateException(
                    "HUD fragment shader failed: " + log);
        }

        int program = glCreateProgram();
        glAttachShader(program, vertexShader);
        glAttachShader(program, fragmentShader);
        glLinkProgram(program);

        glDeleteShader(vertexShader);
        glDeleteShader(fragmentShader);

        if (glGetProgrami(program, GL_LINK_STATUS) == GL_FALSE) {
            String log = glGetProgramInfoLog(program);
            glDeleteProgram(program);
            throw new IllegalStateException(
                    "HUD shader linking failed: " + log);
        }

        return program;
    }

    private int createIconShaderProgram() {
        String vertexSource = """
                #version 330 core
                layout (location = 0) in vec2 aPosition;
                layout (location = 1) in vec2 aTexCoord;
                layout (location = 2) in float aTextureLayer;
                layout (location = 3) in float aShade;

                uniform vec2 uResolution;
                out vec3 vTexCoord;
                out float vShade;

                void main() {
                    vec2 position = vec2(
                        (aPosition.x / uResolution.x) * 2.0 - 1.0,
                        1.0 - (aPosition.y / uResolution.y) * 2.0
                    );
                    gl_Position = vec4(position, 0.0, 1.0);
                    vTexCoord = vec3(aTexCoord, aTextureLayer);
                    vShade = aShade;
                }
                """;

        String fragmentSource = """
                #version 330 core
                in vec3 vTexCoord;
                in float vShade;
                uniform sampler2DArray uBlockTextures;
                out vec4 FragColor;

                void main() {
                    vec4 texel = texture(uBlockTextures, vTexCoord);
                    FragColor = vec4(texel.rgb * vShade, texel.a);
                }
                """;

        int vertexShader = glCreateShader(GL_VERTEX_SHADER);
        glShaderSource(vertexShader, vertexSource);
        glCompileShader(vertexShader);
        if (glGetShaderi(vertexShader, GL_COMPILE_STATUS) == GL_FALSE) {
            String log = glGetShaderInfoLog(vertexShader);
            glDeleteShader(vertexShader);
            throw new IllegalStateException(
                    "HUD icon vertex shader failed: " + log);
        }

        int fragmentShader = glCreateShader(GL_FRAGMENT_SHADER);
        glShaderSource(fragmentShader, fragmentSource);
        glCompileShader(fragmentShader);
        if (glGetShaderi(fragmentShader, GL_COMPILE_STATUS)
                == GL_FALSE) {
            String log = glGetShaderInfoLog(fragmentShader);
            glDeleteShader(vertexShader);
            glDeleteShader(fragmentShader);
            throw new IllegalStateException(
                    "HUD icon fragment shader failed: " + log);
        }

        int program = glCreateProgram();
        glAttachShader(program, vertexShader);
        glAttachShader(program, fragmentShader);
        glLinkProgram(program);
        glDeleteShader(vertexShader);
        glDeleteShader(fragmentShader);

        if (glGetProgrami(program, GL_LINK_STATUS) == GL_FALSE) {
            String log = glGetProgramInfoLog(program);
            glDeleteProgram(program);
            throw new IllegalStateException(
                    "HUD icon shader linking failed: " + log);
        }

        return program;
    }


    void close() {
        if (vbo != 0) {
            glDeleteBuffers(vbo);
        }
        if (vao != 0) {
            glDeleteVertexArrays(vao);
        }
        if (shaderProgram != 0) {
            glDeleteProgram(shaderProgram);
        }
        if (iconVbo != 0) {
            glDeleteBuffers(iconVbo);
        }
        if (iconVao != 0) {
            glDeleteVertexArrays(iconVao);
        }
        if (iconShaderProgram != 0) {
            glDeleteProgram(iconShaderProgram);
        }
    }}
