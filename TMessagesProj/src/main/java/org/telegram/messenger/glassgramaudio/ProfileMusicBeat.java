package org.telegram.messenger.glassgramaudio;

import androidx.annotation.Nullable;
import androidx.annotation.OptIn;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.audio.AudioSink;
import androidx.media3.exoplayer.audio.ForwardingAudioSink;

import java.nio.ByteBuffer;
import java.util.Arrays;

/** Low-frequency onsets in one profile track, timed against the audio sink's playback clock. */
@OptIn(markerClass = UnstableApi.class)
public final class ProfileMusicBeat {
    private static final int HISTORY_SIZE = 64;
    private static final long ATTACK_US = 24_000;
    private static final long DECAY_US = 230_000;
    private static final long MIN_BEAT_GAP_US = 140_000;

    private final long[] times = new long[HISTORY_SIZE];
    private final float[] strengths = new float[HISTORY_SIZE];
    private int next, count;
    private volatile long positionUs = AudioSink.CURRENT_POSITION_NOT_SET;
    // Acquired and released on the UI thread. The renderer only reads this count.
    private volatile int listeners;

    public void acquire() {
        listeners++;
    }

    public void release() {
        listeners--;
    }

    public synchronized float getPulse() {
        final long position = positionUs;
        if (position == AudioSink.CURRENT_POSITION_NOT_SET) return 0;

        float pulse = 0;
        for (int age = 1; age <= count; age++) {
            int index = (next - age + HISTORY_SIZE) % HISTORY_SIZE;
            long elapsed = position - times[index];
            if (elapsed < 0) continue;
            if (elapsed >= ATTACK_US + DECAY_US) break;
            float falloff = 1f - Math.max(0, elapsed - ATTACK_US) / (float) DECAY_US;
            float envelope = Math.min(1f, elapsed / (float) ATTACK_US) * falloff * falloff;
            pulse = Math.max(pulse, strengths[index] * envelope);
        }
        return pulse;
    }

    private synchronized void addBeat(long timeUs, float strength) {
        times[next] = timeUs;
        strengths[next] = strength;
        next = (next + 1) % HISTORY_SIZE;
        count = Math.min(count + 1, HISTORY_SIZE);
    }

    private synchronized void clear() {
        positionUs = AudioSink.CURRENT_POSITION_NOT_SET;
        next = count = 0;
    }

    public AudioSink wrap(AudioSink sink) {
        return new ForwardingAudioSink(sink) {
            private final Detector detector = new Detector(ProfileMusicBeat.this);
            private ByteBuffer pending;
            private boolean analyzing;

            @Override
            public void configure(Format format, int bufferSize, @Nullable int[] channels) throws ConfigurationException {
                super.configure(format, bufferSize, channels);
                detector.configure(format);
            }

            @Override
            public boolean handleBuffer(ByteBuffer buffer, long timeUs, int encodedAccessUnits)
                    throws InitializationException, WriteException {
                final boolean observed = listeners > 0;
                if (analyzing && !observed) detector.reset();
                analyzing = observed;
                if (buffer != pending && observed && timeUs != C.TIME_UNSET) {
                    // The sink may return false and retry this buffer. Read it once, without advancing its position.
                    detector.read(buffer, timeUs);
                }
                pending = buffer;
                final boolean consumed = super.handleBuffer(buffer, timeUs, encodedAccessUnits);
                if (consumed) pending = null;
                return consumed;
            }

            @Override
            public long getCurrentPositionUs(boolean sourceEnded) {
                // This clock and handleBuffer's timestamps use the same stream offset.
                final long position = super.getCurrentPositionUs(sourceEnded);
                if (listeners > 0) positionUs = position;
                return position;
            }

            @Override
            public void flush() {
                pending = null;
                detector.reset();
                super.flush();
            }

            @Override
            public void reset() {
                pending = null;
                detector.reset();
                super.reset();
            }

            @Override
            public void release() {
                pending = null;
                detector.reset();
                super.release();
            }
        };
    }

    private static final class Detector {
        private final ProfileMusicBeat beat;
        private int sampleRate, frameBytes, sampleBytes, windowFrames;
        private boolean floatPcm;
        private float[] bassFilter = new float[0], subFilter = new float[0];
        private float bassWeight, subWeight;
        private float bassPower, signalPower, average, previous;
        private int frames;
        private long lastBeatUs, nextBufferTimeUs;

        Detector(ProfileMusicBeat beat) {
            this.beat = beat;
        }

        void configure(Format format) {
            reset();
            frameBytes = 0;
            sampleRate = format.sampleRate;
            floatPcm = format.pcmEncoding == C.ENCODING_PCM_FLOAT;
            if (sampleRate <= 0 || format.channelCount <= 0 || !MimeTypes.AUDIO_RAW.equals(format.sampleMimeType)
                    || (!floatPcm && format.pcmEncoding != C.ENCODING_PCM_16BIT)) return;
            sampleBytes = floatPcm ? Float.BYTES : Short.BYTES;
            frameBytes = format.channelCount * sampleBytes;
            bassFilter = new float[format.channelCount];
            subFilter = new float[format.channelCount];
            windowFrames = Math.max(1, sampleRate / 50);
            bassWeight = (float) -Math.expm1(-2 * Math.PI * 160 / sampleRate);
            subWeight = (float) -Math.expm1(-2 * Math.PI * 35 / sampleRate);
        }

        void reset() {
            frames = 0;
            Arrays.fill(bassFilter, 0f);
            Arrays.fill(subFilter, 0f);
            bassPower = signalPower = average = previous = 0;
            lastBeatUs = Long.MIN_VALUE / 2;
            nextBufferTimeUs = C.TIME_UNSET;
            beat.clear();
        }

        void read(ByteBuffer pcm, long timeUs) {
            if (frameBytes == 0) return;
            if (nextBufferTimeUs != C.TIME_UNSET && Math.abs(timeUs - nextBufferTimeUs) > 100_000) {
                reset();
            }
            final int first = pcm.position();
            nextBufferTimeUs = timeUs + (pcm.remaining() / frameBytes) * 1_000_000L / sampleRate;
            for (int offset = first; offset <= pcm.limit() - frameBytes; offset += frameBytes) {
                // Sum the energy across channels, so a stereo mix cannot cancel its own bass.
                for (int channel = 0; channel < bassFilter.length; channel++) {
                    final int index = offset + channel * sampleBytes;
                    final float sample = floatPcm ? pcm.getFloat(index) : pcm.getShort(index) / 32768f;
                    bassFilter[channel] += bassWeight * (sample - bassFilter[channel]);
                    subFilter[channel] += subWeight * (sample - subFilter[channel]);
                    final float bass = bassFilter[channel] - subFilter[channel];
                    bassPower += bass * bass;
                    signalPower += sample * sample;
                }
                if (++frames == windowFrames) {
                    final float level = (float) Math.sqrt((bassPower + .06f * signalPower) / (frames * bassFilter.length));
                    final long centerUs = timeUs + ((offset - first) / frameBytes - frames / 2L) * 1_000_000L / sampleRate;
                    final float rise = (level - previous) / Math.max(.009f, previous);
                    final float contrast = (level - average) / Math.max(.012f, average);
                    if (centerUs - lastBeatUs >= MIN_BEAT_GAP_US && level > .009f && rise > .16f && contrast > .3f) {
                        beat.addBeat(centerUs, Math.min(1f, .26f + .34f * contrast + .16f * rise));
                        lastBeatUs = centerUs;
                    }
                    average += .065f * (level - average);
                    previous = level;
                    frames = 0;
                    bassPower = signalPower = 0;
                }
            }
        }
    }
}
