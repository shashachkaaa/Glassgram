// glassGram: the music player's "speed up / slowed" mode, pitch going with the speed as on a
// record: the audio resampled with a Kaiser-windowed sinc, its cutoff lowered for speeding up so
// nothing folds back as aliasing. Telegram's Sonic resamples with straight lines between samples.
#pragma once

#include <cmath>
#include <cstdint>
#include <cstring>
#include <vector>
#include <algorithm>

namespace glassgram {

class Resampler {
public:
    // zero crossings of the sinc on each side, and the table's points between two of them
    static constexpr int HALF = 32;
    static constexpr int OVERSAMPLE = 512;

    explicit Resampler(int channels) : channels(channels), table(kernel()) {
        reset(1.0);
    }

    // rate: input frames for each output frame (2 plays twice as fast, an octave up)
    void reset(double newRate) {
        rate = newRate > 0 ? newRate : 1.0;
        // relative to the input's Nyquist; a little under it, for the filter's transition
        cutoff = std::min(1.0, 1.0 / rate) * 0.94;
        width = (int) std::ceil(HALF / cutoff) + 1;
        // the input starts at frame 0, with zeros before it
        history.assign((size_t) width * channels, 0.0f);
        filled = width;
        base = -width;
        position = 0.0;
        received = 0;
        ended = false;
    }

    // Interleaved int16 in
    void write(const int16_t *src, int frames) {
        if (frames <= 0) {
            return;
        }
        history.resize((size_t) (filled + frames) * channels);
        float *dst = history.data() + (size_t) filled * channels;
        for (int i = 0; i < frames * channels; i++) {
            dst[i] = src[i] * (1.0f / 32768.0f);
        }
        filled += frames;
        received += frames;
    }

    void end() {
        if (ended) {
            return;
        }
        ended = true;
        const int pad = width + 1;
        history.resize((size_t) (filled + pad) * channels, 0.0f);
        std::fill(history.begin() + (size_t) filled * channels, history.end(), 0.0f);
        filled += pad;
    }

    // Up to capacity frames of interleaved int16 out; how many there were
    int read(int16_t *dst, int capacity) {
        int n = 0;
        std::vector<float> &acc = sums;
        acc.assign(channels, 0.0f);
        while (n < capacity) {
            if (ended && position >= (double) received) {
                break;
            }
            const int64_t at = (int64_t) std::floor(position);
            if (at + width >= base + filled) {
                break; // more input first
            }
            std::fill(acc.begin(), acc.end(), 0.0f);
            for (int64_t i = at - width + 1; i <= at + width; i++) {
                const double x = std::fabs((position - (double) i) * cutoff);
                if (x >= HALF) {
                    continue;
                }
                const double t = x * OVERSAMPLE;
                const int k = (int) t;
                const float f = (float) (t - k);
                const float w = table[k] + (table[k + 1] - table[k]) * f;
                const float *frame = history.data() + (size_t) (i - base) * channels;
                for (int c = 0; c < channels; c++) {
                    acc[c] += w * frame[c];
                }
            }
            for (int c = 0; c < channels; c++) {
                float v = acc[c] * (float) cutoff * 32768.0f;
                v = std::max(-32768.0f, std::min(32767.0f, v));
                dst[n * channels + c] = (int16_t) v;
            }
            n++;
            position += rate;
        }
        // what is behind the filter now is not needed any more
        const int64_t keep = (int64_t) std::floor(position) - width;
        const int drop = (int) std::max<int64_t>(0, std::min<int64_t>(keep - base, filled));
        if (drop > 0) {
            std::memmove(history.data(), history.data() + (size_t) drop * channels, (size_t) (filled - drop) * channels * sizeof(float));
            filled -= drop;
            base += drop;
            history.resize((size_t) filled * channels);
        }
        return n;
    }

private:
    int channels;
    double rate = 1.0, cutoff = 0.94;
    int width = HALF + 1;
    std::vector<float> history; // interleaved, from frame `base`
    int filled = 0;
    int64_t base = 0;
    double position = 0.0; // of the next output frame, in input frames
    int64_t received = 0;
    bool ended = false;
    std::vector<float> sums;
    const float *table;

    static double besselI0(double x) {
        double sum = 1.0, term = 1.0;
        for (int k = 1; k < 32; k++) {
            term *= (x / (2.0 * k)) * (x / (2.0 * k));
            sum += term;
            if (term < sum * 1e-12) {
                break;
            }
        }
        return sum;
    }

    // the sinc's right half under a Kaiser window, made once (a static's start is thread-safe)
    static const float *kernel() {
        static const std::vector<float> values = [] {
            std::vector<float> t(HALF * OVERSAMPLE + 2, 0.0f);
            const double beta = 8.0;
            const double norm = besselI0(beta);
            for (int j = 0; j <= HALF * OVERSAMPLE; j++) {
                const double x = j / (double) OVERSAMPLE;
                const double sinc = j == 0 ? 1.0 : std::sin(M_PI * x) / (M_PI * x);
                const double r = x / HALF;
                const double window = besselI0(beta * std::sqrt(std::max(0.0, 1.0 - r * r))) / norm;
                t[j] = (float) (sinc * window);
            }
            return t;
        }();
        return values.data();
    }
};

} // namespace glassgram
