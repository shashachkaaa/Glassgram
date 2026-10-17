package org.telegram.messenger;

import android.media.AudioFormat;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.os.Build;
import android.text.TextUtils;

import org.json.JSONObject;
import org.telegram.tgnet.TLRPC;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * glassGram's offline transcription of voice and video messages, with Vosk on the phone: no
 * Premium and no internet. A small model of the language (about 45 MB) is downloaded once from
 * alphacephei.com; the message's audio is decoded with the phone's own decoders, taken down to
 * 16 kHz mono and given to the recognizer.
 */
public class GlassgramVoice {

    public static final String LANG_RU = "ru", LANG_EN = "en";
    public static final String[] LANGS = {LANG_RU, LANG_EN};

    private static final int SAMPLE_RATE = 16000;
    // The model is kept in memory while messages are transcribed one after another
    private static final long MODEL_KEEP_MS = 2 * 60 * 1000;
    // A message not downloaded yet is waited for this long
    private static final long FILE_WAIT_MS = 60 * 1000;

    private static final DispatchQueue queue = new DispatchQueue("glassgramVoice");
    private static Object model; // org.vosk.Model, loaded on the queue
    private static String modelLang;
    private static final Runnable releaseModel = GlassgramVoice::releaseModel;

    private static volatile String downloadingLang;
    private static volatile boolean downloadCancelled;
    private static volatile long downloadDone, downloadTotal;

    public static String modelUrl(String lang) {
        return LANG_EN.equals(lang)
            ? "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip"
            : "https://alphacephei.com/vosk/models/vosk-model-small-ru-0.22.zip";
    }

    public static File modelDir(String lang) {
        return new File(new File(ApplicationLoader.getFilesDirFixed(), "vosk"), lang);
    }

    public static boolean isModelReady(String lang) {
        return new File(modelDir(lang), ".ready").exists();
    }

    /** Whether messages can be transcribed on the phone now: turned on, and the model is here. */
    public static boolean isReady() {
        return GlassgramConfig.offlineTranscription && Build.VERSION.SDK_INT >= 29 && isModelReady(GlassgramConfig.offlineTranscriptionLang);
    }

    /** Whether this account's messages are transcribed on the phone rather than by Telegram (Premium). */
    public static boolean useFor(int account) {
        return isReady() && !UserConfig.getInstance(account).isPremium();
    }

    public static long modelSize(String lang) {
        return dirSize(modelDir(lang));
    }

