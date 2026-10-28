package com.wildforge.render;

import org.lwjgl.BufferUtils;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;

import static org.lwjgl.opengl.GL11.GL_BLEND;
import static org.lwjgl.opengl.GL11.GL_CULL_FACE;
import static org.lwjgl.opengl.GL11.GL_DEPTH_TEST;
import static org.lwjgl.opengl.GL11.GL_FALSE;
import static org.lwjgl.opengl.GL11.GL_FLOAT;
import static org.lwjgl.opengl.GL11.GL_LINEAR;
import static org.lwjgl.opengl.GL11.GL_ONE_MINUS_SRC_ALPHA;
import static org.lwjgl.opengl.GL11.GL_RGBA;
import static org.lwjgl.opengl.GL11.GL_SRC_ALPHA;
import static org.lwjgl.opengl.GL11.GL_TEXTURE_2D;
import static org.lwjgl.opengl.GL11.GL_TEXTURE_MAG_FILTER;
import static org.lwjgl.opengl.GL11.GL_TEXTURE_MIN_FILTER;
import static org.lwjgl.opengl.GL11.GL_TRIANGLES;
import static org.lwjgl.opengl.GL11.GL_UNSIGNED_BYTE;
import static org.lwjgl.opengl.GL11.glBindTexture;
import static org.lwjgl.opengl.GL11.glBlendFunc;
import static org.lwjgl.opengl.GL11.glDeleteTextures;
import static org.lwjgl.opengl.GL11.glDisable;
import static org.lwjgl.opengl.GL11.glDrawArrays;
import static org.lwjgl.opengl.GL11.glEnable;
import static org.lwjgl.opengl.GL11.glGenTextures;
import static org.lwjgl.opengl.GL11.glIsEnabled;
import static org.lwjgl.opengl.GL11.glTexImage2D;
import static org.lwjgl.opengl.GL11.glTexParameteri;
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
import static org.lwjgl.opengl.GL30.glBindVertexArray;
import static org.lwjgl.opengl.GL30.glDeleteVertexArrays;
import static org.lwjgl.opengl.GL30.glGenVertexArrays;

/** Renders the supplied title artwork as a full-screen background. */
public final class TitleScreenRenderer {
    private int shaderProgram;
    private int vao;
    private int vbo;
    private int resolutionLocation;
    private int textureLocation;
    private int textureId;
    private int imageWidth;
    private int imageHeight;

    public TitleScreenRenderer() {
        shaderProgram = createShaderProgram();
        resolutionLocation = glGetUniformLocation(shaderProgram, "uResolution");
        textureLocation = glGetUniformLocation(shaderProgram, "uTexture");

        vao = glGenVertexArrays();
        vbo = glGenBuffers();
        glBindVertexArray(vao);
        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        glEnableVertexAttribArray(0);
        glVertexAttribPointer(0, 2, GL_FLOAT, false, 4 * Float.BYTES, 0L);
        glEnableVertexAttribArray(1);
        glVertexAttribPointer(1, 2, GL_FLOAT, false, 4 * Float.BYTES, 2L * Float.BYTES);
        glBindBuffer(GL_ARRAY_BUFFER, 0);
        glBindVertexArray(0);

        textureId = loadTexture("ui/title_screen.png");

        glUseProgram(shaderProgram);
        glUniform1i(textureLocation, 0);
        glUseProgram(0);
    }

    public void render(int width, int height) {
        if (width <= 0 || height <= 0 || textureId == 0) {
            return;
        }

        // ImageIO delivers the top scanline first, which occupies OpenGL V=0.
        // The previous UVs inverted the artwork. Crop to cover rather than
        // stretching the image when the window's aspect ratio changes.
        float imageAspect = (float) imageWidth / imageHeight;
        float screenAspect = (float) width / height;
        float u0 = 0f, u1 = 1f, v0 = 0f, v1 = 1f;
        if (screenAspect > imageAspect) {
            float visible = imageAspect / screenAspect;
            v0 = (1f - visible) * 0.5f;
            v1 = 1f - v0;
        } else if (screenAspect < imageAspect) {
            float visible = screenAspect / imageAspect;
            u0 = (1f - visible) * 0.5f;
            u1 = 1f - u0;
        }

        float[] vertices = {
                0f, 0f, u0, v0,
                width, 0f, u1, v0,
                width, height, u1, v1,
                0f, 0f, u0, v0,
                width, height, u1, v1,
                0f, height, u0, v1
        };

        FloatBuffer buffer = BufferUtils.createFloatBuffer(vertices.length);
        buffer.put(vertices).flip();

        boolean depthWasEnabled = glIsEnabled(GL_DEPTH_TEST);
        boolean cullWasEnabled = glIsEnabled(GL_CULL_FACE);
        boolean blendWasEnabled = glIsEnabled(GL_BLEND);

        glDisable(GL_DEPTH_TEST);
        glDisable(GL_CULL_FACE);
        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);

