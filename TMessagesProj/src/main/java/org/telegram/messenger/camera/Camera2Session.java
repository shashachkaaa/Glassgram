package org.telegram.messenger.camera;

import android.annotation.TargetApi;
import android.content.Context;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureFailure;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.util.Range;
import android.util.Size;
import android.util.SizeF;
import android.view.Surface;
import android.view.WindowManager;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

@TargetApi(Build.VERSION_CODES.LOLLIPOP)
public class Camera2Session {

    private boolean isError;
    private boolean isSuccess;
    private boolean isClosed;

    private final CameraManager cameraManager;
    private final boolean isFront;
    public final String cameraId;
    private CameraCharacteristics cameraCharacteristics;

    private HandlerThread thread;
    private Handler handler;

    private CameraDevice cameraDevice;
    private SurfaceTexture surfaceTexture;
    private CameraCaptureSession captureSession;
    private Surface surface;

    private final CameraDevice.StateCallback cameraStateCallback;
    private final CameraCaptureSession.StateCallback captureStateCallback;
    private CaptureRequest.Builder captureRequestBuilder;
    private Rect sensorSize;
    private float maxZoom = 1f;
    private float currentZoom = 1f;
    // glassGram: video message options, see setVideoOptions
    private float minZoom = 1f;
    private float defaultZoom = 1f;
    private boolean useZoomRatio;
    private Range<Integer> fpsRange;
    private int videoStabilizationMode = -1;
    private boolean opticalStabilization;
    private boolean opticalStabilizationOff;
    private boolean gyroStabilization;
    private volatile long frameMiddle = 10_000_000L;
    private float focalPerWidth;

    public static final int STABILIZATION_OFF = 0;
    /** The camera's own stabilization, where it has one. */
    public static final int STABILIZATION_CAMERA = 1;
    /** Stabilization by the gyroscope, see {@link GyroStabilizer}. */
    public static final int STABILIZATION_GYRO = 2;

    // with gyroscope stabilization: when the middle of each frame was taken, after its timestamp
    private final CameraCaptureSession.CaptureCallback frameCallback = new CameraCaptureSession.CaptureCallback() {
        @Override
        public void onCaptureCompleted(@NonNull CameraCaptureSession session, @NonNull CaptureRequest request, @NonNull TotalCaptureResult result) {
            final Long exposure = result.get(CaptureResult.SENSOR_EXPOSURE_TIME);
            final Long skew = result.get(CaptureResult.SENSOR_ROLLING_SHUTTER_SKEW);
            frameMiddle = (exposure != null ? exposure / 2 : 5_000_000L) + (skew != null ? skew / 2 : 8_000_000L);
        }
    };

    private final Size previewSize;
    private boolean realtimeTimestamps;

    private ImageReader imageReader;

    private long lastTime;

    public static Camera2Session create(boolean front, int viewWidth, int viewHeight) {
        return create(front, false, false, viewWidth, viewHeight);
    }

    /**
     * glassGram: with preferWide the back camera is the one that sees widest: a camera that zooms
     * out below 1x onto its ultra-wide lens, or else an ultra-wide camera of its own. With fps60
     * the preview size is one the camera can run at 60 frames a second, where it has one.
     */
    public static Camera2Session create(boolean front, boolean preferWide, boolean fps60, int viewWidth, int viewHeight) {
        final Context context = ApplicationLoader.applicationContext;
        final Object[] choice = choose(front, preferWide, fps60, viewWidth, viewHeight);
        if (choice == null) {
            return null;
        }
        return new Camera2Session(context, front, (String) choice[0], (Size) choice[1]);
    }

    /** The camera id and preview size {@link #create} would take, or null. */
    static Object[] choose(boolean front, boolean preferWide, boolean fps60, int viewWidth, int viewHeight) {
        final Context context = ApplicationLoader.applicationContext;
        final CameraManager cameraManager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);

