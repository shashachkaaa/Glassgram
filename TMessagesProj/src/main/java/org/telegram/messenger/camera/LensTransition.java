package org.telegram.messenger.camera;

import android.opengl.GLES20;
import android.os.SystemClock;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

/**
 * glassGram: a cross-fade over a change of lens in video messages, as the stock camera does. The
 * frame drawn before the first one of the new lens is kept as a snapshot, drawn over the new lens's
 * picture and faded out, instead of the picture jumping from one lens to the other.
 *
 * Frames are kept only while the zoom moves, as that's what changes lenses: copying every frame
 * costs the preview its steadiness. And the fade starts only on the first frame of the new lens,
 * from the frame just before it; a change of lens found out later, or one the camera made by itself
 * with no snapshot to hand, goes without, as fading from an older frame would stop the picture.
 *
 * Used from the camera's GL thread; the encoder draws the same snapshot from its own context,
 * which shares textures with it, through its own {@link Drawer}.
 */
public class LensTransition {

    private static final long FADE_TIME = 320;

    private boolean fading;
    private long fadeStart;
    private final int[] texture = new int[1];
    private int width, height;
    // whether copying from the surface works, found out on the first copy
    private boolean copyChecked, copyWorks;
    private boolean hasSnapshot;
    // the frame the snapshot is of, the frame drawn last, and the change of lens already seen, as frame timestamps
    private long snapshotTime, lastFrameTime, seenChange;
    private Object lastSource;
    private float alpha;
    private Drawer drawer;

    /**
     * After a frame is drawn to the surface and before it's swapped: on the first frame of a new
     * lens, starts the fade; draws the fading snapshot over the frame; and keeps the frame as the
     * next snapshot while a change of lens may come.
     *
     * @param source the camera the frames come from, or null for none that changes lenses
     * @param frameTime the new frame's timestamp, or 0 when no new frame was drawn
     * @param changeTime the timestamp from which frames are of the lens the camera moved to last
     * @param keepSnapshots whether a change of lens may come soon
     */
    public void afterDraw(Object source, long frameTime, long changeTime, boolean keepSnapshots, int surfaceWidth, int surfaceHeight) {
        if (source != lastSource) {
            lastSource = source;
            fading = false;
            hasSnapshot = false;
            lastFrameTime = 0;
            seenChange = changeTime;
        }
        if (source == null) {
            alpha = 0;
            return;
        }
        final long now = SystemClock.uptimeMillis();
        if (frameTime != 0 && changeTime != seenChange && frameTime >= changeTime) {
            seenChange = changeTime;
            if (hasSnapshot && snapshotTime == lastFrameTime && lastFrameTime < changeTime) {
                fading = true;
                fadeStart = now;
            }
        }
        if (fading) {
            final float t = (now - fadeStart) / (float) FADE_TIME;
            if (t >= 1f) {
                fading = false;
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
        if (frameTime == 0) return;
        lastFrameTime = frameTime;
        if (fading) return;
        if (keepSnapshots && surfaceWidth > 0 && surfaceHeight > 0) {
            hasSnapshot = copy(surfaceWidth, surfaceHeight);
            snapshotTime = frameTime;
        } else {
            hasSnapshot = false;
        }
    }

    /** Copies the frame just drawn, on the GPU, from the surface into the snapshot. */
    private boolean copy(int surfaceWidth, int surfaceHeight) {
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
            copyChecked = false;
            // RGB: the camera's surface has no alpha, and a copy from it into RGBA is an error
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGB, width, height, 0, GLES20.GL_RGB, GLES20.GL_UNSIGNED_BYTE, null);
        }
        if (!copyChecked) {
            while (GLES20.glGetError() != GLES20.GL_NO_ERROR) {
                // earlier errors aren't the copy's
            }
        }
        GLES20.glCopyTexSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, 0, 0, width, height);
        if (!copyChecked) {
            // checked once, as asking for errors can stall the driver; a copy that failed would fade in from black
            copyChecked = true;
            copyWorks = GLES20.glGetError() == GLES20.GL_NO_ERROR;
        }
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
        return copyWorks;
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
        width = height = 0;
        hasSnapshot = false;
        fading = false;
        lastSource = null;
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
