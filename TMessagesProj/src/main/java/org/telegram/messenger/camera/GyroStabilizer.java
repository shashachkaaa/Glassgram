package org.telegram.messenger.camera;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;

import org.telegram.messenger.FileLog;

/**
 * glassGram: stabilization of video messages by the gyroscope, for cameras that don't smooth the
 * picture they give to apps.
 *
 * The gyroscope gives the real orientation of the phone at the moment each frame was taken. A
 * virtual camera follows it smoothly: it keeps steady pans, but not shake. Each frame is then
 * shown as the virtual camera would see it, by shifting and turning the picture inside a margin
 * cut from its edges.
 *
 * Coordinates: the phone's sensor axes (x right, y up, z out of the screen in its natural
 * orientation), and the picture as shown upright in that orientation, x right and y up.
 */
public class GyroStabilizer implements SensorEventListener {

    /** How much the picture is enlarged to leave a margin for the shifts. */
    public static final float MARGIN_ZOOM = 1.2f;
    /** The most the picture turns around its center, in radians. The video is round, so turning costs no margin. */
    private static final float MAX_ROLL = 0.2f;
    /** The virtual camera: a critically damped follower that keeps a steady pan without lag. */
    private static final float FOLLOW_RATE = 2.4f;
    private static final float FOLLOW_A = 2 * FOLLOW_RATE;
    private static final float FOLLOW_B = FOLLOW_RATE * FOLLOW_RATE;

    private static final int HISTORY = 1024;
    // a ring of (time, orientation) samples of the real phone, orientation as a unit quaternion
    private final long[] times = new long[HISTORY];
    private final float[] quats = new float[HISTORY * 4];
    private final float[] rates = new float[3];
    private int count, head;
    private final float[] q = {1, 0, 0, 0};
    private long lastSensorTime;

    private final SensorManager sensorManager;
    private final Sensor gyroscope;
    private HandlerThread thread;

    // the virtual camera, used only from the thread that draws
    private final float[] virtual = {1, 0, 0, 0};
    private final float[] virtualRate = new float[3];
    private long lastFrameTime;
    private Object lastSource;

    private final float[] real = new float[4];
    private final float[] rel = new float[3];
    private final float[] matrix = new float[16];

    public GyroStabilizer(Context context) {
        sensorManager = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
        gyroscope = sensorManager == null ? null : sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
    }

    public static boolean isAvailable(Context context) {
        final SensorManager manager = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
        return manager != null && manager.getDefaultSensor(Sensor.TYPE_GYROSCOPE) != null;
    }

