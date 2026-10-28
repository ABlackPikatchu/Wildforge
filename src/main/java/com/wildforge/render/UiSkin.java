package com.wildforge.render;

import org.lwjgl.BufferUtils;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.util.List;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL12.GL_CLAMP_TO_EDGE;
import static org.lwjgl.opengl.GL13.*;
import static org.lwjgl.opengl.GL15.*;
import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.opengl.GL30.*;

/**
 * Textured, nearest-neighbor UI skin. Sprite coordinates are pixels within
 * assets/wildforge/ui/forge_ui.png, which is editable independently of Java.
 *
 * Nine-slice borders remain fixed at the chosen UI scale; only the center and
 * edge strips stretch. Positions use HudRenderer's virtual coordinates.
 */
public final class UiSkin implements AutoCloseable {
    private static final int ATLAS_WIDTH = 512;
    private static final int ATLAS_HEIGHT = 256;

    private record Sprite(int x, int y, int width, int height) { }

    private static final Sprite PANEL = new Sprite(0, 0, 64, 64);
    private static final Sprite HOTBAR = new Sprite(64, 0, 48, 48);
    private static final Sprite SLOT = new Sprite(128, 0, 48, 48);
    private static final Sprite SELECTED = new Sprite(176, 0, 48, 48);
    private static final Sprite TOOLTIP = new Sprite(0, 64, 48, 48);
    private static final Sprite DIVIDER = new Sprite(64, 64, 64, 12);
    // Menu sprites share the same atlas as the in-game inventory skin.
    private static final Sprite BUTTON = new Sprite(256, 0, 96, 32);
    private static final Sprite BUTTON_HOVER = new Sprite(256, 34, 96, 32);
    private static final Sprite BUTTON_PRIMARY = new Sprite(256, 68, 96, 32);
    private static final Sprite BUTTON_DISABLED = new Sprite(256, 102, 96, 32);
    private static final Sprite BUTTON_DANGER = new Sprite(256, 136, 96, 32);
    private static final Sprite BUTTON_DANGER_HOVER = new Sprite(368, 0, 96, 32);
    private static final Sprite BUTTON_SELECTED = new Sprite(256, 170, 96, 32);
    private static final Sprite SLIDER_TRACK = new Sprite(256, 210, 96, 12);
    private static final Sprite SLIDER_FILL = new Sprite(256, 225, 96, 12);
    private static final Sprite SLIDER_KNOB = new Sprite(373, 207, 16, 28);

    private final int texture;
    private final int program;
    private final int vao;
    private final int vbo;
    private final int resolutionUniform;

    public UiSkin() {
        texture = loadAtlas();
        program = createProgram();
        resolutionUniform = glGetUniformLocation(program, "uResolution");
        int sampler = glGetUniformLocation(program, "uTexture");
        glUseProgram(program);
        glUniform1i(sampler, 0);
        glUseProgram(0);

        vao = glGenVertexArrays();
        vbo = glGenBuffers();
        glBindVertexArray(vao);
        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        glEnableVertexAttribArray(0);
        glVertexAttribPointer(0, 2, GL_FLOAT, false, 4 * Float.BYTES, 0L);
        glEnableVertexAttribArray(1);
        glVertexAttribPointer(1, 2, GL_FLOAT, false, 4 * Float.BYTES,
                2L * Float.BYTES);
        glBindVertexArray(0);
        glBindBuffer(GL_ARRAY_BUFFER, 0);
    }

    public void panel(List<Float> batch, float x, float y, float w, float h) {
        nineSlice(batch, PANEL, x, y, w, h, 12);
    }

    public void hotbar(List<Float> batch, float x, float y, float w, float h) {
        nineSlice(batch, HOTBAR, x, y, w, h, 10);
    }

    public void tooltip(List<Float> batch, float x, float y, float w, float h) {
        nineSlice(batch, TOOLTIP, x, y, w, h, 7);
    }

    public void divider(List<Float> batch, float x, float y, float w) {
        add(batch, DIVIDER, x, y, w, 6);
    }

    public void slot(List<Float> batch, float x, float y, boolean selected) {
        add(batch, selected ? SELECTED : SLOT, x, y, 48, 48);
    }

    /** Textured menu frame, using the same forged metal as inventory panels. */
    public void menuPanel(List<Float> batch, float x, float y, float w, float h) {
        nineSlice(batch, PANEL, x, y, w, h, 12);
    }

