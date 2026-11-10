package org.telegram.messenger.glassgramaudio;

import org.telegram.messenger.FileLog;

import java.nio.ByteBuffer;

/**
 * glassGram: the music player's speed in its own native library (jni/glassgram_audio): Signalsmith
 * Stretch (MIT), the speed with the pitch kept, and a windowed-sinc resampler, the pitch going with
 * the speed. Audio is interleaved 16-bit PCM in direct buffers.
 */
public final class GlassgramAudioNative {

    private static Boolean available;

    /** Whether the native library is there; without it the speed falls back to Telegram's Sonic. */
    public static synchronized boolean isAvailable() {
        if (available == null) {
            boolean loaded;
            try {
                System.loadLibrary("glassgramaudio");
                loaded = true;
            } catch (Throwable e) {
                FileLog.e(e);
                loaded = false;
            }
            available = loaded;
        }
        return available;
    }

    private GlassgramAudioNative() {
    }

    static native long stretchCreate(int channels, int sampleRate);

    static native void stretchRelease(long handle);

    /** The input frames {@link #stretchSeek} takes at this rate (input frames for an output frame). */
    static native int stretchSeekLength(long handle, float rate);

    static native void stretchSeek(long handle, ByteBuffer input, int frames);

    static native void stretchProcess(long handle, ByteBuffer input, int frames, ByteBuffer output, int outFrames);

    static native void stretchFlush(long handle, ByteBuffer output, int outFrames, float rate);

    static native long resamplerCreate(int channels);

    static native void resamplerRelease(long handle);

    static native void resamplerReset(long handle, float rate);

    /** Writes up to capacity frames into output; returns how many. */
    static native int resamplerProcess(long handle, ByteBuffer input, int frames, boolean end, ByteBuffer output, int capacity);
}
