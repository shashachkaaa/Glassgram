package org.telegram.messenger.camera;

import android.opengl.GLES20;
import android.os.SystemClock;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

/**
 * glassGram: a cross-fade over a change of lens in video messages, as the stock camera does. The
 * picture drawn last is kept as a snapshot; when the lens changes, the snapshot is drawn over the
 * new lens's picture and fades out, instead of the picture jumping from one lens to the other.
 *
 * Used from the camera's GL thread; the encoder draws the same snapshot from its own context,
 * which shares textures with it, through its own {@link Drawer}.
 */
public class LensTransition {

    private static final long FADE_TIME = 320;
    private static final int ROLLING = 0, HELD = 1, FADING = 2;

    private int state = ROLLING;
    private long fadeStart;
    private final int[] texture = new int[1];
    private int width, height;
    private boolean hasSnapshot;
    private float alpha;
    private Drawer drawer;

    /**
     * The lens is changing. With waitForFrames the picture stops until the new lens sends frames
     * (a camera switched by hand), and the fade starts with them; else it starts now.
     */
    public void begin(boolean waitForFrames) {
        if (!hasSnapshot) return;
        state = waitForFrames ? HELD : FADING;
        fadeStart = SystemClock.uptimeMillis();
    }

    /**
     * After a frame is drawn to the surface and before it's swapped: draws the fading snapshot over
     * it, or else keeps the frame as the next snapshot when lens changes may come.
     */
    public void afterDraw(boolean newFrame, int surfaceWidth, int surfaceHeight, boolean keepSnapshots) {
        final long now = SystemClock.uptimeMillis();
        if (state == HELD && newFrame) {
            state = FADING;
            fadeStart = now;
        }
        if (state == FADING) {
            final float t = (now - fadeStart) / (float) FADE_TIME;
            if (t >= 1f) {
                state = ROLLING;
                alpha = 0;
            } else {
                // ease out: most of the new lens shows early, the rest settles
                alpha = (1f - t) * (1f - t);
                if (drawer == null) {
                    drawer = new Drawer();
                }
                drawer.draw(texture[0], alpha);
            }
        } else {
            alpha = 0;
        }
        if (state == ROLLING && keepSnapshots && newFrame && surfaceWidth > 0 && surfaceHeight > 0) {
            if (texture[0] == 0) {
                GLES20.glGenTextures(1, texture, 0);
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture[0]);
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
            } else {
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture[0]);
            }
            if (width != surfaceWidth || height != surfaceHeight) {
                width = surfaceWidth;
                height = surfaceHeight;
                GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, width, height, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null);
            }
            // the frame just drawn, copied on the GPU from the surface
            GLES20.glCopyTexSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, 0, 0, width, height);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
            hasSnapshot = true;
        }
    }

    /** How strongly the snapshot shows over the frame drawn last, for the encoder. */
    public float getAlpha() {
        return alpha;
    }

    public int getTexture() {
        return texture[0];
    }

    public void release() {
        if (texture[0] != 0) {
            GLES20.glDeleteTextures(1, texture, 0);
            texture[0] = 0;
        }
        hasSnapshot = false;
        state = ROLLING;
        alpha = 0;
    }

    /** Draws the snapshot over the whole viewport with an alpha; one per GL context. */
    public static class Drawer {

        private static final String VERTEX_SHADER =
                "attribute vec4 aPosition;\n" +
                "attribute vec2 aTextureCoord;\n" +
                "varying vec2 vTextureCoord;\n" +
                "void main() {\n" +
                "   gl_Position = aPosition;\n" +
                "   vTextureCoord = aTextureCoord;\n" +
                "}\n";
        private static final String FRAGMENT_SHADER =
                "precision mediump float;\n" +
                "varying vec2 vTextureCoord;\n" +
                "uniform sampler2D sTexture;\n" +
                "uniform float alpha;\n" +
                "void main() {\n" +
                "   gl_FragColor = vec4(texture2D(sTexture, vTextureCoord).rgb, alpha);\n" +
                "}\n";

        private int program;
        private int positionHandle, textureHandle, alphaHandle;
        private final FloatBuffer positions = buffer(new float[] { -1, -1, 1, -1, -1, 1, 1, 1 });
        private final FloatBuffer textureCoords = buffer(new float[] { 0, 0, 1, 0, 0, 1, 1, 1 });

        public Drawer() {
            final int vertex = load(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER);
            final int fragment = load(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER);
            if (vertex == 0 || fragment == 0) return;
            program = GLES20.glCreateProgram();
            GLES20.glAttachShader(program, vertex);
            GLES20.glAttachShader(program, fragment);
            GLES20.glLinkProgram(program);
            final int[] linked = new int[1];
            GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linked, 0);
            if (linked[0] == 0) {
                GLES20.glDeleteProgram(program);
                program = 0;
                return;
            }
            positionHandle = GLES20.glGetAttribLocation(program, "aPosition");
            textureHandle = GLES20.glGetAttribLocation(program, "aTextureCoord");
            alphaHandle = GLES20.glGetUniformLocation(program, "alpha");
        }

        /** Draws with blending on, and leaves blending on, set to the usual source-over. */
        public void draw(int texture, float alpha) {
            if (program == 0 || texture == 0 || alpha <= 0) return;
            GLES20.glUseProgram(program);
            GLES20.glEnable(GLES20.GL_BLEND);
            // the picture's own alpha stays, so the preview doesn't turn see-through while fading
            GLES20.glBlendFuncSeparate(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA, GLES20.GL_ZERO, GLES20.GL_ONE);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture);
            GLES20.glVertexAttribPointer(positionHandle, 2, GLES20.GL_FLOAT, false, 8, positions);
            GLES20.glEnableVertexAttribArray(positionHandle);
            GLES20.glVertexAttribPointer(textureHandle, 2, GLES20.GL_FLOAT, false, 8, textureCoords);
            GLES20.glEnableVertexAttribArray(textureHandle);
            GLES20.glUniform1f(alphaHandle, alpha);
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
            GLES20.glDisableVertexAttribArray(positionHandle);
            GLES20.glDisableVertexAttribArray(textureHandle);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
            GLES20.glUseProgram(0);
            GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);
        }

        private static int load(int type, String source) {
            final int shader = GLES20.glCreateShader(type);
            GLES20.glShaderSource(shader, source);
            GLES20.glCompileShader(shader);
            final int[] compiled = new int[1];
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0);
            if (compiled[0] == 0) {
                GLES20.glDeleteShader(shader);
                return 0;
            }
            return shader;
        }

        private static FloatBuffer buffer(float[] values) {
            final FloatBuffer buffer = ByteBuffer.allocateDirect(values.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
            buffer.put(values).position(0);
            return buffer;
        }
    }
}