    /** Compact bottom strip behind main-menu buttons. */
    public void menuFooter(List<Float> batch, float x, float y, float w, float h) {
        nineSlice(batch, HOTBAR, x, y, w, h, 10);
    }

    /** Button states are actual PNG sprites rather than rectangles drawn in Java. */
    public void menuButton(List<Float> batch, float x, float y, float w, float h,
                           boolean hovered, boolean emphasized, boolean disabled,
                           boolean danger, boolean selected) {
        Sprite sprite = disabled ? BUTTON_DISABLED
                : selected ? BUTTON_SELECTED
                : danger ? (hovered ? BUTTON_DANGER_HOVER : BUTTON_DANGER)
                : hovered ? BUTTON_HOVER
                : emphasized ? BUTTON_PRIMARY : BUTTON;
        nineSlice(batch, sprite, x, y, w, h, 8);
    }

    /** Nine-slice track and progress; fixed pixel-art knob, independent of FOV. */
    public void menuSlider(List<Float> batch, float x, float y, float w, float h,
                           float progress, float sizeScale) {
        float ratio = Math.max(0f, Math.min(1f, progress));
        nineSlice(batch, SLIDER_TRACK, x, y, w, h, 4);
        float fillW = w * ratio;
        if (fillW > 6f) {
            nineSlice(batch, SLIDER_FILL, x, y, Math.max(8f, fillW), h, 4);
        }
        float knobWidth = 16f * sizeScale;
        float knobHeight = 28f * sizeScale;
        add(batch, SLIDER_KNOB, x + fillW - knobWidth / 2f,
                y + (h - knobHeight) / 2f, knobWidth, knobHeight);
    }

