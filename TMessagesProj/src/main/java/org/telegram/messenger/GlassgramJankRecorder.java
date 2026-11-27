package org.telegram.messenger;

import android.app.Activity;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.view.Display;
import android.view.FrameMetrics;
import android.view.Window;

import androidx.annotation.RequiresApi;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * glassGram: what the app was doing in the frames that came late, measured on the phone itself.
 * <p>
 * For a while it takes every frame's times from the window (FrameMetrics: the main thread, the
 * render thread, the GPU, and how long the main thread was busy before the frame could begin) and,
 * a few hundred times a second, where the main thread is (its stack). The stacks taken in a late
 * frame tell what held it: the report sums them by method, over all the late frames, and shows the
 * worst frames one by one. A late frame the main thread didn't hold is the render thread's or the
 * GPU's (drawing, blur, glass): the report says which.
 */
@RequiresApi(api = Build.VERSION_CODES.S)
public final class GlassgramJankRecorder {

    private static final long SAMPLE_MS = 5;
    private static final int RING = 512;
    private static final int TOP = 25;
    private static final int WORST = 6;
    private static final int STACK_SHOWN = 14;

    private static GlassgramJankRecorder current;

    private final Activity activity;
    private final Utilities.Callback<String> done;
    private final Thread main = Looper.getMainLooper().getThread();
    private final HandlerThread metricsThread = new HandlerThread("glassgramJankMetrics");
    private final float displayHz;
    private final long frameNanos;
    private Window.OnFrameMetricsAvailableListener listener;
    private Thread sampler;
    private volatile boolean running;
    private long startNanos;

    // the main thread's stacks of the last moments: guarded by itself
    private final long[] sampleTimes = new long[RING];
    private final StackTraceElement[][] samples = new StackTraceElement[RING][];
    private int sampleNext;

    // on the metrics thread
    private int frames, lateFrames, missedVsyncs, uiLate, rtLate, gpuLate, waitLate;
    private final HashMap<String, long[]> inclusive = new HashMap<>(), self = new HashMap<>();
    private final ArrayList<Late> worst = new ArrayList<>();
    // every frame's main thread, render thread and GPU times, ns
    private long[] uiTimes = new long[1024], rtTimes = new long[1024], gpuTimes = new long[1024];

    private static final class Late {
        long vsync, total, ui, rt, gpu, wait;
        StackTraceElement[] stack;
    }

    private GlassgramJankRecorder(Activity activity, Utilities.Callback<String> done) {
        this.activity = activity;
        this.done = done;
        final Display display = activity.getDisplay();
        displayHz = display != null ? display.getRefreshRate() : 60f;
        frameNanos = (long) (1e9 / Math.max(30f, displayHz));
    }

    public static boolean isRunning() {
        return current != null;
    }

    /** Records for seconds; done gets the report on the UI thread. */
    public static void start(Activity activity, int seconds, Utilities.Callback<String> done) {
        if (current != null || activity == null) {
            return;
        }
        current = new GlassgramJankRecorder(activity, done);
        current.begin();
        AndroidUtilities.runOnUIThread(() -> {
            if (current != null) {
                current.finish();
            }
        }, seconds * 1000L);
    }

