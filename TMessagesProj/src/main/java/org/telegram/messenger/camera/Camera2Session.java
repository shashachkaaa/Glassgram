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
import java.util.LinkedHashSet;
import java.util.List;

@TargetApi(Build.VERSION_CODES.LOLLIPOP)
public class Camera2Session {

    private boolean isError;
    private boolean isSuccess;
    private boolean isClosed;

    private final CameraManager cameraManager;
    private final boolean isFront;
    public volatile String cameraId;
    private CameraCharacteristics cameraCharacteristics;
    // glassGram: cameras to try next when this one doesn't open or stream, and those the phone hides
    private final ArrayList<String> nextCameraIds = new ArrayList<>();
    private final ArrayList<String> hiddenCameraIds = new ArrayList<>();
    private boolean configured;

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
    // glassGram: the camera's own steadiest range with 30 in it, for recording
    private Range<Integer> recordingFpsRange;
    private int videoStabilizationMode = -1;
    private boolean opticalStabilization;
    private boolean opticalStabilizationOff;
    private boolean gyroStabilization;
    private boolean optionsSet, optionFps60, optionWide;
    private int optionStabilization;
    // with gyroscope stabilization: each frame's exposure time and rolling shutter skew
    private volatile long frameExposure = 10_000_000L;
    private volatile long frameSkew;
    // glassGram: a camera of the ultra-wide lens alone (Huawei), switched to below 1x while the main
    // camera serves 1x and up; its zoom is the main camera's times lensRatio
    private volatile String lensId;
    private String mainId;
    private float lensRatio = 1f;
    private volatile boolean onLens;
    private boolean switchingLens;

    // glassGram: when the picture last moved to another lens, as a frame timestamp: the first frame
    // of the new lens has it or a later one
    private volatile long lensChangeTime;
    private String activePhysicalId;
    private boolean everConfigured;
    // the zoom moving is what changes lenses, so frames are kept for a cross-fade only then
    private static final long LENS_SNAPSHOT_TIME = 1500;
    private volatile long lastZoomChange;

    /** glassGram: whether the picture can move between lenses, by the zoom or a switch of camera. */
    public boolean hasLensChanges() {
        return lensId != null || useZoomRatio;
    }

    /** glassGram: the timestamp from which frames are of the lens the picture moved to last, or 0. */
    public long getLensChangeTime() {
        return lensChangeTime;
    }

    /** glassGram: whether the picture may move to another lens soon, as the zoom has just moved. */
    public boolean mayChangeLens() {
        return hasLensChanges() && android.os.SystemClock.uptimeMillis() - lastZoomChange < LENS_SNAPSHOT_TIME;
    }

    /** glassGram: now, in the clock of the frame timestamps. */
    private long frameClockNow() {
        return realtimeTimestamps ? android.os.SystemClock.elapsedRealtimeNanos() : System.nanoTime();
    }
    // a hidden camera that opens but sends no frames (vivo) gives way to the next camera
    private volatile boolean frameSeen;
    private static final long HIDDEN_CAMERA_TIMEOUT = 2000;
    private final Runnable hiddenCameraWatchdog = () -> {
        if (!frameSeen && !isClosed && hiddenCameraIds.contains(cameraId)) {
            FileLog.d("Camera2Session camera #" + cameraId + " sent no frames");
            if (onLens) {
                lensFailed(true);
            } else {
                tryNextCamera(true);
            }
        }
    };
    private float focalPerWidth;
    // read once per camera, as it's asked for every frame
    private int sensorOrientation = 90;

    public static final int STABILIZATION_OFF = 0;
    /** The camera's own stabilization, where it has one. */
    public static final int STABILIZATION_CAMERA = 1;
    /** Stabilization by the gyroscope, see {@link GyroStabilizer}. */
    public static final int STABILIZATION_GYRO = 2;