        glUseProgram(shaderProgram);
        glUniform2f(resolutionLocation, width, height);
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, textureId);

        glBindVertexArray(vao);
        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        glBufferData(GL_ARRAY_BUFFER, buffer, GL_DYNAMIC_DRAW);
        glDrawArrays(GL_TRIANGLES, 0, 6);
        glBindBuffer(GL_ARRAY_BUFFER, 0);
        glBindVertexArray(0);
        glBindTexture(GL_TEXTURE_2D, 0);
        glUseProgram(0);

        if (blendWasEnabled) glEnable(GL_BLEND); else glDisable(GL_BLEND);
        if (depthWasEnabled) glEnable(GL_DEPTH_TEST); else glDisable(GL_DEPTH_TEST);
        if (cullWasEnabled) glEnable(GL_CULL_FACE); else glDisable(GL_CULL_FACE);
    }

    private int loadTexture(String resourcePath) {
        try (InputStream stream = TitleScreenRenderer.class.getClassLoader().getResourceAsStream(resourcePath)) {
            if (stream == null) {
                throw new IllegalStateException("Missing title-screen image: " + resourcePath);
            }
            BufferedImage image = ImageIO.read(stream);
            if (image == null) {
                throw new IllegalStateException("Unable to decode title-screen image: " + resourcePath);
            }

            int width = image.getWidth();
            int height = image.getHeight();
            this.imageWidth = width;
            this.imageHeight = height;
            ByteBuffer pixels = BufferUtils.createByteBuffer(width * height * 4);
            int[] rgba = new int[width * height];
            image.getRGB(0, 0, width, height, rgba, 0, width);
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    int argb = rgba[y * width + x];
                    pixels.put((byte) ((argb >> 16) & 0xFF));
                    pixels.put((byte) ((argb >> 8) & 0xFF));
                    pixels.put((byte) (argb & 0xFF));
                    pixels.put((byte) ((argb >> 24) & 0xFF));
                }
            }
            pixels.flip();

            int texture = glGenTextures();
            glBindTexture(GL_TEXTURE_2D, texture);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, width, height, 0, GL_RGBA, GL_UNSIGNED_BYTE, pixels);
            glBindTexture(GL_TEXTURE_2D, 0);
            return texture;
        } catch (IOException e) {
            throw new IllegalStateException("Unable to load title-screen image: " + resourcePath, e);
        }
    }

    private static int createShaderProgram() {
        String vertexSource = """
                #version 330 core
                layout(location=0) in vec2 aPosition;
                layout(location=1) in vec2 aTexCoord;
                uniform vec2 uResolution;
                out vec2 vTexCoord;
                void main() {
                    vec2 clip = vec2((aPosition.x / uResolution.x) * 2.0 - 1.0,
                                     1.0 - (aPosition.y / uResolution.y) * 2.0);
                    vTexCoord = aTexCoord;
                    gl_Position = vec4(clip, 0.0, 1.0);
                }
                """;
        String fragmentSource = """
                #version 330 core
                in vec2 vTexCoord;
                uniform sampler2D uTexture;
                out vec4 fragColor;
                void main() {
                    fragColor = texture(uTexture, vTexCoord);
                }
                """;

        int vertexShader = compileShader(GL_VERTEX_SHADER, vertexSource);
        int fragmentShader = compileShader(GL_FRAGMENT_SHADER, fragmentSource);
        int program = glCreateProgram();
        glAttachShader(program, vertexShader);
        glAttachShader(program, fragmentShader);
        glLinkProgram(program);
        glDeleteShader(vertexShader);
        glDeleteShader(fragmentShader);

        if (glGetProgrami(program, GL_LINK_STATUS) == GL_FALSE) {
            String log = glGetProgramInfoLog(program);
            glDeleteProgram(program);
            throw new IllegalStateException("Title-screen shader linking failed: " + log);
        }
        return program;
    }

    private static int compileShader(int type, String source) {
        int shader = glCreateShader(type);
        glShaderSource(shader, source);
        glCompileShader(shader);
        if (glGetShaderi(shader, GL_COMPILE_STATUS) == GL_FALSE) {
            String log = glGetShaderInfoLog(shader);
            glDeleteShader(shader);
            throw new IllegalStateException("Title-screen shader compile failed: " + log);
        }
        return shader;
    }

    public void close() {
        if (textureId != 0) glDeleteTextures(textureId);
        if (vbo != 0) glDeleteBuffers(vbo);
        if (vao != 0) glDeleteVertexArrays(vao);
        if (shaderProgram != 0) glDeleteProgram(shaderProgram);
    }
}
