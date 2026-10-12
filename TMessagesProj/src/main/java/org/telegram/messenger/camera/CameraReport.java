package org.telegram.messenger.camera;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.os.Build;
import android.text.TextUtils;
import android.util.Range;
import android.util.Size;
import android.util.SizeF;

import org.telegram.messenger.GlassgramConfig;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * glassGram: what the phone's cameras offer apps through Camera2, for the video message options:
 * frame rates, stabilization, zoom and the lenses it shares.
 */
public class CameraReport {

    public static String build(Context context, int roundVideoSize) {
        final StringBuilder sb = new StringBuilder();
        sb.append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
                .append(", Android ").append(Build.VERSION.RELEASE).append(" (SDK ").append(Build.VERSION.SDK_INT).append(")\n");
        sb.append("Gyroscope: ").append(GyroStabilizer.isAvailable(context) ? "yes" : "no").append('\n');
        final CameraManager manager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
        if (manager == null) {
            return sb.append("No camera service").toString();
        }
        final Set<String> listed = new LinkedHashSet<>();
        final Set<String> physical = new LinkedHashSet<>();
        try {
            listed.addAll(Arrays.asList(manager.getCameraIdList()));
        } catch (Exception e) {
            sb.append("Camera list failed: ").append(e).append('\n');
        }
        sb.append("Listed cameras: ").append(listed.isEmpty() ? "none" : TextUtils.join(", ", listed)).append('\n');

        for (String id : listed) {
            try {
                final CameraCharacteristics c = manager.getCameraCharacteristics(id);
                sb.append('\n');
                describe(sb, id, c, "listed", true);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    physical.addAll(c.getPhysicalCameraIds());
                }
            } catch (Exception e) {
                sb.append("\nCamera ").append(id).append(": ").append(e).append('\n');
            }
        }
        physical.removeAll(listed);
        for (String id : physical) {
            try {
                sb.append('\n');
                describe(sb, id, manager.getCameraCharacteristics(id), "physical, part of a listed camera", false);
            } catch (Exception e) {
                sb.append("\nPhysical camera ").append(id).append(": ").append(e).append('\n');
            }
        }
        // some phones keep lenses out of the list but still answer for their ids
        final ArrayList<String> hidden = new ArrayList<>();
        for (int i = 0; i < 64; ++i) {
            final String id = String.valueOf(i);
            if (listed.contains(id) || physical.contains(id)) continue;
            try {
                final CameraCharacteristics c = manager.getCameraCharacteristics(id);
                if (c == null) continue;
                hidden.add(id);
                sb.append('\n');
                describe(sb, id, c, "hidden, not listed", false);
            } catch (Exception ignore) {

            }
        }
        if (hidden.isEmpty()) {
            sb.append("\nHidden cameras: none answer by id\n");
        }

        sb.append("\nVideo messages with the current settings:\n");
        for (int a = 0; a < 2; ++a) {
            final boolean front = a == 0;
            final Object[] choice = Camera2Session.choose(front, GlassgramConfig.roundWideCamera && !front, GlassgramConfig.roundFps60, roundVideoSize, roundVideoSize);
            sb.append(front ? "  front: " : "  back: ");
            if (choice == null) {
                sb.append("no camera\n");
                continue;
            }
            final String id = (String) choice[0];
            final Size size = (Size) choice[1];
            sb.append("camera ").append(id).append(", preview ").append(size);
            try {
                final CameraCharacteristics c = manager.getCameraCharacteristics(id);
                final StreamConfigurationMap map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
                final Range<Integer> range = Camera2Session.find60FpsRange(c, map, size);
                sb.append(", 60 FPS: ");
                if (range != null) {
                    sb.append("yes ").append(range);
                } else if (Camera2Session.find60FpsRange(c, null, null) == null) {
                    sb.append("no, the camera has no 60 FPS range");
                } else {
                    sb.append("no, not at this size");
                }
                if (!front && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    final Range<Float> zoom = c.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE);
                    sb.append(", ultra-wide: ").append(zoom != null && zoom.getLower() < 0.99f ? "zoom " + fmt(zoom.getLower()) + "x" : "no zoom below 1x");
                }
            } catch (Exception e) {
                sb.append(", ").append(e);
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    private static void describe(StringBuilder sb, String id, CameraCharacteristics c, String kind, boolean full) {
        final Integer facing = c.get(CameraCharacteristics.LENS_FACING);
        sb.append("Camera ").append(id).append(" — ")
                .append(facing == null ? "?" : facing == CameraCharacteristics.LENS_FACING_FRONT ? "front" : facing == CameraCharacteristics.LENS_FACING_BACK ? "back" : "external")
                .append(", ").append(kind).append('\n');

        final Integer level = c.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL);
        final int[] capabilities = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES);
        sb.append("  level ").append(levelName(level));
        if (capabilities != null) {
            if (!contains(capabilities, CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE)) {
                sb.append(", not a regular camera");
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && contains(capabilities, CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA)) {
                sb.append(", logical multi-camera of ").append(TextUtils.join(", ", c.getPhysicalCameraIds()));
            }
        }
        sb.append('\n');

