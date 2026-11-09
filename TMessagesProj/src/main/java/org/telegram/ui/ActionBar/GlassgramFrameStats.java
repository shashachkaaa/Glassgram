package org.telegram.ui.ActionBar;

import android.app.Activity;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.view.Display;
import android.view.FrameMetrics;
import android.view.View;
import android.view.Window;

import androidx.annotation.RequiresApi;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.GlassgramConfig;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.Components.BulletinFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * glassGram: how smooth a page slide was, measured on the device itself (glassGram settings,
 * Appearance, the frame counter). The window's FrameMetrics give every frame's times; after the
 * slide a bulletin tells the frames shown against the display's rate, where a frame's time went
 * (the main thread, the render thread, the GPU) and which views of the pages asked to be drawn
 * again meanwhile, each of which costs a whole page drawn into its layer.
 */
@RequiresApi(api = Build.VERSION_CODES.S)
final class GlassgramFrameStats {

    private static final int VSYNC = 0, UI = 1, RT = 2, GPU = 3, TOTAL = 4, DEADLINE = 5, FIELDS = 6;

    private static GlassgramFrameStats current;
    private static Handler handler;

    private final Activity activity;
    private final boolean open;
    private final long startNanos;
    private final float displayHz;
    private final Window.OnFrameMetricsAvailableListener listener;
    /** The frames' times, from the stats thread: guarded by itself */
    private final ArrayList<long[]> frames = new ArrayList<>();
    private long endNanos = Long.MAX_VALUE;

    // What asked a page to be drawn again, on the main thread
    private final HashMap<String, int[]> asked = new HashMap<>();
    private int step, askedFrames, lastAskedStep = -1;

    private GlassgramFrameStats(Activity activity, boolean open) {
        this.activity = activity;
        this.open = open;
        startNanos = System.nanoTime();
        final Display display = activity.getDisplay();
        displayHz = display != null ? display.getRefreshRate() : 60f;
        listener = (window, metrics, dropped) -> {
            final long vsync = metrics.getMetric(FrameMetrics.INTENDED_VSYNC_TIMESTAMP);
            final long[] frame = new long[FIELDS];
            frame[VSYNC] = vsync;
            frame[UI] = metrics.getMetric(FrameMetrics.INPUT_HANDLING_DURATION)
                + metrics.getMetric(FrameMetrics.ANIMATION_DURATION)
                + metrics.getMetric(FrameMetrics.LAYOUT_MEASURE_DURATION)
                + metrics.getMetric(FrameMetrics.DRAW_DURATION);
            frame[RT] = metrics.getMetric(FrameMetrics.SYNC_DURATION)
                + metrics.getMetric(FrameMetrics.COMMAND_ISSUE_DURATION)
                + metrics.getMetric(FrameMetrics.SWAP_BUFFERS_DURATION);
            frame[GPU] = metrics.getMetric(FrameMetrics.GPU_DURATION);
            frame[TOTAL] = metrics.getMetric(FrameMetrics.TOTAL_DURATION);
            frame[DEADLINE] = metrics.getMetric(FrameMetrics.DEADLINE);
            synchronized (frames) {
                if (vsync >= startNanos && vsync <= endNanos) {
                    frames.add(frame);
                }
            }
        };
    }

    static boolean isEnabled() {
        return GlassgramConfig.frameStats;
    }

    /** A slide begins: its frames are counted from now. */
    static void start(Activity activity, boolean open) {
        if (current != null) {
            current.finish();
        }
        if (activity == null || !isEnabled()) {
            return;
        }
        if (handler == null) {
            final HandlerThread thread = new HandlerThread("glassgramFrameStats");
            thread.start();
            handler = new Handler(thread.getLooper());
        }
        current = new GlassgramFrameStats(activity, open);
        try {
            activity.getWindow().addOnFrameMetricsAvailableListener(current.listener, handler);
        } catch (Exception e) {
            FileLog.e(e);
            current = null;
        }
    }

    /** A step of the slide: one frame. */
    static void step() {
        if (current != null) {
            current.step++;
        }
    }