    private static void nineSlice(List<Float> batch, Sprite sprite,
                                  float x, float y, float w, float h,
                                  int border) {
        if (w < border * 2 || h < border * 2) return;
        int innerSourceW = sprite.width - border * 2;
        int innerSourceH = sprite.height - border * 2;
        float innerDestW = w - border * 2;
        float innerDestH = h - border * 2;

        int[] sx = {sprite.x, sprite.x + border, sprite.x + sprite.width - border};
        int[] sy = {sprite.y, sprite.y + border, sprite.y + sprite.height - border};
        int[] sw = {border, innerSourceW, border};
        int[] sh = {border, innerSourceH, border};
        float[] dx = {x, x + border, x + border + innerDestW};
        float[] dy = {y, y + border, y + border + innerDestH};
        float[] dw = {border, innerDestW, border};
        float[] dh = {border, innerDestH, border};
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 3; col++) {
                quad(batch, dx[col], dy[row], dw[col], dh[row],
                        sx[col], sy[row], sw[col], sh[row]);
            }
        }
    }

    private static void add(List<Float> batch, Sprite sprite,
                            float x, float y, float w, float h) {
        quad(batch, x, y, w, h,
                sprite.x, sprite.y, sprite.width, sprite.height);
    }

    private static void quad(List<Float> batch,
                             float x, float y, float w, float h,
                             int sourceX, int sourceY, int sourceW, int sourceH) {
        float u0 = sourceX / (float) ATLAS_WIDTH;
        float v0 = sourceY / (float) ATLAS_HEIGHT;
        float u1 = (sourceX + sourceW) / (float) ATLAS_WIDTH;
        float v1 = (sourceY + sourceH) / (float) ATLAS_HEIGHT;
        vertex(batch, x, y, u0, v0);
        vertex(batch, x + w, y, u1, v0);
        vertex(batch, x + w, y + h, u1, v1);
        vertex(batch, x, y, u0, v0);
        vertex(batch, x + w, y + h, u1, v1);
        vertex(batch, x, y + h, u0, v1);
    }

    private static void vertex(List<Float> batch,
                               float x, float y, float u, float v) {
        batch.add(x);
        batch.add(y);
        batch.add(u);
        batch.add(v);
    }

    /** Draw one layer; the caller chooses the order relative to item icons/text. */
    public void render(List<Float> data, int width, int height) {
        if (data.isEmpty()) return;
        FloatBuffer buffer = BufferUtils.createFloatBuffer(data.size());
        for (float value : data) buffer.put(value);
        buffer.flip();

        boolean depth = glIsEnabled(GL_DEPTH_TEST);
        boolean cull = glIsEnabled(GL_CULL_FACE);
        boolean blend = glIsEnabled(GL_BLEND);
        glDisable(GL_DEPTH_TEST);
        glDisable(GL_CULL_FACE);
        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);

        glUseProgram(program);
        glUniform2f(resolutionUniform, width, height);
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, texture);
        glBindVertexArray(vao);
        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        glBufferData(GL_ARRAY_BUFFER, buffer, GL_DYNAMIC_DRAW);
        glDrawArrays(GL_TRIANGLES, 0, data.size() / 4);
        glBindBuffer(GL_ARRAY_BUFFER, 0);
        glBindVertexArray(0);
        glBindTexture(GL_TEXTURE_2D, 0);
        glUseProgram(0);

        if (blend) glEnable(GL_BLEND); else glDisable(GL_BLEND);
        if (cull) glEnable(GL_CULL_FACE); else glDisable(GL_CULL_FACE);
        if (depth) glEnable(GL_DEPTH_TEST); else glDisable(GL_DEPTH_TEST);
    }

    private static int loadAtlas() {
        String path = "assets/wildforge/ui/forge_ui.png";
        try (InputStream stream = UiSkin.class.getClassLoader().getResourceAsStream(path)) {
            if (stream == null) throw new IOException("Missing UI atlas: " + path);
            BufferedImage image = ImageIO.read(stream);
            if (image == null || image.getWidth() != ATLAS_WIDTH
                    || image.getHeight() != ATLAS_HEIGHT) {
                throw new IOException("UI atlas must be 512x256 RGBA: " + path);
            }
            ByteBuffer pixels = BufferUtils.createByteBuffer(ATLAS_WIDTH * ATLAS_HEIGHT * 4);
            int[] colors = new int[ATLAS_WIDTH * ATLAS_HEIGHT];
            image.getRGB(0, 0, ATLAS_WIDTH, ATLAS_HEIGHT, colors, 0, ATLAS_WIDTH);
            for (int argb : colors) {
                pixels.put((byte) ((argb >>> 16) & 255));
                pixels.put((byte) ((argb >>> 8) & 255));
                pixels.put((byte) (argb & 255));
                pixels.put((byte) ((argb >>> 24) & 255));
            }
            pixels.flip();
            int id = glGenTextures();
            glBindTexture(GL_TEXTURE_2D, id);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, ATLAS_WIDTH, ATLAS_HEIGHT,
                    0, GL_RGBA, GL_UNSIGNED_BYTE, pixels);
            glBindTexture(GL_TEXTURE_2D, 0);
            return id;
        } catch (IOException e) {
            throw new IllegalStateException("Unable to load Wildforge UI atlas", e);
        }
    }

    private static int createProgram() {
        String vertexSource = """
                #version 330 core
                layout(location=0) in vec2 aPosition;
                layout(location=1) in vec2 aTexCoord;
                uniform vec2 uResolution;
                out vec2 vTexCoord;
                void main() {
                    vec2 clip = vec2(aPosition.x / uResolution.x * 2.0 - 1.0,
                                     1.0 - aPosition.y / uResolution.y * 2.0);
                    gl_Position = vec4(clip, 0.0, 1.0);
                    vTexCoord = aTexCoord;
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
        int vs = compile(GL_VERTEX_SHADER, vertexSource);
        int fs = compile(GL_FRAGMENT_SHADER, fragmentSource);
        int id = glCreateProgram();
        glAttachShader(id, vs);
        glAttachShader(id, fs);
        glLinkProgram(id);
        glDeleteShader(vs);
        glDeleteShader(fs);
        if (glGetProgrami(id, GL_LINK_STATUS) == GL_FALSE) {
            String log = glGetProgramInfoLog(id);
            glDeleteProgram(id);
            throw new IllegalStateException("UI skin shader linking failed: " + log);
        }
        return id;
    }

    private static int compile(int type, String source) {
        int id = glCreateShader(type);
        glShaderSource(id, source);
        glCompileShader(id);
        if (glGetShaderi(id, GL_COMPILE_STATUS) == GL_FALSE) {
            String log = glGetShaderInfoLog(id);
            glDeleteShader(id);
            throw new IllegalStateException("UI skin shader compilation failed: " + log);
        }
        return id;
    }

    @Override
    public void close() {
        glDeleteTextures(texture);
        glDeleteBuffers(vbo);
        glDeleteVertexArrays(vao);
        glDeleteProgram(program);
    }
}