        final float[] focalLengths = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
        final SizeF physicalSize = c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE);
        if (focalLengths != null && focalLengths.length > 0 && physicalSize != null) {
            final float f = focalLengths[0];
            sb.append("  focal ").append(fmt(f)).append(" mm, sensor ").append(fmt(physicalSize.getWidth())).append('×').append(fmt(physicalSize.getHeight()))
                    .append(" mm, view ").append(degrees(physicalSize.getWidth(), f)).append("°×").append(degrees(physicalSize.getHeight(), f)).append("°\n");
        }

        final Float digitalZoom = c.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM);
        sb.append("  zoom: digital up to ").append(digitalZoom == null ? "?" : fmt(digitalZoom));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            final Range<Float> ratio = c.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE);
            sb.append(", ratio ").append(ratio == null ? "none" : fmt(ratio.getLower()) + "–" + fmt(ratio.getUpper()));
        }
        sb.append('\n');
        if (!full) return;

        final Range<Integer>[] ranges = c.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
        sb.append("  FPS ranges: ").append(ranges == null ? "none" : Arrays.toString(ranges)).append('\n');
        final StreamConfigurationMap map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        if (map != null) {
            final Size[] sizes = map.getOutputSizes(SurfaceTexture.class);
            final ArrayList<String> fast = new ArrayList<>();
            if (sizes != null) {
                for (Size size : sizes) {
                    if (Camera2Session.keeps60Fps(map, size)) fast.add(size.toString());
                }
            }
            sb.append("  sizes at 60 FPS: ").append(fast.isEmpty() ? "none" : TextUtils.join(", ", fast)).append('\n');
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                final Range<Integer>[] highSpeed = map.getHighSpeedVideoFpsRanges();
                if (highSpeed != null && highSpeed.length > 0) {
                    sb.append("  high-speed ranges: ").append(Arrays.toString(highSpeed)).append('\n');
                }
            }
        }

        final int[] video = c.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES);
        sb.append("  video stabilization: ");
        if (video == null || video.length == 0) {
            sb.append("none");
        } else {
            for (int i = 0; i < video.length; ++i) {
                if (i > 0) sb.append(", ");
                sb.append(video[i] == 0 ? "off" : video[i] == 1 ? "on" : video[i] == 2 ? "preview" : String.valueOf(video[i]));
            }
        }
        sb.append('\n');
        final int[] optical = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION);
        sb.append("  optical stabilization: ").append(optical != null && contains(optical, CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON) ? "yes" : "no");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            final int[] oisData = c.get(CameraCharacteristics.STATISTICS_INFO_AVAILABLE_OIS_DATA_MODES);
            sb.append(", lens shift data ").append(oisData != null && contains(oisData, CameraMetadata.STATISTICS_OIS_DATA_MODE_ON) ? "yes" : "no");
        }
        final Integer timestamps = c.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE);
        sb.append(", timestamps ").append(timestamps != null && timestamps == CameraMetadata.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME ? "realtime" : "unknown").append('\n');
    }

    private static String levelName(Integer level) {
        if (level == null) return "?";
        switch (level) {
            case CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY: return "legacy";
            case CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED: return "limited";
            case CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_FULL: return "full";
            case CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_3: return "3";
            case CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL: return "external";
            default: return String.valueOf(level);
        }
    }

    private static int degrees(float size, float focal) {
        return Math.round((float) Math.toDegrees(2 * Math.atan(size / (2 * focal))));
    }

    private static String fmt(float value) {
        return String.format(Locale.US, "%.2f", value).replaceAll("0+$", "").replaceAll("\\.$", "");
    }

    private static boolean contains(int[] values, int value) {
        for (int v : values) {
            if (v == value) return true;
        }
        return false;
    }
}
