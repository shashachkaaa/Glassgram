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

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

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
 *
 * With the horizon kept level, the turn of the picture around its center follows gravity instead
 * of the virtual camera: the picture turns against the phone's tilt, by any angle, as a round
 * video can turn without losing anything to the margin.
 */
public class GyroStabilizer implements SensorEventListener {

    /** How much the picture is enlarged to leave a margin for the shifts, from 1x up. */
    public static final float MARGIN_ZOOM = 1.2f;

    /**
     * The margin at a zoom. A turn of the phone moves the picture in proportion to the focal
     * length, so below 1x, on the ultra-wide lens, the same turns need less of a margin: it shrinks
     * with the zoom, leaving the stabilization as much room to turn as at 1x, and the ultra-wide
     * lens its width.
     */
    public static float marginFor(float zoom) {
        if (zoom >= 1f) return MARGIN_ZOOM;
        return 1f / (1f - (1f - 1f / MARGIN_ZOOM) * Math.max(zoom, 0.1f));
    }
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

    // gravity for the level horizon: a ring of (time, up vector in the phone's axes) samples
    private static final int GRAVITY_HISTORY = 256;
    private final long[] gravityTimes = new long[GRAVITY_HISTORY];
    private final float[] gravityValues = new float[GRAVITY_HISTORY * 3];
    private int gravityCount, gravityHead;
    private Sensor gravity;
    private boolean gravityFromAccelerometer;
    private final float[] lowPass = new float[3];
    private boolean horizon;
    private float displayRoll;
    private final float[] up = new float[3];

    // the virtual camera, used only from the thread that draws
    private final float[] virtual = {1, 0, 0, 0};
    private final float[] virtualRate = new float[3];
    private long lastFrameTime;
    private Object lastSource;

    private final float[] real = new float[4];
    private final float[] rel = new float[3];

    public GyroStabilizer(Context context) {
        sensorManager = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
        gyroscope = sensorManager == null ? null : sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
    }

    public static boolean isAvailable(Context context) {
        final SensorManager manager = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
        return manager != null && manager.getDefaultSensor(Sensor.TYPE_GYROSCOPE) != null;
    }

    /**
     * Keeps the horizon level, see the class comment; before {@link #start}. The display rotation
     * is the screen's from the phone's natural orientation, in degrees, as the picture is upright
     * on the screen.
     */
    public void setHorizon(boolean enabled, int displayDegrees) {
        horizon = enabled;
        displayRoll = (float) Math.toRadians(displayDegrees);
    }

