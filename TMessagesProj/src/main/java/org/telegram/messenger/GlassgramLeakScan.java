package org.telegram.messenger;

import android.app.Activity;
import android.os.Debug;

import org.telegram.ui.ActionBar.BaseFragment;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.ref.WeakReference;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.zip.GZIPInputStream;

import shark.AndroidMetadataExtractor;
import shark.AndroidObjectInspectors;
import shark.AndroidReferenceMatchers;
import shark.HeapAnalysis;
import shark.HeapAnalysisFailure;
import shark.HeapAnalysisSuccess;
import shark.HeapAnalyzer;
import shark.HeapField;
import shark.HeapObject;
import shark.Leak;
import shark.LeakTrace;
import shark.LeakingObjectFinder;
import shark.OnAnalysisProgressListener;
import shark.ProguardMapping;
import shark.ProguardMappingReader;

/**
 * glassGram: finds what keeps closed screens in memory, on the phone itself.
 * <p>
 * A crash report counts the screens still in memory after they closed (GlassgramCrashReporter),
 * but not what holds them. This dumps the heap and reads it with shark, LeakCanary's analyzer: for
 * each closed screen (BaseFragment) and destroyed activity still there, the chain of references
 * from what the system keeps (a static field, a thread, a native reference) down to it.
 * <p>
 * Full builds are renamed by R8: their names are read back with the build's mapping, which CI
 * publishes on GitHub under the id the build's own stack traces carry.
 */
public final class GlassgramLeakScan {

    /** A closed screen or activity, for the analysis to find: its referent. Kept by name in R8's rules. */
    public static final class Closed extends WeakReference<Object> {
        Closed(Object referent) {
            super(referent);
        }
    }

    private static final String CLOSED_CLASS = "org.telegram.messenger.GlassgramLeakScan$Closed";
    private static final String MAPPING_URL = "https://github.com/shashachkaaa/Glassgram/releases/download/mappings/mapping-%s.txt.gz";
    private static final int MAX_TRACES = 12;

    private static volatile boolean running;
    // Weak, so they keep nothing; strong only while the heap is dumped and read
    private static ArrayList<Closed> markers;

    private GlassgramLeakScan() {
    }

    public static boolean isRunning() {
        return running;
    }

    /**
     * Dumps the heap and finds what holds the closed screens; done gets the report (or an error)
     * on the UI thread. The app stops for a few seconds while the heap is written.
     */
    public static void run(Utilities.Callback2<String, String> done) {
        if (running) {
            return;
        }
        running = true;
        new Thread(() -> {
            String report = null, error = null;
            File dump = null;
            try {
                // what the collector frees anyway is no leak
                for (int i = 0; i < 3; i++) {
                    Runtime.getRuntime().gc();
                    System.runFinalization();
                    Thread.sleep(300);
                }
                final int[] counts = new int[2];
                final ArrayList<Closed> found = new ArrayList<>();
                final Object lock = new Object();
                synchronized (lock) {
                    AndroidUtilities.runOnUIThread(() -> {
                        collect(found, counts);
                        synchronized (lock) {
                            lock.notifyAll();
                        }
                    });
                    lock.wait(5000);
                }
                if (found.isEmpty()) {
                    report = LocaleController.getString(R.string.GlassgramLeaksNone);
                    return;
                }
                markers = found;
                dump = new File(ApplicationLoader.applicationContext.getCacheDir(), "glassgram-leaks.hprof");
                dump.delete();
                Debug.dumpHprofData(dump.getAbsolutePath());
                markers = null;
                final ProguardMapping mapping = mapping();
                final HeapAnalysis analysis = new HeapAnalyzer(OnAnalysisProgressListener.Companion.getNO_OP()).analyze(
                        dump,
                        GlassgramLeakScan::closedObjects,
                        AndroidReferenceMatchers.Companion.getAppDefaults(),
                        false,
                        AndroidObjectInspectors.Companion.getAppDefaults(),
                        AndroidMetadataExtractor.INSTANCE,
                        mapping);
                report = describe(analysis, counts, mapping != null);
            } catch (Throwable e) {
                FileLog.e(e);
                error = e.toString();
            } finally {
                markers = null;
                if (dump != null) {
                    dump.delete();
                }
                running = false;
                final String r = report, err = error;
                AndroidUtilities.runOnUIThread(() -> done.run(r, err));
            }
        }, "GlassgramLeakScan").start();
    }