    private static long dirSize(File dir) {
        long size = 0;
        final File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                size += f.isDirectory() ? dirSize(f) : f.length();
            }
        }
        return size;
    }

    private static void deleteDir(File dir) {
        final File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.isDirectory()) {
                    deleteDir(f);
                } else {
                    f.delete();
                }
            }
        }
        dir.delete();
    }

    public static void deleteModel(String lang) {
        queue.postRunnable(() -> {
            if (lang.equals(modelLang)) {
                releaseModel();
            }
            deleteDir(modelDir(lang));
        });
    }

    /* Downloading */

    public interface DownloadListener {
        void onProgress(long done, long total);
        void onDone(boolean ok);
    }

    public static boolean isDownloading(String lang) {
        return lang.equals(downloadingLang);
    }

    public static float downloadProgress() {
        return downloadTotal > 0 ? Math.min(1f, downloadDone / (float) downloadTotal) : 0f;
    }

    public static void cancelDownload() {
        downloadCancelled = true;
    }

    /** Downloads and unpacks the model of a language; the listener is called on the UI thread. */
    public static void download(String lang, DownloadListener listener) {
        if (downloadingLang != null) {
            return;
        }
        downloadingLang = lang;
        downloadCancelled = false;
        downloadDone = downloadTotal = 0;
        new Thread(() -> {
            boolean ok = false;
            final File zip = new File(ApplicationLoader.applicationContext.getCacheDir(), "vosk-" + lang + ".zip");
            final File tmp = new File(modelDir(lang).getParentFile(), lang + ".tmp");
            try {
                HttpURLConnection connection = (HttpURLConnection) new URL(modelUrl(lang)).openConnection();
                connection.setConnectTimeout(15000);
                connection.setReadTimeout(30000);
                connection.setInstanceFollowRedirects(true);
                if (connection.getResponseCode() / 100 != 2) {
                    throw new Exception("HTTP " + connection.getResponseCode());
                }
                downloadTotal = connection.getContentLengthLong();
                try (InputStream in = new BufferedInputStream(connection.getInputStream()); OutputStream out = new FileOutputStream(zip)) {
                    final byte[] buffer = new byte[64 * 1024];
                    long lastReport = 0;
                    int n;
                    while ((n = in.read(buffer)) > 0) {
                        if (downloadCancelled) {
                            throw new InterruptedException();
                        }
                        out.write(buffer, 0, n);
                        downloadDone += n;
                        final long now = System.currentTimeMillis();
                        if (now - lastReport > 200) {
                            lastReport = now;
                            final long done = downloadDone, total = downloadTotal;
                            AndroidUtilities.runOnUIThread(() -> listener.onProgress(done, total));
                        }
                    }
                }
                connection.disconnect();
                // the archive holds one folder with the model: its contents go to the language's folder
                deleteDir(tmp);
                tmp.mkdirs();
                try (ZipInputStream in = new ZipInputStream(new BufferedInputStream(new java.io.FileInputStream(zip)))) {
                    final byte[] buffer = new byte[64 * 1024];
                    ZipEntry entry;
                    final String root = tmp.getCanonicalPath() + File.separator;
                    while ((entry = in.getNextEntry()) != null) {
                        if (downloadCancelled) {
                            throw new InterruptedException();
                        }
                        String name = entry.getName();
                        final int slash = name.indexOf('/');
                        if (slash < 0 || slash == name.length() - 1) {
                            continue;
                        }
                        name = name.substring(slash + 1);
                        final File file = new File(tmp, name);
                        if (!file.getCanonicalPath().startsWith(root)) {
                            continue;
                        }
                        if (entry.isDirectory()) {
                            file.mkdirs();
                            continue;
                        }
                        file.getParentFile().mkdirs();
                        try (OutputStream out = new FileOutputStream(file)) {
                            int n;
                            while ((n = in.read(buffer)) > 0) {
                                out.write(buffer, 0, n);
                            }
                        }
                    }
                }
                final File dir = modelDir(lang);
                deleteDir(dir);
                if (!tmp.renameTo(dir) || !new File(dir, ".ready").createNewFile()) {
                    throw new Exception("can't keep the model");
                }
                ok = true;
            } catch (InterruptedException ignore) {
            } catch (Exception e) {
                FileLog.e(e);
            } finally {
                zip.delete();
                deleteDir(tmp);
                downloadingLang = null;
            }
            final boolean result = ok;
            AndroidUtilities.runOnUIThread(() -> listener.onDone(result));
        }, "glassgramVoiceDownload").start();
    }

    /* Transcribing */

    /**
     * Transcribes a voice or video message; done gets the text, "" when nothing was said, or null
     * when it could not be done. It is called on the UI thread.
     */
    public static void transcribe(MessageObject messageObject, Utilities.Callback<String> done) {
        final int account = messageObject.currentAccount;
        final TLRPC.Document document = messageObject.getDocument();
        if (locate(messageObject) == null && document != null) {
            // not downloaded yet: it loads as when it is played
            FileLoader.getInstance(account).loadFile(document, messageObject, FileLoader.PRIORITY_HIGH, 0);
        }
        final String lang = GlassgramConfig.offlineTranscriptionLang;
        queue.postRunnable(() -> {
            String text = null;
            try {
                File file = locate(messageObject);
                final long until = System.currentTimeMillis() + FILE_WAIT_MS;
                while (file == null && System.currentTimeMillis() < until) {
                    Thread.sleep(250);
                    file = locate(messageObject);
                }
                if (file != null) {
                    text = recognize(file, lang);
                }
            } catch (Throwable e) {
                FileLog.e(e);
                text = null;
            }
            queue.cancelRunnable(releaseModel);
            queue.postRunnable(releaseModel, MODEL_KEEP_MS);
            final String result = text;
            AndroidUtilities.runOnUIThread(() -> done.run(result));
        });
    }

    private static File locate(MessageObject messageObject) {
        if (messageObject.messageOwner == null) {
            return null;
        }
        if (!TextUtils.isEmpty(messageObject.messageOwner.attachPath)) {
            final File file = new File(messageObject.messageOwner.attachPath);
            if (file.exists() && file.length() > 0) {
                return file;
            }
        }
        final File file = FileLoader.getInstance(messageObject.currentAccount).getPathToMessage(messageObject.messageOwner);
        return file != null && file.exists() && file.length() > 0 ? file : null;
    }

    private static org.vosk.Model model(String lang) throws Exception {
        if (model != null && lang.equals(modelLang)) {
            return (org.vosk.Model) model;
        }
        releaseModel();
        org.vosk.LibVosk.setLogLevel(org.vosk.LogLevel.WARNINGS);
        model = new org.vosk.Model(modelDir(lang).getAbsolutePath());
        modelLang = lang;
        return (org.vosk.Model) model;
    }

    private static void releaseModel() {
        if (model != null) {
            try {
                ((org.vosk.Model) model).close();
            } catch (Throwable e) {
                FileLog.e(e);
            }
            model = null;
            modelLang = null;
        }
    }

    private static String recognize(File file, String lang) throws Exception {
        final org.vosk.Recognizer recognizer = new org.vosk.Recognizer(model(lang), SAMPLE_RATE);
        final StringBuilder text = new StringBuilder();
        try {
            decode(file, (samples, count) -> {
                if (recognizer.acceptWaveForm(samples, count)) {
                    append(text, recognizer.getResult());
                }
            });
            append(text, recognizer.getFinalResult());
        } finally {
            recognizer.close();
        }
        if (text.length() > 0) {
            text.setCharAt(0, Character.toUpperCase(text.charAt(0)));
        }
        return text.toString();
    }

    private static void append(StringBuilder text, String json) {
        try {
            final String part = new JSONObject(json).optString("text", "").trim();
            if (!part.isEmpty()) {
                if (text.length() > 0) {
                    text.append(' ');
                }
                text.append(part);
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    private interface PcmConsumer {
        void accept(short[] samples, int count);
    }

    // The audio track decoded and given on as 16 kHz mono
    private static void decode(File file, PcmConsumer consumer) throws Exception {
        final MediaExtractor extractor = new MediaExtractor();
        MediaCodec codec = null;
        try {
            extractor.setDataSource(file.getAbsolutePath());
            int track = -1;
            MediaFormat format = null;
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                final MediaFormat f = extractor.getTrackFormat(i);
                final String mime = f.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) {
                    track = i;
                    format = f;
                    break;
                }
            }
            if (track < 0) {
                throw new Exception("no audio");
            }
            extractor.selectTrack(track);
            codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME));
            codec.configure(format, null, null, 0);
            codec.start();

            int rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE);
            int channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
            boolean floatPcm = false;
            Resampler resampler = new Resampler(rate);
            float[] mono = new float[0];
            short[] out = new short[0];

            final MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean inputDone = false;
            while (true) {
                if (!inputDone) {
                    final int in = codec.dequeueInputBuffer(10000);
                    if (in >= 0) {
                        final ByteBuffer buffer = codec.getInputBuffer(in);
                        final int size = buffer == null ? -1 : extractor.readSampleData(buffer, 0);
                        if (size < 0) {
                            codec.queueInputBuffer(in, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        } else {
                            codec.queueInputBuffer(in, 0, size, extractor.getSampleTime(), 0);
                            extractor.advance();
                        }
                    }
                }
                final int outIndex = codec.dequeueOutputBuffer(info, 10000);
                if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    final MediaFormat outFormat = codec.getOutputFormat();
                    channels = outFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                    floatPcm = outFormat.containsKey(MediaFormat.KEY_PCM_ENCODING) && outFormat.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT;
                    final int outRate = outFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                    if (outRate != rate) {
                        rate = outRate;
                        resampler = new Resampler(rate);
                    }
                } else if (outIndex >= 0) {
                    final ByteBuffer buffer = codec.getOutputBuffer(outIndex);
                    if (buffer != null && info.size > 0) {
                        buffer.position(info.offset);
                        buffer.limit(info.offset + info.size);
                        buffer.order(ByteOrder.nativeOrder());
                        final int frames = info.size / ((floatPcm ? 4 : 2) * Math.max(1, channels));
                        if (mono.length < frames) {
                            mono = new float[frames];
                        }
                        for (int i = 0; i < frames; i++) {
                            float sum = 0;
                            for (int c = 0; c < channels; c++) {
                                sum += floatPcm ? buffer.getFloat() : buffer.getShort() / 32768f;
                            }
                            mono[i] = sum / Math.max(1, channels);
                        }
                        final int need = resampler.maxOutput(frames);
                        if (out.length < need) {
                            out = new short[need];
                        }
                        final int count = resampler.feed(mono, frames, out);
                        if (count > 0) {
                            consumer.accept(out, count);
                        }
                    }
                    codec.releaseOutputBuffer(outIndex, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        break;
                    }
                }
            }
        } finally {
            if (codec != null) {
                try {
                    codec.stop();
                } catch (Exception ignore) {}
                codec.release();
            }
            extractor.release();
        }
    }

    // From the decoder's rate to 16 kHz: a box filter against aliasing, then linear interpolation
    private static final class Resampler {
        private final double step;
        private final float[] window;
        private int windowPos;
        private float windowSum;
        private double pos;
        private float last;

        Resampler(int rate) {
            step = rate / (double) SAMPLE_RATE;
            window = new float[Math.max(1, (int) Math.round(step))];
        }

        int maxOutput(int frames) {
            return (int) Math.ceil(frames / step) + 2;
        }

        int feed(float[] in, int n, short[] out) {
            // filtered in place: each sample the mean of the last ones
            for (int i = 0; i < n; i++) {
                windowSum += in[i] - window[windowPos];
                window[windowPos] = in[i];
                windowPos = (windowPos + 1) % window.length;
                in[i] = windowSum / window.length;
            }
            int k = 0;
            while (true) {
                final int i0 = (int) Math.floor(pos);
                final int i1 = i0 + 1;
                if (i1 >= n) {
                    break;
                }
                final float a = i0 < 0 ? last : in[i0];
                final float v = a + (in[i1] - a) * (float) (pos - i0);
                out[k++] = (short) Math.max(-32768, Math.min(32767, Math.round(v * 32767)));
                pos += step;
            }
            pos -= n;
            if (n > 0) {
                last = in[n - 1];
            }
            return k;
        }
    }
}