    // with gyroscope stabilization: when the middle of each frame was taken, after its timestamp
    private final CameraCaptureSession.CaptureCallback frameCallback = new CameraCaptureSession.CaptureCallback() {
        @Override
        public void onCaptureCompleted(@NonNull CameraCaptureSession session, @NonNull CaptureRequest request, @NonNull TotalCaptureResult result) {
            frameSeen = true;
            if (useZoomRatio && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // a camera that zooms out onto its ultra-wide lens moves between lenses by itself
                final String physical = result.get(CaptureResult.LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID);
                if (physical != null && !physical.equals(activePhysicalId)) {
                    final boolean changed = activePhysicalId != null;
                    activePhysicalId = physical;
                    if (changed) {
                        final Long timestamp = result.get(CaptureResult.SENSOR_TIMESTAMP);
                        lensChangeTime = timestamp != null ? timestamp : frameClockNow();
                    }
                }
            }
            final Long exposure = result.get(CaptureResult.SENSOR_EXPOSURE_TIME);
            final Long skew = result.get(CaptureResult.SENSOR_ROLLING_SHUTTER_SKEW);
            if (exposure != null) frameExposure = exposure;
            if (skew != null) frameSkew = skew;
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
        final Choice choice = choose(front, preferWide, fps60, viewWidth, viewHeight);
        if (choice == null) {
            return null;
        }
        return new Camera2Session(context, front, choice);
    }

    /**
     * glassGram: the cameras to try in turn, the ones the phone hides among them, the preview size,
     * and a camera of the ultra-wide lens alone to switch to below 1x, if any.
     */
    static class Choice {
        final ArrayList<String> ids = new ArrayList<>();
        final ArrayList<String> hiddenIds = new ArrayList<>();
        Size size;
        String lensId;
        float lensRatio = 1f;
    }

    private static final String UNAVAILABLE_KEY = "glassgram_camera_unavailable";

    /** glassGram: hidden cameras that refused to open or stream, not to be tried again. */
    private static boolean isUnavailable(String id) {
        final String ids = org.telegram.messenger.MessagesController.getGlobalMainSettings().getString(UNAVAILABLE_KEY, "");
        return ("," + ids + ",").contains("," + id + ",");
    }

    private static void markUnavailable(String id) {
        if (isUnavailable(id)) return;
        final android.content.SharedPreferences preferences = org.telegram.messenger.MessagesController.getGlobalMainSettings();
        final String ids = preferences.getString(UNAVAILABLE_KEY, "");
        preferences.edit().putString(UNAVAILABLE_KEY, ids.isEmpty() ? id : ids + "," + id).apply();
    }

    /** The cameras and preview size {@link #create} would take, or null. */
    static Choice choose(boolean front, boolean preferWide, boolean fps60, int viewWidth, int viewHeight) {
        final Context context = ApplicationLoader.applicationContext;
        final CameraManager cameraManager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);

        float bestAspectRatio = 0;
        Size bestSize = null;
        String cameraId = null;
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
            }
        } catch (Exception e) {
            FileLog.e(e);
        }

        if (cameraId == null || bestSize == null) {
            return null;
        }
        final Choice choice = new Choice();
        choice.size = bestSize;
        if (preferWide && !front) {
            findWideLens(cameraManager, cameraId, bestSize, choice);
        }
        choice.ids.add(cameraId);
        return choice;
    }

    /**
     * glassGram: how the back camera gets to its ultra-wide lens when the chosen camera doesn't zoom
     * out onto it itself. A camera that does (a logical one, which some phones such as vivo keep out
     * of the list apps see, yet answer for by id) is tried first, widest first, the chosen camera
     * staying the fallback. Else a camera of the ultra-wide lens alone (Huawei) is switched to below
     * 1x, the chosen one serving 1x and up. Either has to stream the chosen preview size.
     */
    private static void findWideLens(CameraManager manager, String cameraId, Size size, Choice choice) {
        try {
            final float mainField = widestField(manager.getCameraCharacteristics(cameraId));
            final List<String> listed = Arrays.asList(manager.getCameraIdList());
            final LinkedHashSet<String> ids = new LinkedHashSet<>(listed);
            for (int i = 0; i < 16; ++i) {
                ids.add(String.valueOf(i));
            }
            final ArrayList<String> zooming = new ArrayList<>();
            final ArrayList<Float> zoomingFields = new ArrayList<>();
            String lens = null;
            float lensField = 0;
            for (String id : ids) {
                if (id.equals(cameraId)) continue;
                final boolean hidden = !listed.contains(id);
                if (hidden && isUnavailable(id)) continue;
                final CameraCharacteristics characteristics;
                try {
                    characteristics = manager.getCameraCharacteristics(id);
                } catch (Exception e) {
                    continue;
                }
                final Integer facing = characteristics.get(CameraCharacteristics.LENS_FACING);
                if (facing == null || facing != CameraCharacteristics.LENS_FACING_BACK || !isBackwardCompatible(characteristics)) continue;
                final StreamConfigurationMap map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
                final Size[] sizes = map == null ? null : map.getOutputSizes(SurfaceTexture.class);
                if (sizes == null || !Arrays.asList(sizes).contains(size)) continue;
                final float field = widestField(characteristics);
                if (field <= mainField * 1.15f) continue;
                if (zoomsOut(characteristics)) {
                    int index = 0;
                    while (index < zoomingFields.size() && zoomingFields.get(index) >= field) index++;
                    zooming.add(index, id);
                    zoomingFields.add(index, field);
                } else if (field > lensField) {
                    lens = id;
                    lensField = field;
                }
            }
            if (!zooming.isEmpty()) {
                choice.ids.addAll(zooming);
                for (String id : zooming) {
                    if (!listed.contains(id)) choice.hiddenIds.add(id);
                }
            } else if (lens != null) {
                choice.lensId = lens;
                choice.lensRatio = mainField / lensField;
                if (!listed.contains(lens)) choice.hiddenIds.add(lens);
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    /** Whether the camera zooms out below 1x, onto an ultra-wide lens of its own. */
    private static boolean zoomsOut(CameraCharacteristics characteristics) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false;
        final Range<Float> range = characteristics.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE);
        return range != null && range.getLower() > 0 && range.getLower() < 0.99f;
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

    private Camera2Session(Context context, boolean isFront, Choice choice) {
        final List<String> cameraIds = choice.ids;
        final Size size = choice.size;
        final String cameraId = cameraIds.get(0);
        nextCameraIds.addAll(cameraIds.subList(1, cameraIds.size()));
        hiddenCameraIds.addAll(choice.hiddenIds);
        lensId = choice.lensId;
        lensRatio = choice.lensRatio;
        thread = new HandlerThread("tg_camera2");
        thread.start();
        handler = new Handler(thread.getLooper());

        cameraStateCallback = new CameraDevice.StateCallback() {
            @Override
            public void onOpened(@NonNull CameraDevice camera) {
                Camera2Session.this.cameraDevice = camera;
                Camera2Session.this.lastTime = System.currentTimeMillis();
                FileLog.d("Camera2Session camera #" + Camera2Session.this.cameraId + " opened");
                checkOpen();
            }

            @Override
            public void onDisconnected(@NonNull CameraDevice camera) {
                Camera2Session.this.cameraDevice = camera;
                FileLog.d("Camera2Session camera #" + Camera2Session.this.cameraId + " disconnected");
                if (!configured && onLens) {
                    lensFailed(false);
                    return;
                }
                if (!configured && tryNextCamera(false)) return;
            }

            @Override
            public void onError(@NonNull CameraDevice camera, int error) {
                Camera2Session.this.cameraDevice = camera;
                FileLog.e("Camera2Session camera #" + Camera2Session.this.cameraId + " received " + error + " error");
                if (!configured && onLens) {
                    lensFailed(false);
                    return;
                }
                if (!configured && tryNextCamera(false)) return;
                AndroidUtilities.runOnUIThread(() -> {
                    isError = true;
                });
            }
        };

        captureStateCallback = new CameraCaptureSession.StateCallback() {
            @Override
            public void onConfigured(@NonNull CameraCaptureSession session) {
                captureSession = session;
                configured = true;
                if (everConfigured) {
                    // another camera in this session, as the lens changed: its frames all come after now
                    lensChangeTime = frameClockNow();
                }
                everConfigured = true;
                FileLog.e("Camera2Session camera #" + Camera2Session.this.cameraId + " capture session configured");
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
                FileLog.e("Camera2Session camera #" + Camera2Session.this.cameraId + " capture session failed to configure");
                if (onLens) {
                    lensFailed(true);
                    return;
                }
                if (tryNextCamera(true)) return;
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
            loadCamera(cameraId);
            cameraManager.openCamera(cameraId, cameraStateCallback, handler);
            watchHiddenCamera();
        } catch (Exception e) {
            FileLog.e(e);
            if (tryNextCamera(e instanceof IllegalArgumentException || e instanceof SecurityException)) return;
            AndroidUtilities.runOnUIThread(() -> {
                isError = true;
            });
        }
    }

    private void loadCamera(String id) throws Exception {
        cameraId = id;
        cameraCharacteristics = cameraManager.getCameraCharacteristics(id);
        sensorSize = cameraCharacteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
        if (!switchingLens) {
            final Float value = cameraCharacteristics.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM);
            maxZoom = (value == null || value < 1f) ? 1f : value;
        }
        final Integer timestampSource = cameraCharacteristics.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE);
        realtimeTimestamps = timestampSource != null && timestampSource == CameraMetadata.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME;
        recordingFpsRange = findRecordingFpsRange(cameraCharacteristics);
        final Integer orientation = cameraCharacteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);
        sensorOrientation = orientation == null ? 90 : orientation;
        focalPerWidth = 0;
        if (optionsSet) {
            applyVideoOptions();
        }
    }

    /** glassGram: closes the camera, keeping the preview surface for the next one. */
    private void closeCamera() {
        try {
            if (captureSession != null) captureSession.close();
        } catch (Exception ignore) {}
        try {
            if (cameraDevice != null) cameraDevice.close();
        } catch (Exception ignore) {}
        captureSession = null;
        cameraDevice = null;
        opened = false;
        configured = false;
        handler.removeCallbacks(hiddenCameraWatchdog);
    }

    /**
     * glassGram: moves between the main camera and the ultra-wide lens camera, keeping the zoom,
     * the preview surface and the options; the picture stops for a moment in between.
     */
    private void switchLens(boolean toLens) {
        onLens = toLens;
        handler.post(() -> {
            if (isClosed || lensId == null) return;
            final String id = toLens ? lensId : mainId;
            if (id == null || id.equals(cameraId)) return;
            if (toLens) {
                mainId = cameraId;
            }
            closeCamera();
            switchingLens = true;
            try {
                loadCamera(id);
                cameraManager.openCamera(id, cameraStateCallback, handler);
                watchHiddenCamera();
            } catch (Exception e) {
                FileLog.e(e);
                if (toLens) {
                    switchingLens = false;
                    lensFailed(e instanceof IllegalArgumentException || e instanceof SecurityException);
                }
            } finally {
                switchingLens = false;
            }
        });
    }

    /** glassGram: the ultra-wide lens camera didn't open or stream: back to the main camera, without the lens. */
    private void lensFailed(boolean refused) {
        final String failed = lensId;
        if (refused && failed != null && hiddenCameraIds.contains(failed)) {
            markUnavailable(failed);
        }
        lensId = null;
        onLens = false;
        minZoom = 1f;
        defaultZoom = Math.max(1f, defaultZoom);
        currentZoom = Math.max(1f, currentZoom);
        if (mainId == null || isClosed) return;
        closeCamera();
        switchingLens = true;
        try {
            loadCamera(mainId);
            cameraManager.openCamera(mainId, cameraStateCallback, handler);
        } catch (Exception e) {
            FileLog.e(e);
            AndroidUtilities.runOnUIThread(() -> isError = true);
        } finally {
            switchingLens = false;
        }
    }

    /**
     * glassGram: moves on to the next camera when this one didn't open or stream, as a camera the
     * phone hides may refuse apps. A hidden one that refused for good isn't tried again.
     */
    private boolean tryNextCamera(boolean refused) {
        if (isClosed || nextCameraIds.isEmpty()) return false;
        if (refused && hiddenCameraIds.contains(cameraId)) {
            markUnavailable(cameraId);
        }
        try {
            if (captureSession != null) captureSession.close();
        } catch (Exception ignore) {}
        try {
            if (cameraDevice != null) cameraDevice.close();
        } catch (Exception ignore) {}
        captureSession = null;
        cameraDevice = null;
        opened = false;
        configured = false;
        handler.removeCallbacks(hiddenCameraWatchdog);
        while (!nextCameraIds.isEmpty()) {
            final String id = nextCameraIds.remove(0);
            FileLog.d("Camera2Session camera #" + cameraId + " failed, trying #" + id);
            try {
                loadCamera(id);
                cameraManager.openCamera(id, cameraStateCallback, handler);
                watchHiddenCamera();
                return true;
            } catch (Exception e) {
                FileLog.e(e);
                if (hiddenCameraIds.contains(id) && (e instanceof IllegalArgumentException || e instanceof SecurityException)) {
                    markUnavailable(id);
                }
            }
        }
        return false;
    }

    private void watchHiddenCamera() {
        if (hiddenCameraIds.contains(cameraId)) {
            frameSeen = false;
            handler.removeCallbacks(hiddenCameraWatchdog);
            handler.postDelayed(hiddenCameraWatchdog, HIDDEN_CAMERA_TIMEOUT);
        }
    }

    /** glassGram: lets hidden cameras that refused be tried again, as when the ultra-wide option is turned on. */
    public static void resetUnavailableCameras() {
        org.telegram.messenger.MessagesController.getGlobalMainSettings().edit().remove(UNAVAILABLE_KEY).apply();
    }

    /**
     * glassGram: camera options for video messages. Called right after {@link #create}, before the
     * session opens; an option the camera can't do stays off.
     */
    public void setVideoOptions(boolean fps60, int stabilization, boolean wide) {
        optionFps60 = fps60;
        optionStabilization = stabilization;
        optionWide = wide;
        optionsSet = true;
        applyVideoOptions();
    }

    private void applyVideoOptions() {
        final boolean fps60 = optionFps60, wide = optionWide;
        final int stabilization = optionStabilization;
        fpsRange = null;
        videoStabilizationMode = -1;
        opticalStabilization = false;
        opticalStabilizationOff = false;
        gyroStabilization = false;
        useZoomRatio = false;
        if (!switchingLens) {
            minZoom = defaultZoom = currentZoom = 1f;
            // with a camera of the ultra-wide lens alone, the zoom goes below 1x by switching to it
            if (wide && lensId != null) {
                minZoom = Math.min(1f, Math.max(0.3f, lensRatio));
            }
        }
        final CameraCharacteristics characteristics = cameraCharacteristics;
        if (characteristics == null) return;
        try {
            if (fps60) {
                final StreamConfigurationMap map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
                fpsRange = find60FpsRange(characteristics, map, previewSize);
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
                    // the ultra-wide lens is a zoom away; the camera starts on the main one at 1x
                    defaultZoom = currentZoom = 1f;
                }
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    /**
     * glassGram: the range to record in without 60 FPS: the one with 30 in it and the highest floor,
     * then the narrowest, so a steady 30 where the camera has it. A range like 30 to 60 lets the
     * frame rate wander and the picture stutter with it; one the camera doesn't have may fall to 10
     * frames a second in dim light, with exposures long enough to smear every move.
     */
    static Range<Integer> findRecordingFpsRange(CameraCharacteristics characteristics) {
        final Range<Integer>[] ranges = characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
        if (ranges == null || ranges.length == 0) return null;
        Range<Integer> best = null;
        for (Range<Integer> range : ranges) {
            if (range == null || !range.contains(30)) continue;
            if (best == null || range.getLower() > best.getLower() || range.getLower().equals(best.getLower()) && range.getUpper() < best.getUpper()) {
                best = range;
            }
        }
        return best;
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
     * glassGram: a SurfaceTexture timestamp (when the first row's exposure started) in the
     * gyroscope's clock, {@link android.os.SystemClock#elapsedRealtimeNanos()}.
     */
    public long toSensorClock(long timestamp) {
        return realtimeTimestamps ? timestamp : timestamp + android.os.SystemClock.elapsedRealtimeNanos() - System.nanoTime();
    }

    /** glassGram: the exposure time of the last frames' rows. */
    public long getFrameExposure() {
        return frameExposure;
    }

    /** glassGram: the time the last frames took to read out, from their first row to their last. */
    public long getFrameSkew() {
        return frameSkew;
    }

    /**
     * glassGram: the edge of the upright picture the readout starts at. Sensors read from their top
     * row, and the picture is the sensor's turned clockwise by its orientation, then mirrored for
     * the front camera.
     */
    public int getReadoutEdge() {
        int edge;
        switch (sensorOrientation) {
            case 0: edge = GyroStabilizer.READOUT_TOP; break;
            case 180: edge = GyroStabilizer.READOUT_BOTTOM; break;
            case 270: edge = GyroStabilizer.READOUT_LEFT; break;
            default: edge = GyroStabilizer.READOUT_RIGHT; break;
        }
        if (isFront) {
            if (edge == GyroStabilizer.READOUT_LEFT) {
                edge = GyroStabilizer.READOUT_RIGHT;
            } else if (edge == GyroStabilizer.READOUT_RIGHT) {
                edge = GyroStabilizer.READOUT_LEFT;
            }
        }
        return edge;
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

        if (surface == null) {
            surface = new Surface(surfaceTexture);
        }

        try {
            ArrayList<Surface> surfaces = new ArrayList<>();
            surfaces.add(surface);
            // video messages take no photos, and a camera the phone hides may not stream the extra size
            if (!hiddenCameraIds.contains(cameraId) && !onLens) {
                surfaces.add(imageReader.getSurface());
            }
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
        final float zoom = Utilities.clamp(value, maxZoom, minZoom);
        if (zoom != currentZoom) {
            lastZoomChange = android.os.SystemClock.uptimeMillis();
        }
        currentZoom = zoom;
        // glassGram: below 1x on the ultra-wide lens camera, from 1x on the main one, with a little
        // room between so the zoom doesn't flip between them right at 1x
        if (lensId != null && optionWide) {
            final boolean toLens = onLens ? currentZoom < 1f : currentZoom < 0.97f;
            if (toLens != onLens) {
                switchLens(toLens);
                return;
            }
        }
        if (captureRequestBuilder == null || cameraDevice == null || sensorSize == null) return;
        updateCaptureRequest();
    }

    /** glassGram: the zoom of the camera's own picture: on the ultra-wide lens camera, the zoom over its ratio. */
    public float getStreamZoom() {
        if (onLens) {
            return Math.max(1f, currentZoom / lensRatio);
        }
        return useZoomRatio ? currentZoom : Math.max(1f, currentZoom);
    }

    /** glassGram: repeats the request, watching its frames when that's needed. */
    private void setRepeating(CaptureRequest request) throws Exception {
        // the frame callback also tells a hidden camera that streams from one that doesn't
        final CameraCaptureSession.CaptureCallback callback = gyroStabilization || hiddenCameraIds.contains(cameraId) || useZoomRatio ? frameCallback : null;
        captureSession.setRepeatingRequest(request, callback, handler);
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

    /** glassGram: a new zoom to go back to after a pinch, as picked on the zoom slider. */
    public void setDefaultZoom(float zoom) {
        defaultZoom = Utilities.clamp(zoom, maxZoom, minZoom);
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
                captureRequestBuilder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fpsRange != null ? fpsRange : recordingFpsRange != null ? recordingFpsRange : new Range<Integer>(30, 60));
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

            final float streamZoom = getStreamZoom();
            if (useZoomRatio && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                captureRequestBuilder.set(CaptureRequest.CONTROL_ZOOM_RATIO, currentZoom);
            } else if (sensorSize != null && Math.abs(streamZoom - 1f) >= 0.01f) {
                final int centerX = sensorSize.width() / 2;
                final int centerY = sensorSize.height() / 2;
                final int deltaX = (int) ((0.5f * sensorSize.width()) / streamZoom);
                final int deltaY = (int) ((0.5f * sensorSize.height()) / streamZoom);
                cropRegion.set(
                        centerX - deltaX,
                        centerY - deltaY,
                        centerX + deltaX,
                        centerY + deltaY
                );
                captureRequestBuilder.set(CaptureRequest.SCALER_CROP_REGION, cropRegion);
            }

            captureRequestBuilder.addTarget(surface);
            setRepeating(captureRequestBuilder.build());
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