    public void start() {
        if (gyroscope == null || thread != null) return;
        thread = new HandlerThread("glassgram_gyro");
        thread.start();
        try {
            // 200 times a second: the most apps get without asking for more
            sensorManager.registerListener(this, gyroscope, 5000, new Handler(thread.getLooper()));
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    public void stop() {
        if (thread == null) return;
        try {
            sensorManager.unregisterListener(this);
        } catch (Exception e) {
            FileLog.e(e);
        }
        thread.quitSafely();
        thread = null;
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        final long time = event.timestamp;
        synchronized (this) {
            if (lastSensorTime != 0) {
                final float dt = (time - lastSensorTime) / 1e9f;
                if (dt > 0 && dt < 0.1f) {
                    // body rates: the new orientation is the old one turned by rate * dt in the phone's own axes
                    rotate(q, (rates[0] + event.values[0]) * 0.5f * dt, (rates[1] + event.values[1]) * 0.5f * dt, (rates[2] + event.values[2]) * 0.5f * dt);
                }
            }
            lastSensorTime = time;
            rates[0] = event.values[0];
            rates[1] = event.values[1];
            rates[2] = event.values[2];
            times[head] = time;
            System.arraycopy(q, 0, quats, head * 4, 4);
            head = (head + 1) % HISTORY;
            if (count < HISTORY) count++;
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {

    }

    /** The real orientation at a moment in the gyroscope's clock; false before the first samples. */
    private synchronized boolean orientationAt(long time, float[] out) {
        if (count == 0) return false;
        final int newest = (head - 1 + HISTORY) % HISTORY;
        if (time >= times[newest]) {
            System.arraycopy(quats, newest * 4, out, 0, 4);
            final float dt = Math.min((time - times[newest]) / 1e9f, 0.05f);
            rotate(out, rates[0] * dt, rates[1] * dt, rates[2] * dt);
            return true;
        }
        for (int i = 1; i < count; ++i) {
            final int index = (newest - i + HISTORY) % HISTORY;
            if (times[index] <= time) {
                final int next = (index + 1) % HISTORY;
                final float t = (time - times[index]) / (float) Math.max(1, times[next] - times[index]);
                nlerp(quats, index * 4, quats, next * 4, t, out);
                return true;
            }
        }
        final int oldest = (head - count + HISTORY) % HISTORY;
        System.arraycopy(quats, oldest * 4, out, 0, 4);
        return true;
    }

    /**
     * The texture matrix for a frame: {@code stMatrix}, the SurfaceTexture's own matrix, after the
     * stabilizing shift and turn. Texture coordinates before it run over the upright picture, and
     * the frame draws the square {@code 0.5 ± halfWidth, 0.5 ± halfHeight} of them.
     *
     * @param source the camera the frame came from; the virtual camera restarts when it changes
     * @param frameTime when the frame was taken, in {@link SystemClock#elapsedRealtimeNanos()} time
     * @param focal the focal length in units of the drawn square's half width
     * @param front whether the frame is from the front camera, mirrored like the preview
     */
    public void apply(Object source, long frameTime, float focal, boolean front, float halfWidth, float halfHeight, float[] stMatrix, float[] out) {
        if (!orientationAt(frameTime, real)) {
            System.arraycopy(stMatrix, 0, out, 0, 16);
            return;
        }
        if (source != lastSource || lastFrameTime == 0 || frameTime < lastFrameTime) {
            lastSource = source;
            System.arraycopy(real, 0, virtual, 0, 4);
            virtualRate[0] = virtualRate[1] = virtualRate[2] = 0;
        } else {
            final float dt = Math.min((frameTime - lastFrameTime) / 1e9f, 0.1f);
            relative(virtual, real, rel);
            for (int i = 0; i < 3; ++i) {
                virtualRate[i] += FOLLOW_B * rel[i] * dt;
            }
            rotate(virtual, (virtualRate[0] + FOLLOW_A * rel[0]) * dt, (virtualRate[1] + FOLLOW_A * rel[1]) * dt, (virtualRate[2] + FOLLOW_A * rel[2]) * dt);
        }
        lastFrameTime = frameTime;

        // how far the real phone is turned from the virtual camera, kept inside the margin
        relative(virtual, real, rel);
        final float maxTurn = (1f - 1f / MARGIN_ZOOM) / Math.max(focal, 0.1f);
        final float turn = (float) Math.hypot(rel[0], rel[1]);
        boolean clamped = false;
        if (turn > maxTurn) {
            rel[0] *= maxTurn / turn;
            rel[1] *= maxTurn / turn;
            clamped = true;
        }
        if (Math.abs(rel[2]) > MAX_ROLL) {
            rel[2] = Math.signum(rel[2]) * MAX_ROLL;
            clamped = true;
        }
        if (clamped) {
            // the virtual camera is dragged along: virtual = real turned back by rel
            System.arraycopy(real, 0, virtual, 0, 4);
            rotate(virtual, -rel[0], -rel[1], -rel[2]);
        }

        // A point the virtual camera sees at p shows in the real frame at rotate(-roll) * p + shift.
        // Back camera, looking along -z: turning by +y moves the picture right, by +x down.
        // Front camera, looking along +z and mirrored: the shift goes the other way.
        float shiftX = focal * rel[1];
        float shiftY = -focal * rel[0];
        if (front) {
            shiftX = -shiftX;
            shiftY = -shiftY;
        }
        final float[] m = matrix;
        android.opengl.Matrix.setIdentityM(m, 0);
        android.opengl.Matrix.translateM(m, 0, 0.5f, 0.5f, 0);
        android.opengl.Matrix.scaleM(m, 0, halfWidth, halfHeight, 1);
        android.opengl.Matrix.translateM(m, 0, shiftX, shiftY, 0);
        android.opengl.Matrix.rotateM(m, 0, (float) Math.toDegrees(-rel[2]), 0, 0, 1);
        android.opengl.Matrix.scaleM(m, 0, 1f / (MARGIN_ZOOM * halfWidth), 1f / (MARGIN_ZOOM * halfHeight), 1);
        android.opengl.Matrix.translateM(m, 0, -0.5f, -0.5f, 0);
        android.opengl.Matrix.multiplyMM(out, 0, stMatrix, 0, m, 0);
    }

    /** The real camera's turn from the virtual one, as a rotation vector in the virtual camera's axes. */
    private void relative(float[] from, float[] to, float[] out) {
        // conj(from) * to
        final float w1 = from[0], x1 = -from[1], y1 = -from[2], z1 = -from[3];
        final float w2 = to[0], x2 = to[1], y2 = to[2], z2 = to[3];
        float w = w1 * w2 - x1 * x2 - y1 * y2 - z1 * z2;
        float x = w1 * x2 + x1 * w2 + y1 * z2 - z1 * y2;
        float y = w1 * y2 - x1 * z2 + y1 * w2 + z1 * x2;
        float z = w1 * z2 + x1 * y2 - y1 * x2 + z1 * w2;
        if (w < 0) {
            w = -w; x = -x; y = -y; z = -z;
        }
        final float s = (float) Math.sqrt(x * x + y * y + z * z);
        final float angle = 2f * (float) Math.atan2(s, w);
        final float k = s < 1e-6f ? 2f : angle / s;
        out[0] = x * k;
        out[1] = y * k;
        out[2] = z * k;
    }

    /** Turns the orientation by a rotation vector in its own axes. */
    private static void rotate(float[] q, float rx, float ry, float rz) {
        final float angle = (float) Math.sqrt(rx * rx + ry * ry + rz * rz);
        final float w2, x2, y2, z2;
        if (angle < 1e-8f) {
            w2 = 1; x2 = rx * 0.5f; y2 = ry * 0.5f; z2 = rz * 0.5f;
        } else {
            final float s = (float) Math.sin(angle * 0.5f) / angle;
            w2 = (float) Math.cos(angle * 0.5f); x2 = rx * s; y2 = ry * s; z2 = rz * s;
        }
        final float w1 = q[0], x1 = q[1], y1 = q[2], z1 = q[3];
        float w = w1 * w2 - x1 * x2 - y1 * y2 - z1 * z2;
        float x = w1 * x2 + x1 * w2 + y1 * z2 - z1 * y2;
        float y = w1 * y2 - x1 * z2 + y1 * w2 + z1 * x2;
        float z = w1 * z2 + x1 * y2 - y1 * x2 + z1 * w2;
        final float n = (float) Math.sqrt(w * w + x * x + y * y + z * z);
        q[0] = w / n; q[1] = x / n; q[2] = y / n; q[3] = z / n;
    }

    private static void nlerp(float[] a, int ao, float[] b, int bo, float t, float[] out) {
        float dot = a[ao] * b[bo] + a[ao + 1] * b[bo + 1] + a[ao + 2] * b[bo + 2] + a[ao + 3] * b[bo + 3];
        final float sign = dot < 0 ? -1 : 1;
        float n = 0;
        for (int i = 0; i < 4; ++i) {
            out[i] = a[ao + i] * (1 - t) + sign * b[bo + i] * t;
            n += out[i] * out[i];
        }
        n = (float) Math.sqrt(n);
        for (int i = 0; i < 4; ++i) {
            out[i] /= n;
        }
    }
}
