// glassGram: the music player's speed, behind JNI, for org.telegram.messenger.glassgramaudio.
//
// Telegram changes the speed of music with Sonic, made for speech: on music it smears and wobbles,
// and its resampling (the pitch going with the speed) draws straight lines between samples. Here:
// - Signalsmith Stretch (MIT) stretches music in the frequency domain, keeping its pitch;
// - glassgram::Resampler resamples it with a windowed sinc, for "speed up / slowed".
// Audio comes and goes as interleaved 16-bit PCM in direct ByteBuffers.

#include <jni.h>
#include <cstdint>
#include <cstring>
#include <vector>
#include <algorithm>

#include "signalsmith-stretch/signalsmith-stretch.h"
#include "resampler.h"

namespace {

struct Stretch {
    signalsmith::stretch::SignalsmithStretch<float> stretch;
    int channels = 0;
    std::vector<std::vector<float>> in, out;

    void ensure(int inFrames, int outFrames) {
        in.resize(channels);
        out.resize(channels);
        for (int c = 0; c < channels; c++) {
            if ((int) in[c].size() < inFrames) in[c].resize(inFrames);
            if ((int) out[c].size() < outFrames) out[c].resize(outFrames);
        }
    }

    // interleaved int16 -> planar float
    void readInput(const int16_t *src, int frames) {
        for (int c = 0; c < channels; c++) {
            float *dst = in[c].data();
            for (int i = 0; i < frames; i++) {
                dst[i] = src[i * channels + c] * (1.0f / 32768.0f);
            }
        }
    }

    // planar float -> interleaved int16
    void writeOutput(int16_t *dst, int frames) {
        for (int c = 0; c < channels; c++) {
            const float *src = out[c].data();
            for (int i = 0; i < frames; i++) {
                float v = src[i] * 32768.0f;
                v = std::max(-32768.0f, std::min(32767.0f, v));
                dst[i * channels + c] = (int16_t) v;
            }
        }
    }
};

struct Planar {
    std::vector<std::vector<float>> &buffers;
    float *operator[](int c) { return buffers[c].data(); }
};

int16_t *address(JNIEnv *env, jobject buffer) {
    return buffer != nullptr ? (int16_t *) env->GetDirectBufferAddress(buffer) : nullptr;
}

} // namespace

extern "C" {

// ---- Signalsmith Stretch: the speed with the pitch kept

JNIEXPORT jlong JNICALL
Java_org_telegram_messenger_glassgramaudio_GlassgramAudioNative_stretchCreate(JNIEnv *, jclass, jint channels, jint sampleRate) {
    if (channels <= 0 || channels > 8 || sampleRate <= 0) {
        return 0;
    }
    auto *s = new Stretch();
    s->channels = channels;
    // the library's default: its best quality for music, blocks of ~120 ms
    s->stretch.presetDefault(channels, (float) sampleRate);
    return (jlong) (intptr_t) s;
}

JNIEXPORT void JNICALL
Java_org_telegram_messenger_glassgramaudio_GlassgramAudioNative_stretchRelease(JNIEnv *, jclass, jlong handle) {
    delete (Stretch *) (intptr_t) handle;
}

// The input frames outputSeek wants for this rate (input frames for each output frame)
JNIEXPORT jint JNICALL
Java_org_telegram_messenger_glassgramaudio_GlassgramAudioNative_stretchSeekLength(JNIEnv *, jclass, jlong handle, jfloat rate) {
    auto *s = (Stretch *) (intptr_t) handle;
    return s != nullptr ? s->stretch.outputSeekLength(rate) : 0;
}

// The start of a stream (a start, a seek, a new speed): its first seekLength frames, so that the
// next output is the stream's own beginning, with no pre-roll and no fade in
JNIEXPORT void JNICALL
Java_org_telegram_messenger_glassgramaudio_GlassgramAudioNative_stretchSeek(JNIEnv *env, jclass, jlong handle, jobject input, jint frames) {
    auto *s = (Stretch *) (intptr_t) handle;
    const int16_t *src = address(env, input);
    if (s == nullptr || frames <= 0 || src == nullptr) {
        return;
    }
    s->ensure(frames, 1);
    s->readInput(src, frames);
    Planar in{s->in};
    s->stretch.outputSeek(in, frames);
}

// frames of input into outFrames of output
JNIEXPORT void JNICALL
Java_org_telegram_messenger_glassgramaudio_GlassgramAudioNative_stretchProcess(JNIEnv *env, jclass, jlong handle, jobject input, jint frames, jobject output, jint outFrames) {
    auto *s = (Stretch *) (intptr_t) handle;
    const int16_t *src = address(env, input);
    int16_t *dst = address(env, output);
    if (s == nullptr || frames <= 0 || src == nullptr || outFrames < 0 || (outFrames > 0 && dst == nullptr)) {
        return;
    }
    s->ensure(frames, std::max(1, (int) outFrames));
    s->readInput(src, frames);
    Planar in{s->in}, out{s->out};
    s->stretch.process(in, frames, out, outFrames);
    if (outFrames > 0) {
        s->writeOutput(dst, outFrames);
    }
}

// The rest, at the end of the stream: what its latency still holds
JNIEXPORT void JNICALL
Java_org_telegram_messenger_glassgramaudio_GlassgramAudioNative_stretchFlush(JNIEnv *env, jclass, jlong handle, jobject output, jint outFrames, jfloat rate) {
    auto *s = (Stretch *) (intptr_t) handle;
    int16_t *dst = address(env, output);
    if (s == nullptr || outFrames <= 0 || dst == nullptr) {
        return;
    }
    s->ensure(1, outFrames);
    Planar out{s->out};
    s->stretch.flush(out, outFrames, rate);
    s->writeOutput(dst, outFrames);
}

// ---- the resampler: the pitch going with the speed

JNIEXPORT jlong JNICALL
Java_org_telegram_messenger_glassgramaudio_GlassgramAudioNative_resamplerCreate(JNIEnv *, jclass, jint channels) {
    if (channels <= 0 || channels > 8) {
        return 0;
    }
    return (jlong) (intptr_t) new glassgram::Resampler(channels);
}

JNIEXPORT void JNICALL
Java_org_telegram_messenger_glassgramaudio_GlassgramAudioNative_resamplerRelease(JNIEnv *, jclass, jlong handle) {
    delete (glassgram::Resampler *) (intptr_t) handle;
}

JNIEXPORT void JNICALL
Java_org_telegram_messenger_glassgramaudio_GlassgramAudioNative_resamplerReset(JNIEnv *, jclass, jlong handle, jfloat rate) {
    auto *r = (glassgram::Resampler *) (intptr_t) handle;
    if (r != nullptr) {
        r->reset(rate);
    }
}

// frames of input (none and end at the end of the stream), up to capacity frames out; how many
JNIEXPORT jint JNICALL
Java_org_telegram_messenger_glassgramaudio_GlassgramAudioNative_resamplerProcess(JNIEnv *env, jclass, jlong handle, jobject input, jint frames, jboolean end, jobject output, jint capacity) {
    auto *r = (glassgram::Resampler *) (intptr_t) handle;
    int16_t *dst = address(env, output);
    if (r == nullptr || dst == nullptr || capacity <= 0) {
        return 0;
    }
    const int16_t *src = address(env, input);
    if (src != nullptr && frames > 0) {
        r->write(src, frames);
    }
    if (end) {
        r->end();
    }
    return r->read(dst, capacity);
}

} // extern "C"