    /** A view in one of the sliding pages asked to be drawn again (held or not). */
    static void pageInvalidated(View target) {
        final GlassgramFrameStats stats = current;
        if (stats == null || target == null) {
            return;
        }
        if (stats.lastAskedStep != stats.step) {
            stats.lastAskedStep = stats.step;
            stats.askedFrames++;
        }
        final String name = nameOf(target);
        final int[] count = stats.asked.get(name);
        if (count == null) {
            stats.asked.put(name, new int[]{1});
        } else {
            count[0]++;
        }
    }

    /** The slide is over: its frames are reported once the last ones are in. */
    static void end() {
        if (current != null) {
            current.finish();
        }
    }

    private void finish() {
        if (current == this) {
            current = null;
        }
        synchronized (frames) {
            endNanos = System.nanoTime();
        }
        // FrameMetrics come a little after their frames
        AndroidUtilities.runOnUIThread(this::report, 400);
    }

    private static String nameOf(View view) {
        final Class<?> type = view.getClass();
        String name = type.getSimpleName();
        if (name.isEmpty()) {
            // an anonymous class: Outer$3 and what it extends
            name = type.getName();
            name = name.substring(name.lastIndexOf('.') + 1) + "(" + type.getSuperclass().getSimpleName() + ")";
        }
        return name;
    }

    private void report() {
        try {
            activity.getWindow().removeOnFrameMetricsAvailableListener(listener);
        } catch (Exception ignore) {
        }
        final long[][] list;
        synchronized (frames) {
            list = frames.toArray(new long[0][]);
        }
        final int n = list.length;
        if (n < 2 || activity.isFinishing()) {
            return;
        }
        Arrays.sort(list, (a, b) -> Long.compare(a[VSYNC], b[VSYNC]));

        // The frame interval the app gets (the display's, or less when the system lowers it)
        final long[] deadlines = new long[n];
        for (int i = 0; i < n; i++) {
            deadlines[i] = list[i][DEADLINE];
        }
        Arrays.sort(deadlines);
        long interval = deadlines[n / 2];
        if (interval <= 0) {
            interval = (long) (1e9 / Math.max(1f, displayHz));
        }
        final long span = list[n - 1][VSYNC] - list[0][VSYNC] + interval;
        final int expected = Math.max(n, Math.round(span / (float) interval));
        final int fps = Math.round(n * 1e9f / span);
        final int appHz = Math.round(1e9f / interval);

        long late = 0;
        final double[] sum = new double[FIELDS];
        final long[] max = new long[FIELDS];
        for (long[] frame : list) {
            for (int f = UI; f <= TOTAL; f++) {
                sum[f] += frame[f];
                max[f] = Math.max(max[f], frame[f]);
            }
            if (frame[TOTAL] > interval) {
                late++;
            }
        }

        final StringBuilder text = new StringBuilder();
        text.append(LocaleController.formatString(open ? R.string.GlassgramFrameStatsOpen : R.string.GlassgramFrameStatsClose,
            fps, Math.round(displayHz), appHz));
        text.append('\n').append(LocaleController.formatString(R.string.GlassgramFrameStatsFrames,
            n, Math.round(span / 1e6f), expected - n, late));
        text.append('\n').append(LocaleController.formatString(R.string.GlassgramFrameStatsTimes,
            ms(sum[UI] / n), ms(max[UI]), ms(sum[RT] / n), ms(max[RT]), ms(sum[GPU] / n), ms(max[GPU])));
        if (askedFrames > 0) {
            final ArrayList<Map.Entry<String, int[]>> entries = new ArrayList<>(asked.entrySet());
            entries.sort((a, b) -> Integer.compare(b.getValue()[0], a.getValue()[0]));
            final StringBuilder who = new StringBuilder();
            for (int i = 0; i < Math.min(3, entries.size()); i++) {
                if (i > 0) {
                    who.append(", ");
                }
                who.append(entries.get(i).getKey()).append(" ×").append(entries.get(i).getValue()[0]);
            }
            text.append('\n').append(LocaleController.formatString(R.string.GlassgramFrameStatsPage, askedFrames, step, who));
        }
        final String summary = text.toString();
        FileLog.d("glassGram frame stats: " + summary.replace('\n', ' '));
        try {
            BulletinFactory.global().createSimpleBulletin(R.raw.info, summary, 6, 8000).show();
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    private static String ms(double nanos) {
        return String.format(Locale.US, "%.1f", nanos / 1e6);
    }
}
