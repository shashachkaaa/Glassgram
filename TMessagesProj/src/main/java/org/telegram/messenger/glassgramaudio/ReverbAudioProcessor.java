package org.telegram.messenger.glassgramaudio;

import androidx.media3.common.C;
import androidx.media3.common.audio.BaseAudioProcessor;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * glassGram: the music player's reverb, for "slowed + reverb": Freeverb (Jezar at Dreampoint's,
 * public domain), eight comb filters and four all-passes a channel, on 16-bit mono or stereo PCM.
 * <p>
 * It stays in the chain and is turned on and off as the track plays: its wet part fades in and
 * out over a fifth of a second; off, the audio only passes through.
 */
public final class ReverbAudioProcessor extends BaseAudioProcessor {

    private static final int[] COMB_TUNING = {1116, 1188, 1277, 1356, 1422, 1491, 1557, 1617};
    private static final int[] ALLPASS_TUNING = {556, 441, 341, 225};
    private static final int STEREO_SPREAD = 23;
    private static final float FIXED_GAIN = 0.015f;
    // the wet part about 6 dB under the dry one: a big, soft room, as "slowed + reverb" has it
    private static final float ROOM = 0.86f, DAMP = 0.35f, WET = 0.20f, WIDTH = 1f;

    private volatile boolean enabled;
    private float wet; // now, toward WET or 0
    private int channels, sampleRate;
    private float[][] combBuffer, allpassBuffer;
    private int[] combIndex, allpassIndex;
    private float[] combStore;
    private boolean silent = true;

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isEnabled() {
        return enabled;
    }

    @Override
    protected AudioFormat onConfigure(AudioFormat inputAudioFormat) {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT || inputAudioFormat.channelCount < 1 || inputAudioFormat.channelCount > 2) {
            return AudioFormat.NOT_SET;
        }
        return inputAudioFormat;
    }

    @Override
    protected void onFlush(StreamMetadata streamMetadata) {
        final AudioFormat format = inputAudioFormat;
        if (format == AudioFormat.NOT_SET) {
            return;
        }
        if (combBuffer == null || channels != format.channelCount || sampleRate != format.sampleRate) {
            allocate(format);
        } else {
            clear();
        }
        wet = enabled ? WET : 0f;
        silent = !enabled;
    }

    @Override
    protected void onReset() {
        combBuffer = null;
        allpassBuffer = null;
    }

    private void allocate(AudioFormat format) {
        channels = format.channelCount;
        sampleRate = format.sampleRate;
        final float scale = format.sampleRate / 44100f;
        combBuffer = new float[8 * channels][];
        allpassBuffer = new float[4 * channels][];
        combIndex = new int[8 * channels];
        allpassIndex = new int[4 * channels];
        combStore = new float[8 * channels];
        for (int c = 0; c < channels; c++) {
            final int spread = c == 0 ? 0 : STEREO_SPREAD;
            for (int i = 0; i < 8; i++) {
                combBuffer[c * 8 + i] = new float[Math.max(1, Math.round((COMB_TUNING[i] + spread) * scale))];
            }
            for (int i = 0; i < 4; i++) {
                allpassBuffer[c * 4 + i] = new float[Math.max(1, Math.round((ALLPASS_TUNING[i] + spread) * scale))];
            }
        }
    }

    private void clear() {
        for (float[] b : combBuffer) {
            java.util.Arrays.fill(b, 0f);
        }
        for (float[] b : allpassBuffer) {
            java.util.Arrays.fill(b, 0f);
        }
        java.util.Arrays.fill(combStore, 0f);
        java.util.Arrays.fill(combIndex, 0);
        java.util.Arrays.fill(allpassIndex, 0);
    }

    @Override
    public void queueInput(ByteBuffer inputBuffer) {
        final int bytes = inputBuffer.remaining();
        if (bytes == 0) {
            return;
        }
        final ByteBuffer out = replaceOutputBuffer(bytes);
        final float target = enabled ? WET : 0f;
        if (silent && target == 0f) {
            // off: the audio as it is
            out.put(inputBuffer);
            out.flip();
            return;
        }
        if (silent) {
            clear();
            silent = false;
        }
        final ByteBuffer in = inputBuffer.order(ByteOrder.nativeOrder());
        final int frames = bytes / (2 * channels);
        // the wet part moves toward its target over ~0.2 s
        final float step = 1f / Math.max(1f, inputAudioFormat.sampleRate * 0.2f) * WET;
        final float feedback = ROOM, damp1 = DAMP, damp2 = 1f - DAMP;
        for (int f = 0; f < frames; f++) {
            if (wet < target) {
                wet = Math.min(target, wet + step);
            } else if (wet > target) {
                wet = Math.max(target, wet - step);
            }
            final float l = in.getShort() / 32768f;
            final float r = channels == 2 ? in.getShort() / 32768f : l;
            final float input = (l + r) * FIXED_GAIN;
            float outL = 0f, outR = 0f;
            for (int c = 0; c < channels; c++) {
                float acc = 0f;
                for (int i = 0; i < 8; i++) {
                    final int k = c * 8 + i;
                    final float[] buf = combBuffer[k];
                    int idx = combIndex[k];
                    final float output = buf[idx];
                    combStore[k] = output * damp2 + combStore[k] * damp1;
                    buf[idx] = input + combStore[k] * feedback;
                    if (++idx >= buf.length) {
                        idx = 0;
                    }
                    combIndex[k] = idx;
                    acc += output;
                }
                for (int i = 0; i < 4; i++) {
                    final int k = c * 4 + i;
                    final float[] buf = allpassBuffer[k];
                    int idx = allpassIndex[k];
                    final float bufout = buf[idx];
                    final float output = -acc + bufout;
                    buf[idx] = acc + bufout * 0.5f;
                    if (++idx >= buf.length) {
                        idx = 0;
                    }
                    allpassIndex[k] = idx;
                    acc = output;
                }
                if (c == 0) {
                    outL = acc;
                } else {
                    outR = acc;
                }
            }
            if (channels == 1) {
                outR = outL;
            }
            final float wet1 = wet * (WIDTH / 2f + 0.5f), wet2 = wet * ((1f - WIDTH) / 2f);
            // the dry part a little lower while wet, so the sum is about as loud as the track
            final float dry = 1f - wet * 0.75f;
            final float yl = l * dry + (outL * wet1 + outR * wet2) * 3f;
            final float yr = r * dry + (outR * wet1 + outL * wet2) * 3f;
            out.putShort(clamp(yl));
            if (channels == 2) {
                out.putShort(clamp(yr));
            }
        }
        out.flip();
        // off, and faded out: back to passing through
        if (target == 0f && wet == 0f) {
            silent = true;
        }
    }

    /** To 16 bits, the peaks over 0.9 bent softly under 1 rather than cut. */
    private static short clamp(float v) {
        final float a = Math.abs(v);
        if (a > 0.9f) {
            final float bent = 0.9f + 0.1f * (float) Math.tanh((a - 0.9f) / 0.1f);
            v = v < 0 ? -bent : bent;
        }
        return (short) Math.max(-32768f, Math.min(32767f, v * 32768f));
    }
}
