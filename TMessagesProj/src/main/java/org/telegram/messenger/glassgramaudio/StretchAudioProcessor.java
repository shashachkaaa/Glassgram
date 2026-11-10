package org.telegram.messenger.glassgramaudio;

import androidx.media3.common.C;
import androidx.media3.common.audio.AudioProcessor;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * glassGram: the music's speed changed with its pitch kept, by Signalsmith Stretch, in place of
 * Sonic, which is made for speech and smears music.
 * <p>
 * 16-bit PCM in, the same out, {@code 1 / speed} as long. A stream (from a start, a seek, a new
 * speed) begins with the library's output seek, so what is heard is its own beginning, lined up
 * with the position, with no pre-roll; at its end the rest is flushed out, as long as it should be.
 */
public final class StretchAudioProcessor implements AudioProcessor {

    private volatile float speed = 1f;
    private float configuredSpeed = 1f;
    private AudioFormat pendingFormat = AudioFormat.NOT_SET;
    private AudioFormat format = AudioFormat.NOT_SET;

    private long handle;
    private int handleChannels, handleSampleRate;
    private int seekLength;

    private ByteBuffer pending = EMPTY_BUFFER; // output not taken yet
    private ByteBuffer outBuffer = EMPTY_BUFFER;
    private ByteBuffer scratch = EMPTY_BUFFER; // the input in a direct buffer
    private ByteBuffer seekBuffer = EMPTY_BUFFER;
    private int seekFrames;
    private boolean seeked;
    private double outRemainder;
    private boolean inputEnded;
    private long inputFrames, outputFrames;

    /** The speed from the next flush; 1 makes the processor inactive. */
    public void setSpeed(float speed) {
        this.speed = speed;
    }

    /** Whether it stretches the stream it was flushed for. */
    public boolean isRunning() {
        return handle != 0 && Math.abs(configuredSpeed - 1f) > 0.005f;
    }

    /** Whether it could take the format it was last configured with. */
    public boolean canProcess() {
        return supports(pendingFormat) && GlassgramAudioNative.isAvailable();
    }

    static boolean supports(AudioFormat format) {
        return format.encoding == C.ENCODING_PCM_16BIT && format.channelCount > 0 && format.channelCount <= 8 && format.sampleRate > 0;
    }

    @Override
    public AudioFormat configure(AudioFormat inputAudioFormat) throws UnhandledAudioFormatException {
        pendingFormat = inputAudioFormat;
        return isActive() ? inputAudioFormat : AudioFormat.NOT_SET;
    }

    @Override
    public boolean isActive() {
        return pendingFormat != AudioFormat.NOT_SET && supports(pendingFormat)
            && Math.abs(speed - 1f) > 0.005f
            && GlassgramAudioNative.isAvailable();
    }

    @Override
    public long getDurationAfterProcessorApplied(long durationUs) {
        return (long) (durationUs / (double) configuredSpeed);
    }

    /** The media played out in playoutDuration: the output is made exactly 1 / speed as long. */
    public long getMediaDuration(long playoutDuration) {
        return (long) (playoutDuration * (double) configuredSpeed);
    }

    static ByteBuffer direct(ByteBuffer old, int size) {
        if (old.capacity() < size) {
            return ByteBuffer.allocateDirect(Math.max(size, old.capacity() * 2)).order(ByteOrder.nativeOrder());
        }
        old.clear();
        return old;
    }

    /** Room for bytes more of output, after what was not taken yet. */
    private ByteBuffer outputRoom(int bytes) {
        if (pending.hasRemaining()) {
            final ByteBuffer merged = ByteBuffer.allocateDirect(pending.remaining() + bytes).order(ByteOrder.nativeOrder());
            merged.put(pending);
            outBuffer = merged;
        } else {
            outBuffer = direct(outBuffer, bytes);
        }
        return outBuffer;
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
        scratch = direct(scratch, frames * bpf);
        final ByteBuffer src = inputBuffer.duplicate();
        src.limit(src.position() + frames * bpf);
        scratch.put(src);
        scratch.flip();
        inputBuffer.position(inputBuffer.limit());
        inputFrames += frames;

        int offset = 0;
        if (!seeked) {
            // the stream's first seekLength frames go to the seek
            final int take = Math.min(seekLength - seekFrames, frames);
            final ByteBuffer part = scratch.duplicate();
            part.limit(take * bpf);
            seekBuffer.limit(seekBuffer.capacity()).position(seekFrames * bpf);
            seekBuffer.put(part);
            seekFrames += take;
            offset = take;
            if (seekFrames < seekLength) {
                return;
            }
            seekBuffer.position(0).limit(seekFrames * bpf);
            GlassgramAudioNative.stretchSeek(handle, seekBuffer, seekFrames);
            seeked = true;
            if (offset >= frames) {
                return;
            }
        }
        final int n = frames - offset;
        ByteBuffer in = scratch;
        if (offset > 0) {
            scratch.position(offset * bpf);
            in = scratch.slice();
        }
        final double exact = n / (double) configuredSpeed + outRemainder;
        final int outFrames = (int) Math.floor(exact);
        outRemainder = exact - outFrames;

        final ByteBuffer out = outputRoom(outFrames * bpf);
        final int start = out.position();
        GlassgramAudioNative.stretchProcess(handle, in, n, out.slice(), outFrames);
        out.position(start + outFrames * bpf);
        out.flip();
        pending = out;
        outputFrames += outFrames;
    }

    @Override
    public void queueEndOfStream() {
        if (handle != 0 && !inputEnded) {
            final int bpf = format.bytesPerFrame;
            // all of it 1 / speed as long: what the latency holds, or (a stream too short for the
            // stretch) silence as long
            final long total = Math.round(inputFrames / (double) configuredSpeed);
            final int tail = (int) Math.max(0, total - outputFrames);
            if (tail > 0) {
                final ByteBuffer out = outputRoom(tail * bpf);
                final int start = out.position();
                if (seeked) {
                    GlassgramAudioNative.stretchFlush(handle, out.slice(), tail, configuredSpeed);
                } else {
                    for (int i = 0; i < tail * bpf; i++) {
                        out.put((byte) 0);
                    }
                }
                out.position(start + tail * bpf);
                out.flip();
                pending = out;
                outputFrames += tail;
            }
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
        configuredSpeed = speed;
        pending = EMPTY_BUFFER;
        inputEnded = false;
        seeked = false;
        seekFrames = 0;
        outRemainder = 0;
        inputFrames = outputFrames = 0;
        if (!isActive()) {
            return;
        }
        if (handle == 0 || handleChannels != format.channelCount || handleSampleRate != format.sampleRate) {
            release();
            handle = GlassgramAudioNative.stretchCreate(format.channelCount, format.sampleRate);
            handleChannels = format.channelCount;
            handleSampleRate = format.sampleRate;
        }
        if (handle == 0) {
            return;
        }
        // the seek resets the stretch, and takes this much of the stream's start
        seekLength = Math.max(1, GlassgramAudioNative.stretchSeekLength(handle, configuredSpeed));
        seekBuffer = direct(seekBuffer, seekLength * format.bytesPerFrame);
    }

    private void release() {
        if (handle != 0) {
            GlassgramAudioNative.stretchRelease(handle);
            handle = 0;
        }
    }

    @Override
    public void reset() {
        release();
        speed = configuredSpeed = 1f;
        pendingFormat = format = AudioFormat.NOT_SET;
        pending = outBuffer = scratch = seekBuffer = EMPTY_BUFFER;
        inputEnded = false;
    }
}