    private void begin() {
        try {
            com.exteragram.messenger.plugins.xposed.PyMethodHook.startMainThreadStats();
        } catch (Throwable e) {
            FileLog.e(e);
        }
        startNanos = System.nanoTime();
        running = true;
        metricsThread.start();
        final Handler handler = new Handler(metricsThread.getLooper());
        listener = (window, metrics, dropped) -> onFrame(metrics);
        activity.getWindow().addOnFrameMetricsAvailableListener(listener, handler);
        sampler = new Thread(() -> {
            while (running) {
                final long now = System.nanoTime();
                final StackTraceElement[] stack = main.getStackTrace();
                synchronized (sampleTimes) {
                    sampleTimes[sampleNext] = now;
                    samples[sampleNext] = stack;
                    sampleNext = (sampleNext + 1) % RING;
                }
                try {
                    Thread.sleep(SAMPLE_MS);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }, "glassgramJankSampler");
        sampler.setPriority(Thread.MAX_PRIORITY);
        sampler.start();
    }

    private void onFrame(FrameMetrics metrics) {
        if (!running || metrics.getMetric(FrameMetrics.FIRST_DRAW_FRAME) == 1) {
            return;
        }
        final long vsync = metrics.getMetric(FrameMetrics.INTENDED_VSYNC_TIMESTAMP);
        if (vsync < startNanos) {
            return;
        }
        if (frames == uiTimes.length) {
            uiTimes = java.util.Arrays.copyOf(uiTimes, frames * 2);
            rtTimes = java.util.Arrays.copyOf(rtTimes, frames * 2);
            gpuTimes = java.util.Arrays.copyOf(gpuTimes, frames * 2);
        }
        uiTimes[frames] = metrics.getMetric(FrameMetrics.INPUT_HANDLING_DURATION)
                + metrics.getMetric(FrameMetrics.ANIMATION_DURATION)
                + metrics.getMetric(FrameMetrics.LAYOUT_MEASURE_DURATION)
                + metrics.getMetric(FrameMetrics.DRAW_DURATION);
        rtTimes[frames] = metrics.getMetric(FrameMetrics.SYNC_DURATION)
                + metrics.getMetric(FrameMetrics.COMMAND_ISSUE_DURATION)
                + metrics.getMetric(FrameMetrics.SWAP_BUFFERS_DURATION);
        gpuTimes[frames] = metrics.getMetric(FrameMetrics.GPU_DURATION);
        frames++;
        final long total = metrics.getMetric(FrameMetrics.TOTAL_DURATION);
        final long deadline = metrics.getMetric(FrameMetrics.DEADLINE);
        final long budget = deadline > 0 ? deadline : frameNanos;
        if (total <= budget * 3 / 2) {
            return;
        }
        lateFrames++;
        missedVsyncs += (int) Math.max(1, (total - budget) / frameNanos);
        final Late late = new Late();
        late.vsync = vsync;
        late.total = total;
        late.wait = metrics.getMetric(FrameMetrics.UNKNOWN_DELAY_DURATION);
        late.ui = metrics.getMetric(FrameMetrics.INPUT_HANDLING_DURATION)
                + metrics.getMetric(FrameMetrics.ANIMATION_DURATION)
                + metrics.getMetric(FrameMetrics.LAYOUT_MEASURE_DURATION)
                + metrics.getMetric(FrameMetrics.DRAW_DURATION);
        late.rt = metrics.getMetric(FrameMetrics.SYNC_DURATION)
                + metrics.getMetric(FrameMetrics.COMMAND_ISSUE_DURATION)
                + metrics.getMetric(FrameMetrics.SWAP_BUFFERS_DURATION);
        late.gpu = metrics.getMetric(FrameMetrics.GPU_DURATION);
        // what held it most
        final long most = Math.max(Math.max(late.wait, late.ui), Math.max(late.rt, late.gpu));
        if (most == late.wait) waitLate++;
        else if (most == late.ui) uiLate++;
        else if (most == late.rt) rtLate++;
        else gpuLate++;

        // the main thread's stacks while the frame was due and being made
        final long from = vsync, to = vsync + late.wait + late.ui;
        final ArrayList<StackTraceElement[]> in = new ArrayList<>();
        synchronized (sampleTimes) {
            for (int i = 0; i < RING; i++) {
                if (samples[i] != null && sampleTimes[i] >= from && sampleTimes[i] <= to) {
                    in.add(samples[i]);
                }
            }
        }
        final HashSet<String> seen = new HashSet<>();
        StackTraceElement[] longest = null;
        for (StackTraceElement[] stack : in) {
            seen.clear();
            boolean first = true;
            for (StackTraceElement e : stack) {
                if (!isApp(e)) {
                    continue;
                }
                final String name = name(e);
                if (first) {
                    add(self, name, SAMPLE_MS);
                    first = false;
                }
                if (seen.add(name)) {
                    add(inclusive, name, SAMPLE_MS);
                }
            }
            if (first) {
                add(self, idle(stack) ? "(main thread idle: render thread / GPU)" : "(system: " + (stack.length > 0 ? name(stack[0]) : "?") + ")", SAMPLE_MS);
            }
            if (longest == null || appDepth(stack) > appDepth(longest)) {
                longest = stack;
            }
        }
        late.stack = longest;
        worst.add(late);
        Collections.sort(worst, (a, b) -> Long.compare(b.total, a.total));
        if (worst.size() > WORST) {
            worst.remove(worst.size() - 1);
        }
    }

    private static boolean isApp(StackTraceElement e) {
        final String c = e.getClassName();
        return c.startsWith("org.telegram.") || c.startsWith("com.exteragram.") || c.startsWith("tw.nekomimi.")
                || c.startsWith("androidx.recyclerview.") || c.startsWith("com.chaquo.");
    }

    private static boolean idle(StackTraceElement[] stack) {
        return stack.length > 0 && ("nativePollOnce".equals(stack[0].getMethodName()) || "nativePollOnce".equals(stack.length > 1 ? stack[1].getMethodName() : ""));
    }

    private static int appDepth(StackTraceElement[] stack) {
        int n = 0;
        for (StackTraceElement e : stack) {
            if (isApp(e)) n++;
        }
        return n;
    }

    private static String name(StackTraceElement e) {
        String c = e.getClassName();
        c = c.replace("org.telegram.ui.Components.", "").replace("org.telegram.ui.ActionBar.", "")
                .replace("org.telegram.ui.", "").replace("org.telegram.messenger.", "");
        return c + "." + e.getMethodName();
    }

    private static void add(Map<String, long[]> map, String key, long ms) {
        long[] v = map.get(key);
        if (v == null) {
            map.put(key, v = new long[1]);
        }
        v[0] += ms;
    }

    private void finish() {
        current = null;
        running = false;
        try {
            activity.getWindow().removeOnFrameMetricsAvailableListener(listener);
        } catch (Exception ignore) {
        }
        if (sampler != null) {
            sampler.interrupt();
        }
        List<String> hookStats;
        try {
            hookStats = com.exteragram.messenger.plugins.xposed.PyMethodHook.finishMainThreadStats();
        } catch (Throwable e) {
            hookStats = Collections.singletonList(e.toString());
        }
        final List<String> collectedHooks = hookStats;
        // the last frames' metrics come a moment later
        new Handler(metricsThread.getLooper()).postDelayed(() -> {
            final String report = report(collectedHooks);
            metricsThread.quitSafely();
            AndroidUtilities.runOnUIThread(() -> done.run(report));
        }, 300);
    }

    private String report(List<String> hooks) {
        final StringBuilder r = new StringBuilder();
        final double seconds = (System.nanoTime() - startNanos) / 1e9;
        r.append("glassGram ").append(BuildVars.BUILD_VERSION_STRING).append(" · ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
                .append(" · Android ").append(Build.VERSION.RELEASE)
                .append(String.format(Locale.US, "\n%.0f s, display %.0f Hz: %d frames, %d late (%d vsyncs missed)",
                        seconds, displayHz, frames, lateFrames, missedVsyncs))
                .append(String.format(Locale.US, "\nLate frames held most by: main thread busy before the frame %d, main thread drawing %d, render thread %d, GPU %d",
                        waitLate, uiLate, rtLate, gpuLate));
        r.append(String.format(Locale.US, "\nAll frames, ms (average / 90%% / max): main %s · render %s · GPU %s",
                spread(uiTimes, frames), spread(rtTimes, frames), spread(gpuTimes, frames)));
        r.append("\n\nPlugins' hooks on the main thread, the whole recording:");
        if (hooks.isEmpty()) {
            r.append("\n  none");
        }
        for (int i = 0; i < Math.min(15, hooks.size()); i++) {
            r.append("\n  ").append(hooks.get(i));
        }
        r.append("\n\nMain thread in late frames, ms (with what it called):");
        top(r, inclusive);
        r.append("\n\nMain thread in late frames, ms (where it was itself):");
        top(r, self);
        r.append("\n\nWorst frames:");
        for (Late late : worst) {
            r.append(String.format(Locale.US, "\n\n%.0f ms at %.1f s: waited %.0f, main %.0f, render %.0f, GPU %.0f",
                    late.total / 1e6, (late.vsync - startNanos) / 1e9, late.wait / 1e6, late.ui / 1e6, late.rt / 1e6, late.gpu / 1e6));
            if (late.stack != null) {
                int shown = 0;
                for (StackTraceElement e : late.stack) {
                    if (isApp(e)) {
                        r.append("\n  ").append(name(e)).append(':').append(e.getLineNumber());
                        if (++shown >= STACK_SHOWN) break;
                    }
                }
            }
        }
        return r.toString();
    }

    private static String spread(long[] times, int count) {
        if (count == 0) {
            return "-";
        }
        final long[] sorted = java.util.Arrays.copyOf(times, count);
        java.util.Arrays.sort(sorted);
        long sum = 0;
        for (int i = 0; i < count; i++) {
            sum += sorted[i];
        }
        return String.format(Locale.US, "%.1f/%.1f/%.0f", sum / 1e6 / count, sorted[Math.min(count - 1, count * 9 / 10)] / 1e6, sorted[count - 1] / 1e6);
    }

    private static void top(StringBuilder r, HashMap<String, long[]> map) {
        final List<Map.Entry<String, long[]>> entries = new ArrayList<>(map.entrySet());
        Collections.sort(entries, (a, b) -> Long.compare(b.getValue()[0], a.getValue()[0]));
        for (int i = 0; i < Math.min(TOP, entries.size()); i++) {
            r.append(String.format(Locale.US, "\n%6d  %s", entries.get(i).getValue()[0], entries.get(i).getKey()));
        }
    }
}