    public void start() {
        if (gyroscope == null || thread != null) return;
        thread = new HandlerThread("glassgram_gyro");
        thread.start();
        final Handler handler = new Handler(thread.getLooper());
        try {
            // 200 times a second: the most apps get without asking for more
            sensorManager.registerListener(this, gyroscope, 5000, handler);
        } catch (Exception e) {
            FileLog.e(e);
        }
        if (horizon) {
            // the fused gravity is steady through shake; the raw accelerometer is smoothed when it's all there is
            gravity = sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY);
            gravityFromAccelerometer = gravity == null;
            if (gravity == null) {
                gravity = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
            }
            if (gravity != null) {
                try {
                    sensorManager.registerListener(this, gravity, 10000, handler);
                } catch (Exception e) {
                    FileLog.e(e);
                }
            }
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
        if (event.sensor != gyroscope) {
            onGravity(time, event.values);
            return;
        }
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

    private synchronized void onGravity(long time, float[] values) {
        float x = values[0], y = values[1], z = values[2];
        if (gravityFromAccelerometer) {
            if (gravityCount == 0) {
                lowPass[0] = x;
                lowPass[1] = y;
                lowPass[2] = z;
            } else {
                lowPass[0] += (x - lowPass[0]) * 0.15f;
                lowPass[1] += (y - lowPass[1]) * 0.15f;
                lowPass[2] += (z - lowPass[2]) * 0.15f;
            }
            x = lowPass[0];
            y = lowPass[1];
            z = lowPass[2];
        }
        gravityTimes[gravityHead] = time;
        gravityValues[gravityHead * 3] = x;
        gravityValues[gravityHead * 3 + 1] = y;
        gravityValues[gravityHead * 3 + 2] = z;
        gravityHead = (gravityHead + 1) % GRAVITY_HISTORY;
        if (gravityCount < GRAVITY_HISTORY) gravityCount++;
    }

    /**
     * Which way is up at a moment, in the phone's axes, not normalized; false before the first
     * samples. At rest the sensors read up, against gravity: (0, g, 0) held upright.
     */
    private synchronized boolean upAt(long time, float[] out) {
        if (gravityCount == 0) return false;
        final int newest = (gravityHead - 1 + GRAVITY_HISTORY) % GRAVITY_HISTORY;
        int index = newest;
        if (time < gravityTimes[newest]) {
            for (int i = 1; i < gravityCount; ++i) {
                final int previous = (newest - i + GRAVITY_HISTORY) % GRAVITY_HISTORY;
                if (gravityTimes[previous] <= time) {
                    final int next = (previous + 1) % GRAVITY_HISTORY;
                    final float t = (time - gravityTimes[previous]) / (float) Math.max(1, gravityTimes[next] - gravityTimes[previous]);
                    for (int k = 0; k < 3; ++k) {
                        out[k] = gravityValues[previous * 3 + k] * (1 - t) + gravityValues[next * 3 + k] * t;
                    }
                    return true;
                }
                index = previous;
            }
        }
        System.arraycopy(gravityValues, index * 3, out, 0, 3);
        return true;
    }

    /** An angle brought to -pi..pi. */
    private static float wrap(float angle) {
        while (angle > Math.PI) angle -= 2 * Math.PI;
        while (angle < -Math.PI) angle += 2 * Math.PI;
        return angle;
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

    /** Where the camera starts reading a frame out, as an edge of the upright picture. */
    public static final int READOUT_TOP = 0, READOUT_RIGHT = 1, READOUT_BOTTOM = 2, READOUT_LEFT = 3;
    /** Strips across the readout, each turned for the moment it was read. */
    public static final int STRIPS = 12;

    /**
     * A triangle strip over the drawn square: positions (x, y, 0) in its own -1..1 coordinates and
     * texture coordinates, which carry the stabilization; the SurfaceTexture's matrix goes after them.
     */
    public static class Mesh {
        public final FloatBuffer positions;
        public final FloatBuffer textureCoords;
        public final int count;

        Mesh(int count) {
            this.count = count;
            positions = ByteBuffer.allocateDirect(count * 3 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
            textureCoords = ByteBuffer.allocateDirect(count * 2 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        }
    }

    // a few meshes in turn, as the encoder draws a frame a little after the preview did
    private final Mesh[] meshes = { new Mesh((STRIPS + 1) * 2), new Mesh((STRIPS + 1) * 2), new Mesh((STRIPS + 1) * 2), new Mesh((STRIPS + 1) * 2) };
    private int meshIndex;
    private final float[] rowReal = new float[4];
    private final float[] rowRel = new float[3];

    /**
     * The stabilized mesh for a frame, or null before the gyroscope has anything. The camera reads
     * a frame out row by row over its rolling shutter skew, so while the phone shakes, each row is
     * taken at a different turn; turning the whole frame as one leaves the rows wobbling like jelly.
     * So each strip across the readout is turned back for the moment its rows were taken.
     *
     * @param source the camera the frame came from; the virtual camera restarts when it changes
     * @param exposureStart when the first row's exposure started, in {@link SystemClock#elapsedRealtimeNanos()} time
     * @param exposure the exposure time of a row, and skew the time from the first row to the last
     * @param readout the edge of the upright picture the readout starts at
     * @param focal the focal length in units of the drawn square's half width
     * @param front whether the frame is from the front camera, mirrored like the preview
     * @param halfWidth the drawn square is {@code 0.5 ± halfWidth, 0.5 ± halfHeight} of the upright texture
     * @param margin how much the picture is enlarged for the shifts, see {@link #marginFor}
     */
    public Mesh apply(Object source, long exposureStart, long exposure, long skew, int readout, float focal, boolean front, float halfWidth, float halfHeight, float margin) {
        final long frameTime = exposureStart + exposure / 2 + skew / 2;
        if (!orientationAt(frameTime, real)) {
            return null;
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

        // how far the real phone is turned from the virtual camera in the middle of the frame, kept inside the margin
        relative(virtual, real, rel);
        final float maxTurn = (1f - 1f / margin) / Math.max(focal, 0.1f);
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

        // The turn of the picture: the virtual camera's, or with the level horizon, the phone's tilt on
        // the screen from upright, the angle of up within the screen. Both are turns of the real phone
        // around z, so either counters itself. Looking straight up or down there's no horizon to keep,
        // and the turn goes back to the virtual camera's.
        float roll = rel[2];
        if (horizon && upAt(frameTime, up)) {
            final float length = (float) Math.sqrt(up[0] * up[0] + up[1] * up[1] + up[2] * up[2]);
            if (length > 0) {
                final float inScreen = (float) Math.hypot(up[0], up[1]) / length;
                final float weight = Math.max(0f, Math.min(1f, (inScreen - 0.35f) / 0.3f));
                final float tilt = wrap((float) Math.atan2(up[0], up[1]) - displayRoll);
                roll = rel[2] + wrap(tilt - rel[2]) * weight;
            }
        }

        meshIndex = (meshIndex + 1) % meshes.length;
        final Mesh mesh = meshes[meshIndex];
        final boolean across = readout == READOUT_LEFT || readout == READOUT_RIGHT;
        for (int k = 0; k <= STRIPS; ++k) {
            // the strip edge, as a line across the readout in the square's -1..1 coordinates
            final float along = -1f + 2f * k / STRIPS;
            final float texture = 0.5f + along * (across ? halfWidth : halfHeight);
            final float fraction = readout == READOUT_LEFT || readout == READOUT_BOTTOM ? texture : 1f - texture;
            final long rowTime = exposureStart + exposure / 2 + (long) (Math.max(0f, Math.min(1f, fraction)) * skew);
            if (!orientationAt(rowTime, rowReal)) {
                System.arraycopy(real, 0, rowReal, 0, 4);
            }
            relative(virtual, rowReal, rowRel);

            // A point the virtual camera sees at p shows in the real frame at rotate(-roll) * p + shift.
            // Back camera, looking along -z: turning by +y moves the picture right, by +x down.
            // Front camera, looking along +z and mirrored: the shift goes the other way.
            float shiftX = focal * rowRel[1];
            float shiftY = -focal * rowRel[0];
            if (front) {
                shiftX = -shiftX;
                shiftY = -shiftY;
            }
            // each strip's own turn over the frame's, from the rolling shutter, on top of the picture's
            final float rowRoll = roll + rowRel[2] - rel[2];
            final float cos = (float) Math.cos(-rowRoll), sin = (float) Math.sin(-rowRoll);
            for (int side = 0; side < 2; ++side) {
                final float other = side == 0 ? -1f : 1f;
                final float x = across ? along : other;
                final float y = across ? other : along;
                final int vertex = k * 2 + side;
                mesh.positions.put(vertex * 3, x);
                mesh.positions.put(vertex * 3 + 1, y);
                mesh.positions.put(vertex * 3 + 2, 0f);
                final float qx = x / margin, qy = y / margin;
                mesh.textureCoords.put(vertex * 2, 0.5f + halfWidth * (shiftX + cos * qx - sin * qy));
                mesh.textureCoords.put(vertex * 2 + 1, 0.5f + halfHeight * (shiftY + sin * qx + cos * qy));
            }
        }
        return mesh;
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