    /** UI thread: a marker for each closed screen and destroyed activity still in memory. */
    private static void collect(ArrayList<Closed> out, int[] counts) {
        final List<BaseFragment> screens = GlassgramCrashReporter.closedScreens();
        for (int i = 0; i < screens.size(); i++) {
            out.add(new Closed(screens.get(i)));
        }
        counts[0] = screens.size();
        final List<Activity> activities = GlassgramCrashReporter.destroyedActivities();
        for (int i = 0; i < activities.size(); i++) {
            out.add(new Closed(activities.get(i)));
        }
        counts[1] = activities.size();
    }

    /** What the markers point to, in the dump. */
    private static Set<Long> closedObjects(shark.HeapGraph graph) {
        final Set<Long> ids = new HashSet<>();
        HeapObject.HeapClass closed = graph.findClassByName(CLOSED_CLASS);
        if (closed == null) {
            closed = graph.findClassByName(Closed.class.getName());
        }
        if (closed == null) {
            return ids;
        }
        for (Iterator<HeapObject.HeapInstance> it = closed.getInstances().iterator(); it.hasNext(); ) {
            final HeapField referent = it.next().get("java.lang.ref.Reference", "referent");
            final Long id = referent != null ? referent.getValue().getAsNonNullObjectId() : null;
            if (id != null) {
                ids.add(id);
            }
        }
        return ids;
    }

    /** The build's R8 mapping, so the report has the source's names; null for a build R8 didn't rename, or offline. */
    private static ProguardMapping mapping() {
        final String file = new Throwable().getStackTrace()[0].getFileName();
        if (file == null || !file.startsWith("r8-map-id-")) {
            return null;
        }
        final String id = file.substring("r8-map-id-".length());
        final File cached = new File(ApplicationLoader.applicationContext.getCacheDir(), "glassgram-mapping-" + id + ".txt.gz");
        HttpURLConnection connection = null;
        try {
            if (!cached.exists()) {
                connection = (HttpURLConnection) new URL(String.format(MAPPING_URL, id)).openConnection();
                connection.setConnectTimeout(15000);
                connection.setReadTimeout(30000);
                if (connection.getResponseCode() != 200) {
                    return null;
                }
                final File part = new File(cached.getPath() + ".part");
                try (InputStream in = connection.getInputStream(); OutputStream out = new FileOutputStream(part)) {
                    final byte[] buffer = new byte[64 * 1024];
                    int read;
                    while ((read = in.read(buffer)) > 0) {
                        out.write(buffer, 0, read);
                    }
                }
                if (!part.renameTo(cached)) {
                    return null;
                }
            }
            try (InputStream in = new GZIPInputStream(new BufferedInputStream(new FileInputStream(cached), 64 * 1024))) {
                return new ProguardMappingReader(in).readProguardMapping();
            }
        } catch (Throwable e) {
            // the names stay R8's: the report can still be read back with the mapping
            FileLog.e(e);
            return null;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private static String describe(HeapAnalysis analysis, int[] counts, boolean named) {
        final StringBuilder report = new StringBuilder();
        report.append("glassGram ").append(BuildVars.BUILD_VERSION_STRING)
                .append("\nClosed screens in memory: ").append(counts[0])
                .append(", destroyed activities: ").append(counts[1]);
        if (!named) {
            report.append("\nNames as R8 made them (no mapping)");
        }
        if (analysis instanceof HeapAnalysisFailure) {
            return report.append("\n\n").append(((HeapAnalysisFailure) analysis).getException()).toString();
        }
        final HeapAnalysisSuccess success = (HeapAnalysisSuccess) analysis;
        final ArrayList<Leak> leaks = new ArrayList<>();
        leaks.addAll(success.getApplicationLeaks());
        leaks.addAll(success.getLibraryLeaks());
        if (leaks.isEmpty()) {
            return report.append("\n\n").append(LocaleController.getString(R.string.GlassgramLeaksNone)).toString();
        }
        // one trace per kind of chain, the commonest first
        java.util.Collections.sort(leaks, (a, b) -> Integer.compare(b.getLeakTraces().size(), a.getLeakTraces().size()));
        int shown = 0;
        for (Leak leak : leaks) {
            final List<LeakTrace> traces = leak.getLeakTraces();
            if (traces.isEmpty()) {
                continue;
            }
            report.append("\n\n").append(traces.size()).append(" × ").append(leak.getShortDescription())
                    .append('\n').append(traces.get(0));
            if (++shown >= MAX_TRACES) {
                break;
            }
        }
        return report.toString();
    }
}
