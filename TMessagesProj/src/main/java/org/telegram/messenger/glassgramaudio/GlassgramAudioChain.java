package org.telegram.messenger.glassgramaudio;

import androidx.media3.common.PlaybackParameters;
import androidx.media3.common.audio.AudioProcessor;
import androidx.media3.common.audio.SonicAudioProcessor;
import androidx.media3.exoplayer.audio.DefaultAudioSink;
import androidx.media3.exoplayer.audio.SilenceSkippingAudioProcessor;

/**
 * glassGram: the audio player's processors, ExoPlayer's own (silence skipping, Sonic) and, for
 * music, the client's: the speed with its pitch kept ({@link StretchAudioProcessor}), the speed with
 * the pitch going along ({@link ResampleAudioProcessor}) and the reverb ({@link ReverbAudioProcessor}).
 * <p>
 * The playback parameters pick the processor: for music, pitch 1 is the stretch and the pitch equal
 * to the speed the resampler; anything else (voice messages, and music where the native library is
 * missing) is Sonic's, as in Telegram.
 */
public final class GlassgramAudioChain implements DefaultAudioSink.AudioProcessorChain {

    private final AudioProcessor[] processors;
    private final SilenceSkippingAudioProcessor silenceSkipping = new SilenceSkippingAudioProcessor();
    private final SonicAudioProcessor sonic = new SonicAudioProcessor();
    private final StretchAudioProcessor stretch = new StretchAudioProcessor();
    private final ResampleAudioProcessor resample = new ResampleAudioProcessor();
    private final ReverbAudioProcessor reverb = new ReverbAudioProcessor();

    private volatile boolean music;

    public GlassgramAudioChain(AudioProcessor... userProcessors) {
        processors = new AudioProcessor[userProcessors.length + 5];
        System.arraycopy(userProcessors, 0, processors, 0, userProcessors.length);
        int i = userProcessors.length;
        processors[i++] = silenceSkipping;
        processors[i++] = sonic;
        processors[i++] = stretch;
        processors[i++] = resample;
        processors[i] = reverb;
    }

    /** Music gets the client's speed and the reverb; voice and the rest Telegram's Sonic. */
    public void setMusic(boolean music) {
        this.music = music;
    }

    public void setReverb(boolean enabled) {
        reverb.setEnabled(enabled);
    }

    @Override
    public AudioProcessor[] getAudioProcessors() {
        return processors;
    }

    @Override
    public PlaybackParameters applyPlaybackParameters(PlaybackParameters parameters) {
        final float speed = parameters.speed, pitch = parameters.pitch;
        if (music && Math.abs(pitch - 1f) < 0.001f && stretch.canProcess()) {
            stretch.setSpeed(speed);
            resample.setRate(1f);
            sonic.setSpeed(1f);
            sonic.setPitch(1f);
        } else if (music && Math.abs(pitch - speed) < 0.001f && resample.canProcess()) {
            resample.setRate(speed);
            stretch.setSpeed(1f);
            sonic.setSpeed(1f);
            sonic.setPitch(1f);
        } else {
            stretch.setSpeed(1f);
            resample.setRate(1f);
            sonic.setSpeed(speed);
            sonic.setPitch(pitch);
        }
        return parameters;
    }

    @Override
    public boolean applySkipSilenceEnabled(boolean skipSilenceEnabled) {
        silenceSkipping.setEnabled(skipSilenceEnabled);
        return skipSilenceEnabled;
    }

    @Override
    public long getMediaDuration(long playoutDuration) {
        if (stretch.isRunning()) {
            return stretch.getMediaDuration(playoutDuration);
        }
        if (resample.isRunning()) {
            return resample.getMediaDuration(playoutDuration);
        }
        return sonic.isActive() ? sonic.getMediaDuration(playoutDuration) : playoutDuration;
    }

    @Override
    public long getSkippedOutputFrameCount() {
        return silenceSkipping.getSkippedFrames();
    }
}
