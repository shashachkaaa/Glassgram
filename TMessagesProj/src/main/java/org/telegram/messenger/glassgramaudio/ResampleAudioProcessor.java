package org.telegram.messenger.glassgramaudio;

import androidx.media3.common.audio.AudioProcessor;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * glassGram: "speed up / slowed", the music's pitch going with its speed as on a record: resampled
 * with a windowed sinc by the native library, where Sonic draws straight lines between samples
 * (and lets what is over the new Nyquist fold back).
 * <p>
 * 16-bit PCM in, the same out, {@code 1 / rate} as long.
 */
public final class ResampleAudioProcessor implements AudioProcessor {

    private volatile float rate = 1f;
    private float configuredRate = 1f;
    private AudioFormat pendingFormat = AudioFormat.NOT_SET;
    private AudioFormat format = AudioFormat.NOT_SET;

    private long handle;
    private int handleChannels;

    private ByteBuffer pending = EMPTY_BUFFER;
    private ByteBuffer outBuffer = EMPTY_BUFFER;
    private ByteBuffer scratch = EMPTY_BUFFER;
    private boolean inputEnded;

    /** The rate (input frames for each output frame) from the next flush; 1 makes it inactive. */
    public void setRate(float rate) {
        this.rate = rate;
    }

    /** Whether it resamples the stream it was flushed for. */
    public boolean isRunning() {
        return handle != 0 && Math.abs(configuredRate - 1f) > 0.005f;
    }

    /** Whether it could take the format it was last configured with. */
    public boolean canProcess() {
        return StretchAudioProcessor.supports(pendingFormat) && GlassgramAudioNative.isAvailable();
    }

    @Override
    public AudioFormat configure(AudioFormat inputAudioFormat) throws UnhandledAudioFormatException {
        pendingFormat = inputAudioFormat;
        return isActive() ? inputAudioFormat : AudioFormat.NOT_SET;
    }

    @Override
    public boolean isActive() {
        return pendingFormat != AudioFormat.NOT_SET && StretchAudioProcessor.supports(pendingFormat)
            && Math.abs(rate - 1f) > 0.005f
            && GlassgramAudioNative.isAvailable();
    }

    @Override
    public long getDurationAfterProcessorApplied(long durationUs) {
        return (long) (durationUs / (double) configuredRate);
    }

    public long getMediaDuration(long playoutDuration) {
        return (long) (playoutDuration * (double) configuredRate);
    }

    /** What frames more of input can give, with what the filter still holds. */
    private int capacity(int frames) {
        return (int) Math.ceil((frames + 512) / (double) configuredRate) + 16;
    }

    private void resample(ByteBuffer input, int frames, boolean end) {
        final int bpf = format.bytesPerFrame;
        final int capacity = capacity(frames);
        final ByteBuffer out;
        if (pending.hasRemaining()) {
            out = ByteBuffer.allocateDirect(pending.remaining() + capacity * bpf).order(ByteOrder.nativeOrder());
            out.put(pending);
            outBuffer = out;
        } else {
            out = outBuffer = StretchAudioProcessor.direct(outBuffer, capacity * bpf);
        }
        final int start = out.position();
        final int made = GlassgramAudioNative.resamplerProcess(handle, input, frames, end, out.slice(), capacity);
        out.position(start + Math.max(0, made) * bpf);
        out.flip();
        pending = out;
    }

    @Override
    public void queueInput(ByteBuffer inputBuffer) {
        if (!inputBuffer.hasRemaining()) {
            return;
        }
        if (handle == 0) {
            inputBuffer.position(inputBuffer.limit());
            return;
        }
        final int bpf = format.bytesPerFrame;
        final int frames = inputBuffer.remaining() / bpf;
        scratch = StretchAudioProcessor.direct(scratch, frames * bpf);
        final ByteBuffer src = inputBuffer.duplicate();
        src.limit(src.position() + frames * bpf);
        scratch.put(src);
        scratch.flip();
        inputBuffer.position(inputBuffer.limit());
        resample(scratch, frames, false);
    }

    @Override
    public void queueEndOfStream() {
        if (handle != 0 && !inputEnded) {
            resample(null, 0, true);
        }
        inputEnded = true;
    }

    @Override
    public ByteBuffer getOutput() {
        final ByteBuffer out = pending;
        pending = EMPTY_BUFFER;
        return out;
    }

    @Override
    public boolean isEnded() {
        return inputEnded && !pending.hasRemaining();
    }

    @Override
    @Deprecated
    public void flush() {
        flushState();
    }

    @Override
    public void flush(StreamMetadata streamMetadata) {
        flushState();
    }

    private void flushState() {
        format = pendingFormat;
        configuredRate = rate;
        pending = EMPTY_BUFFER;
        inputEnded = false;
        if (!isActive()) {
            return;
        }
        if (handle == 0 || handleChannels != format.channelCount) {
            release();
            handle = GlassgramAudioNative.resamplerCreate(format.channelCount);
            handleChannels = format.channelCount;
        }
        if (handle != 0) {
            GlassgramAudioNative.resamplerReset(handle, configuredRate);
        }
    }

    private void release() {
        if (handle != 0) {
            GlassgramAudioNative.resamplerRelease(handle);
            handle = 0;
        }
    }

    @Override
    public void reset() {
        release();
        rate = configuredRate = 1f;
        pendingFormat = format = AudioFormat.NOT_SET;
        pending = outBuffer = scratch = EMPTY_BUFFER;
        inputEnded = false;
    }
}