        float bestAspectRatio = 0;
        Size bestSize = null;
        String cameraId = null;
        float wideField = 0;
        Size wideSize = null;
        String wideId = null;
        try {
            String[] cameraIds = cameraManager.getCameraIdList();
            for (int i = 0; i < cameraIds.length; ++i) {
                final String id = cameraIds[i];
                CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics(id);
                if (characteristics == null) continue;
                if (characteristics.get(CameraCharacteristics.LENS_FACING) != (front ? CameraCharacteristics.LENS_FACING_FRONT : CameraCharacteristics.LENS_FACING_BACK)) {
                    continue;
                }
                StreamConfigurationMap confMap = (StreamConfigurationMap) characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
                Size pixelSize = characteristics.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE);
                float cameraAspectRatio = pixelSize == null ? 0 : (float) pixelSize.getWidth() / pixelSize.getHeight();
                if ((viewWidth / (float) viewHeight >= 1f) != (cameraAspectRatio >= 1f)) {
                    cameraAspectRatio = 1f / cameraAspectRatio;
                }
                if (bestAspectRatio <= 0 || Math.abs((float) viewWidth / viewHeight - bestAspectRatio) > Math.abs((float) viewWidth / viewHeight - cameraAspectRatio)) {
                    if (confMap != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        Size size = chooseSize(characteristics, confMap, viewWidth, viewHeight, fps60);
                        if (size != null) {
                            bestAspectRatio = cameraAspectRatio;
                            cameraId = id;
                            bestSize = size;
                        }
                    }
                } else {

                }
                if (preferWide && !front && confMap != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && isBackwardCompatible(characteristics)) {
                    final float field = widestField(characteristics);
                    if (field > wideField) {
                        Size size = chooseSize(characteristics, confMap, viewWidth, viewHeight, fps60);
                        if (size != null) {
                            wideField = field;
                            wideId = id;
                            wideSize = size;
                        }
                    }
                }
            }
            if (wideId != null && cameraId != null && !wideId.equals(cameraId) && wideField > widestField(cameraManager.getCameraCharacteristics(cameraId)) * 1.15f) {
                cameraId = wideId;
                bestSize = wideSize;
            }
        } catch (Exception e) {
            FileLog.e(e);
        }

        if (cameraId == null || bestSize == null) {
            return null;
        }
        return new Object[] { cameraId, bestSize };
    }

    /** The preview size; with fps60, one of those the camera keeps at 60 frames a second, if any. */
    private static Size chooseSize(CameraCharacteristics characteristics, StreamConfigurationMap map, int viewWidth, int viewHeight, boolean fps60) {
        final Size[] sizes = map.getOutputSizes(SurfaceTexture.class);
        if (sizes == null || sizes.length == 0) {
            return null;
        }
        if (fps60 && find60FpsRange(characteristics, null, null) != null) {
            final ArrayList<Size> fast = new ArrayList<>();
            for (Size size : sizes) {
                if (keeps60Fps(map, size)) fast.add(size);
            }
            if (!fast.isEmpty()) {
                return chooseOptimalSize(fast.toArray(new Size[0]), viewWidth, viewHeight, false);
            }
        }
        return chooseOptimalSize(sizes, viewWidth, viewHeight, false);
    }

    static boolean keeps60Fps(StreamConfigurationMap map, Size size) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true;
        try {
            return map.getOutputMinFrameDuration(SurfaceTexture.class, size) <= 17_000_000L;
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean isBackwardCompatible(CameraCharacteristics characteristics) {
        final int[] capabilities = characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES);
        return capabilities != null && contains(capabilities, CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE);
    }

    /** How wide the camera sees at its smallest zoom: sensor width over focal length, in no particular unit. */
    static float widestField(CameraCharacteristics characteristics) {
        final SizeF physicalSize = characteristics.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE);
        final float[] focalLengths = characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
        if (physicalSize == null || focalLengths == null || focalLengths.length == 0) {
            return 0;
        }
        float focalLength = Float.MAX_VALUE;
        for (float f : focalLengths) {
            if (f > 0) focalLength = Math.min(focalLength, f);
        }
        if (focalLength == Float.MAX_VALUE) {
            return 0;
        }
        float field = physicalSize.getWidth() / focalLength;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            final Range<Float> zoomRange = characteristics.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE);
            if (zoomRange != null && zoomRange.getLower() > 0 && zoomRange.getLower() < 1f) {
                field /= zoomRange.getLower();
            }
        }
        return field;
    }

    private static boolean contains(int[] values, int value) {
        for (int v : values) {
            if (v == value) return true;
        }
        return false;
    }

    private Camera2Session(Context context, boolean isFront, String cameraId, Size size) {
        thread = new HandlerThread("tg_camera2");
        thread.start();
        handler = new Handler(thread.getLooper());

        cameraStateCallback = new CameraDevice.StateCallback() {
            @Override
            public void onOpened(@NonNull CameraDevice camera) {
                Camera2Session.this.cameraDevice = camera;
                Camera2Session.this.lastTime = System.currentTimeMillis();
                FileLog.d("Camera2Session camera #" + cameraId + " opened");
                checkOpen();
            }

            @Override
            public void onDisconnected(@NonNull CameraDevice camera) {
                Camera2Session.this.cameraDevice = camera;
                FileLog.d("Camera2Session camera #" + cameraId + " disconnected");
            }

            @Override
            public void onError(@NonNull CameraDevice camera, int error) {
                Camera2Session.this.cameraDevice = camera;
                FileLog.e("Camera2Session camera #" + cameraId + " received " + error + " error");
                AndroidUtilities.runOnUIThread(() -> {
                    isError = true;
                });
            }
        };

        captureStateCallback = new CameraCaptureSession.StateCallback() {
            @Override
            public void onConfigured(@NonNull CameraCaptureSession session) {
                captureSession = session;
                FileLog.e("Camera2Session camera #" + cameraId + " capture session configured");
                Camera2Session.this.lastTime = System.currentTimeMillis();
                try {
                    updateCaptureRequest();
                    AndroidUtilities.runOnUIThread(() -> {
                        isSuccess = true;
                        if (doneCallback != null) {
                            doneCallback.run();
                            doneCallback = null;
                        }
                    });
                } catch (Exception e) {
                    FileLog.e(e);
                }
            }

            @Override
            public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                captureSession = session;
                FileLog.e("Camera2Session camera #" + cameraId + " capture session failed to configure");
                AndroidUtilities.runOnUIThread(() -> {
                    isError = true;
                });
            }
        };

        this.isFront = isFront;
        this.cameraId = cameraId;
        this.previewSize = size;
        this.lastTime = System.currentTimeMillis();
        this.imageReader = ImageReader.newInstance(size.getWidth(), size.getHeight(), ImageFormat.JPEG, 1);
        cameraManager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
        try {
            cameraCharacteristics = cameraManager.getCameraCharacteristics(cameraId);
            sensorSize = cameraCharacteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
            final Float value = cameraCharacteristics.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM);
            maxZoom = (value == null || value < 1f) ? 1f : value;
            final Integer timestampSource = cameraCharacteristics.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE);
            realtimeTimestamps = timestampSource != null && timestampSource == CameraMetadata.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME;
            cameraManager.openCamera(cameraId, cameraStateCallback, handler);
        } catch (Exception e) {
            FileLog.e(e);
            AndroidUtilities.runOnUIThread(() -> {
                isError = true;
            });
        }
    }

    /**
     * glassGram: camera options for video messages. Called right after {@link #create}, before the
     * session opens; an option the camera can't do stays off.
     */
    public void setVideoOptions(boolean fps60, int stabilization, boolean wide) {
        final CameraCharacteristics characteristics = cameraCharacteristics;
        if (characteristics == null) return;
        try {
            if (fps60) {
                fpsRange = find60FpsRange(characteristics, characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP), previewSize);
            }
            if (stabilization == STABILIZATION_GYRO) {
                // the picture is stabilized by the gyroscope, which only works on a picture the camera
                // doesn't shift itself: the lens or the camera's own stabilization would be undone twice
                videoStabilizationMode = CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF;
                final int[] optical = characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION);
                opticalStabilizationOff = optical != null && contains(optical, CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_OFF);
                gyroStabilization = true;
            } else if (stabilization == STABILIZATION_CAMERA) {
                final int[] modes = characteristics.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES);
                if (modes != null) {
                    // the video is recorded from the preview stream, so stabilize that where the camera can
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && contains(modes, CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_PREVIEW_STABILIZATION)) {
                        videoStabilizationMode = CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_PREVIEW_STABILIZATION;
                    } else if (contains(modes, CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON)) {
                        videoStabilizationMode = CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON;
                    }
                }
                // Camera2 advises against forcing both at once: with video stabilization on, the camera
                // keeps its own optical stabilization setting
                if (videoStabilizationMode < 0) {
                    final int[] optical = characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION);
                    opticalStabilization = optical != null && contains(optical, CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON);
                }
            }
            if (wide && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                final Range<Float> zoomRange = characteristics.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE);
                if (zoomRange != null && zoomRange.getLower() > 0 && zoomRange.getLower() < 0.99f) {
                    useZoomRatio = true;
                    minZoom = zoomRange.getLower();
                    maxZoom = Math.max(1f, Math.min(zoomRange.getUpper(), 10f));
                    defaultZoom = currentZoom = minZoom;
                }
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    /** The best frame rate range with 60 in it, or null; with a size, only if that size keeps up. */
    static Range<Integer> find60FpsRange(CameraCharacteristics characteristics, StreamConfigurationMap map, Size size) {
        final Range<Integer>[] ranges = characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
        if (ranges == null) return null;
        if (map != null && size != null && !keeps60Fps(map, size)) return null;
        Range<Integer> best = null;
        for (Range<Integer> range : ranges) {
            if (range == null || !range.contains(60)) continue;
            // a steady 60 first, then the narrowest range
            if (best == null || range.getLower() > best.getLower() || range.getLower().equals(best.getLower()) && range.getUpper() < best.getUpper()) {
                best = range;
            }
        }
        return best;
    }

    public boolean isFront() {
        return isFront;
    }

    /** glassGram: whether the picture is to be stabilized by the gyroscope, see {@link GyroStabilizer}. */
    public boolean isGyroStabilization() {
        return gyroStabilization;
    }

    /**
     * glassGram: when the middle of a frame was taken, in the gyroscope's clock
     * ({@link android.os.SystemClock#elapsedRealtimeNanos()}), from its SurfaceTexture timestamp.
     */
    public long frameTimeToSensorTime(long timestamp) {
        long time = timestamp + frameMiddle;
        if (!realtimeTimestamps) {
            time += android.os.SystemClock.elapsedRealtimeNanos() - System.nanoTime();
        }
        return time;
    }

    /** glassGram: the focal length over the width of the upright preview, at zoom 1. */
    public float getFocalPerWidth() {
        if (focalPerWidth <= 0) {
            focalPerWidth = computeFocalPerWidth();
        }
        return focalPerWidth;
    }

    private float computeFocalPerWidth() {
        final CameraCharacteristics characteristics = cameraCharacteristics;
        try {
            final float[] focalLengths = characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
            final SizeF physicalSize = characteristics.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE);
            final Size pixelSize = characteristics.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE);
            if (focalLengths != null && focalLengths.length > 0 && focalLengths[0] > 0 && physicalSize != null) {
                float activeWidth = physicalSize.getWidth(), activeHeight = physicalSize.getHeight();
                if (pixelSize != null && sensorSize != null && pixelSize.getWidth() > 0 && pixelSize.getHeight() > 0) {
                    activeWidth *= sensorSize.width() / (float) pixelSize.getWidth();
                    activeHeight *= sensorSize.height() / (float) pixelSize.getHeight();
                }
                // the preview is the largest centered cut of the sensor with its own aspect ratio
                final float aspect = previewSize.getWidth() / (float) previewSize.getHeight();
                final float streamWidth, streamHeight;
                if (aspect >= activeWidth / activeHeight) {
                    streamWidth = activeWidth;
                    streamHeight = activeWidth / aspect;
                } else {
                    streamHeight = activeHeight;
                    streamWidth = activeHeight * aspect;
                }
                final Integer orientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);
                final float uprightWidth = orientation != null && orientation % 180 != 0 ? streamHeight : streamWidth;
                if (uprightWidth > 0) {
                    return focalLengths[0] / uprightWidth;
                }
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
        // a common phone camera: about 64 degrees across the upright preview
        return 0.8f;
    }

    /** glassGram: whether the camera was set to record 60 frames a second. */
    public boolean isFps60() {
        return fpsRange != null;
    }

    private Runnable doneCallback;
    public void whenDone(Runnable doneCallback) {
        if (isInitiated()) {
            doneCallback.run();
            this.doneCallback = null;
        } else {
            this.doneCallback = doneCallback;
        }
    }

    public void open(SurfaceTexture surfaceTexture) {
        handler.post(() -> {
            this.surfaceTexture = surfaceTexture;
            if (surfaceTexture != null) {
                surfaceTexture.setDefaultBufferSize(getPreviewWidth(), getPreviewHeight());
            }
            checkOpen();
        });
    }

    private boolean opened = false;
    private void checkOpen() {
        if (opened) return;
        if (surfaceTexture == null || cameraDevice == null) return;
        opened = true;

        surface = new Surface(surfaceTexture);

        try {
            ArrayList<Surface> surfaces = new ArrayList<>();
            surfaces.add(surface);
            surfaces.add(imageReader.getSurface());
            cameraDevice.createCaptureSession(surfaces, captureStateCallback, null);
        } catch (Exception e) {
            FileLog.e(e);
            AndroidUtilities.runOnUIThread(() -> {
                isError = true;
            });
        }
    }

    public boolean isInitiated() {
        return !isError && isSuccess && !isClosed;
    }

    public int getDisplayOrientation() {
        try {
            Context context = ApplicationLoader.applicationContext;
            if (context == null) {
                return 0;
            }
            int rotation = ((WindowManager) context.getSystemService(Context.WINDOW_SERVICE)).getDefaultDisplay().getRotation();
            int degrees = 0;
            switch (rotation) {
                case Surface.ROTATION_0:
                    degrees = 0;
                    break;
                case Surface.ROTATION_90:
                    degrees = 90;
                    break;
                case Surface.ROTATION_180:
                    degrees = 180;
                    break;
                case Surface.ROTATION_270:
                    degrees = 270;
                    break;
            }

            Integer sensorOrientation = cameraCharacteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);
            int displayOrientation;
            if (isFront) {
                displayOrientation = (sensorOrientation + degrees) % 360;
                displayOrientation = (360 - displayOrientation) % 360; // compensate the mirror
            } else { // back-facing
                displayOrientation = (sensorOrientation - degrees + 360) % 360;
            }
            return displayOrientation;
        } catch (Exception e) {
            FileLog.e(e);
        }
        return 0;
    }

    private int getJpegOrientation() {
        try {
            Context context = ApplicationLoader.applicationContext;
            if (context == null) {
                return 0;
            }
            int rotation = ((WindowManager) context.getSystemService(Context.WINDOW_SERVICE)).getDefaultDisplay().getRotation();
            int degrees = 0;
            switch (rotation) {
                case Surface.ROTATION_0:
                    degrees = 0;
                    break;
                case Surface.ROTATION_90:
                    degrees = 90;
                    break;
                case Surface.ROTATION_180:
                    degrees = 180;
                    break;
                case Surface.ROTATION_270:
                    degrees = 270;
                    break;
            }

            Integer sensorOrientation = cameraCharacteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);
            int jpegOrientation;
            if (isFront) {
                jpegOrientation = (sensorOrientation + degrees) % 360;
                jpegOrientation = (360 - jpegOrientation) % 360; // compensate the mirror
            } else { // back-facing
                jpegOrientation = (sensorOrientation - degrees + 360) % 360;
            }
            return jpegOrientation;
        } catch (Exception e) {
            FileLog.e(e);
        }
        return 0;
    }

    public int getWorldAngle() {
        int displayOrientation = getDisplayOrientation();
        int jpegOrientation = getJpegOrientation();
        int diffOrientation = jpegOrientation - displayOrientation;
        if (diffOrientation < 0) {
            diffOrientation += 360;
        }
        return diffOrientation;
    }

    public int getCurrentOrientation() {
        return getJpegOrientation();
    }

    private final Rect cropRegion = new Rect();
    public void setZoom(float value) {
        if (!isInitiated()) return;
        if (captureRequestBuilder == null || cameraDevice == null || sensorSize == null) return;

        currentZoom = Utilities.clamp(value, maxZoom, minZoom);
        updateCaptureRequest();

        try {
            captureSession.setRepeatingRequest(captureRequestBuilder.build(), gyroStabilization ? frameCallback : null, handler);
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    private boolean flashing;
    public void setFlash(boolean flash) {
        if (flashing != flash) {
            flashing = flash;
            updateCaptureRequest();
        }
    }
    public boolean getFlash() {
        return flashing;
    }

    public float getZoom() {
        return currentZoom;
    }

    public float getMaxZoom() {
        return maxZoom;
    }

    public float getMinZoom() {
        return minZoom;
    }

    /** glassGram: the zoom the camera starts at and goes back to after a pinch. */
    public float getDefaultZoom() {
        return defaultZoom;
    }

    public int getPreviewWidth() {
        return previewSize.getWidth();
    }

    public int getPreviewHeight() {
        return previewSize.getHeight();
    }

    public void destroy(boolean async) {
        destroy(async, null);
    }

    public void destroy(boolean async, Runnable afterCallback) {
        isClosed = true;
        if (async) {
            handler.post(() -> {
                if (captureSession != null) {
                    captureSession.close();
                    captureSession = null;
                }
                if (cameraDevice != null) {
                    cameraDevice.close();
                    cameraDevice = null;
                }
                if (imageReader != null) {
                    imageReader.close();
                    imageReader = null;
                }
                thread.quitSafely();
                AndroidUtilities.runOnUIThread(() -> {
                    try {
                        thread.join();
                    } catch (Exception e) {
                        FileLog.e(e);
                    }
                    if (afterCallback != null) {
                        afterCallback.run();
                    }
                });
            });
        } else {
            if (captureSession != null) {
                captureSession.close();
                captureSession = null;
            }
            if (cameraDevice != null) {
                cameraDevice.close();
                cameraDevice = null;
            }
            if (imageReader != null) {
                imageReader.close();
                imageReader = null;
            }
            thread.quitSafely();
            try {
                thread.join();
            } catch (Exception e) {
                FileLog.e(e);
            }
            if (afterCallback != null) {
                AndroidUtilities.runOnUIThread(afterCallback);
            }
        }
    }

    private boolean recordingVideo;
    public void setRecordingVideo(boolean recording) {
        if (recordingVideo != recording) {
            recordingVideo = recording;
            updateCaptureRequest();
        }
    }

    private boolean scanningBarcode;
    public void setScanningBarcode(boolean scanning) {
        if (scanningBarcode != scanning) {
            scanningBarcode = scanning;
            updateCaptureRequest();
        }
    }

    private boolean nightMode;
    public void setNightMode(boolean enable) {
        if (nightMode != enable) {
            nightMode = enable;
            updateCaptureRequest();
        }
    }

    private void updateCaptureRequest() {
        if (cameraDevice == null || surface == null || captureSession == null) return;
        try {
            int template;
            if (recordingVideo) {
                template = CameraDevice.TEMPLATE_RECORD;
            } else if (scanningBarcode) {
                template = CameraDevice.TEMPLATE_STILL_CAPTURE;
            } else {
                template = CameraDevice.TEMPLATE_PREVIEW;
            }
            captureRequestBuilder = cameraDevice.createCaptureRequest(template);

            if (scanningBarcode) {
                captureRequestBuilder.set(CaptureRequest.CONTROL_SCENE_MODE, CameraMetadata.CONTROL_SCENE_MODE_BARCODE);
            } else if (nightMode) {
                captureRequestBuilder.set(CaptureRequest.CONTROL_SCENE_MODE, isFront ? CameraMetadata.CONTROL_SCENE_MODE_NIGHT_PORTRAIT : CameraMetadata.CONTROL_SCENE_MODE_NIGHT);
            }

            captureRequestBuilder.set(CaptureRequest.FLASH_MODE, flashing ? (recordingVideo ? CaptureRequest.FLASH_MODE_TORCH : CaptureRequest.FLASH_MODE_SINGLE) : CaptureRequest.FLASH_MODE_OFF);

            if (recordingVideo) {
                captureRequestBuilder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fpsRange != null ? fpsRange : new Range<Integer>(30, 60));
                captureRequestBuilder.set(CaptureRequest.CONTROL_CAPTURE_INTENT, CaptureRequest.CONTROL_CAPTURE_INTENT_VIDEO_RECORD);
            } else if (fpsRange != null) {
                captureRequestBuilder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fpsRange);
            }
            if (videoStabilizationMode >= 0) {
                captureRequestBuilder.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, videoStabilizationMode);
            }
            if (opticalStabilization) {
                captureRequestBuilder.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON);
            } else if (opticalStabilizationOff) {
                captureRequestBuilder.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_OFF);
            }

            if (useZoomRatio && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                captureRequestBuilder.set(CaptureRequest.CONTROL_ZOOM_RATIO, currentZoom);
            } else if (sensorSize != null && Math.abs(currentZoom - 1f) >= 0.01f) {
                final int centerX = sensorSize.width() / 2;
                final int centerY = sensorSize.height() / 2;
                final int deltaX = (int) ((0.5f * sensorSize.width()) / currentZoom);
                final int deltaY = (int) ((0.5f * sensorSize.height()) / currentZoom);
                cropRegion.set(
                        centerX - deltaX,
                        centerY - deltaY,
                        centerX + deltaX,
                        centerY + deltaY
                );
                captureRequestBuilder.set(CaptureRequest.SCALER_CROP_REGION, cropRegion);
            }

            captureRequestBuilder.addTarget(surface);
            captureSession.setRepeatingRequest(captureRequestBuilder.build(), gyroStabilization ? frameCallback : null, handler);
        } catch (Exception e) {
            FileLog.e("Camera2Sessions setRepeatingRequest error in updateCaptureRequest", e);
        }
    }

    public boolean takePicture(final File file, Utilities.Callback<Integer> whenDone) {
        if (cameraDevice == null || captureSession == null) return false;
        try {
            CaptureRequest.Builder captureRequestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
            final int orientation = getJpegOrientation();
            captureRequestBuilder.set(CaptureRequest.JPEG_ORIENTATION, orientation);
            imageReader.setOnImageAvailableListener(new ImageReader.OnImageAvailableListener() {
                @Override
                public void onImageAvailable(ImageReader reader) {
                    Image image = reader.acquireLatestImage();
                    ByteBuffer buffer = image.getPlanes()[0].getBuffer();
                    byte[] bytes = new byte[buffer.remaining()];
                    buffer.get(bytes);

                    FileOutputStream output = null;
                    try {
                        output = new FileOutputStream(file);
                        output.write(bytes);
                    } catch (IOException e) {
                        e.printStackTrace();
                    } finally {
                        image.close();
                        if (null != output) {
                            try {
                                output.close();
                            } catch (IOException e) {
                                e.printStackTrace();
                            }
                        }
                    }

                    AndroidUtilities.runOnUIThread(() -> {
                        if (whenDone != null) {
                            whenDone.run(orientation);
                        }
                    });
                }
            }, null);
            if (scanningBarcode) {
                captureRequestBuilder.set(CaptureRequest.CONTROL_SCENE_MODE, CameraMetadata.CONTROL_SCENE_MODE_BARCODE);
            }
            captureRequestBuilder.addTarget(imageReader.getSurface());
            captureSession.capture(captureRequestBuilder.build(), new CameraCaptureSession.CaptureCallback() {}, null);
            return true;
        } catch (Exception e) {
            FileLog.e("Camera2Sessions takePicture error", e);
            return false;
        }
    }


    public static Size chooseOptimalSize(Size[] choices, int width, int height, boolean notBigger) {
        List<Size> bigEnoughWithAspectRatio = new ArrayList<>(choices.length);
        List<Size> bigEnough = new ArrayList<>(choices.length);
        int w = width;
        int h = height;
        for (int a = 0; a < choices.length; a++) {
            Size option = choices[a];
            if (notBigger && (option.getHeight() > height || option.getWidth() > width)) {
                continue;
            }
            if (option.getHeight() == option.getWidth() * h / w && option.getWidth() >= width && option.getHeight() >= height) {
                bigEnoughWithAspectRatio.add(option);
            } else if (option.getHeight() * option.getWidth() <= width * height * 4 && option.getWidth() >= width && option.getHeight() >= height) {
                bigEnough.add(option);
            }
        }
        if (bigEnoughWithAspectRatio.size() > 0) {
            return Collections.min(bigEnoughWithAspectRatio, new CompareSizesByArea());
        } else if (bigEnough.size() > 0) {
            return Collections.min(bigEnough, new CompareSizesByArea());
        } else {
            return Collections.max(Arrays.asList(choices), new CompareSizesByArea());
        }
    }
    static class CompareSizesByArea implements Comparator<Size> {
        @Override
        public int compare(Size lhs, Size rhs) {
            return Long.signum((long) lhs.getWidth() * lhs.getHeight() - (long) rhs.getWidth() * rhs.getHeight());
        }
    }

}