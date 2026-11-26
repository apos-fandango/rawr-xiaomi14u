#include "encoding/dng/DngThumbnail.h"

#include <algorithm>
#include <cmath>

#ifndef RAWR_DNG_THUMB_NO_JPEG
#include <turbojpeg.h>
#endif

namespace rawrcam::encoding::dng {

std::vector<uint8_t> makeDngThumbnailRgb(const TinyDngWriteParams& params, uint32_t maxSide, uint32_t* outWidth,
                                         uint32_t* outHeight) {
    std::vector<uint8_t> empty;
    const tinydng_write_image& image = params.image;
    const tinydng_raw_info& raw = params.raw;
    const tinydng_cfa& cfa = params.cfa;
    if (!params.pixelData || image.bits_per_sample != 16 || image.samples_per_pixel != 1 || !cfa.present ||
        cfa.pattern_dim[0] != 2 || cfa.pattern_dim[1] != 2 || maxSide < 8)
        return empty;
    const uint32_t fullW = image.width, fullH = image.height;
    // Active area (top, left, bottom, right); the black levels are anchored at its origin.
    uint32_t top = 0, left = 0, bottom = fullH, right = fullW;
    if (raw.has_active_area && raw.active_area[2] > raw.active_area[0] && raw.active_area[3] > raw.active_area[1] &&
        raw.active_area[2] <= fullH && raw.active_area[3] <= fullW) {
        top = raw.active_area[0];
        left = raw.active_area[1];
        bottom = raw.active_area[2];
        right = raw.active_area[3];
    }
    const uint32_t W = (right - left) & ~1u, H = (bottom - top) & ~1u;
    if (W < 16 || H < 16) return empty;
    const double scale = double(maxSide) / std::max(W, H);
    const uint32_t ow = std::max(1u, uint32_t(W * scale)), oh = std::max(1u, uint32_t(H * scale));

    double black[4];
    int color[4];
    for (uint32_t s = 0; s < 4; ++s) {
        // DNG BlackLevel and CFAPattern are both relative to the ActiveArea origin, where sampling starts.
        black[s] = raw.black_level_present ? raw.black_level_exact[s] : 0.0;
        const uint8_t plane = cfa.pattern[s];
        color[s] = cfa.plane_color_count >= 3 && plane < cfa.plane_color_count ? cfa.plane_color[plane] : plane;
        if (color[s] < 0 || color[s] > 2) return empty;
    }
    const double white = raw.white_level[0] > 0 ? double(raw.white_level[0]) : 65535.0;
    double neutral[3] = {1, 1, 1};
    if (raw.has_as_shot_neutral)
        for (int c = 0; c < 3; ++c)
            if (raw.as_shot_neutral[c] > 0) neutral[c] = raw.as_shot_neutral[c];
    // White-balanced camera -> XYZ D50 (ForwardMatrix1) -> linear sRGB (D50-adapted).
    double toSrgb[9] = {1, 0, 0, 0, 1, 0, 0, 0, 1};
    if (raw.has_forward_matrix1) {
        static const double xyzToSrgb[9] = {3.1338561, -1.6168667, -0.4906146, -0.9787684, 1.9161415,
                                            0.0334540, 0.0719453,  -0.2289914, 1.4052427};
        for (int r = 0; r < 3; ++r)
            for (int c = 0; c < 3; ++c) {
                double v = 0;
                for (int k = 0; k < 3; ++k) v += xyzToSrgb[r * 3 + k] * raw.forward_matrix1[k * 3 + c];
                toSrgb[r * 3 + c] = v;
            }
    }

    const uint16_t* px = reinterpret_cast<const uint16_t*>(params.pixelData);
    const double cellW = double(W) / ow, cellH = double(H) / oh;
    const uint32_t stepX = std::max(2u, uint32_t(cellW / 4) & ~1u), stepY = std::max(2u, uint32_t(cellH / 4) & ~1u);
    std::vector<float> sum(size_t(ow) * oh * 3, 0.0f);
    std::vector<uint32_t> count(size_t(ow) * oh, 0);
    for (uint32_t y = 0; y + 1 < H; y += stepY) {
        const uint32_t oy = std::min(oh - 1, uint32_t(y / cellH));
        const uint16_t* row0 = px + size_t(top + y) * fullW + left;
        const uint16_t* row1 = row0 + fullW;
        for (uint32_t x = 0; x + 1 < W; x += stepX) {
            const uint32_t ox = std::min(ow - 1, uint32_t(x / cellW));
            const double v[4] = {double(row0[x]), double(row0[x + 1]), double(row1[x]), double(row1[x + 1])};
            double rgb[3] = {0, 0, 0}, n[3] = {0, 0, 0};
            for (uint32_t s = 0; s < 4; ++s) {
                rgb[color[s]] += std::max(0.0, (v[s] - black[s]) / std::max(1.0, white - black[s]));
                n[color[s]] += 1;
            }
            float* dst = &sum[(size_t(oy) * ow + ox) * 3];
            for (int c = 0; c < 3; ++c) dst[c] += float(n[c] ? rgb[c] / n[c] / neutral[c] : 0.0);
            ++count[size_t(oy) * ow + ox];
        }
    }

    // Exposure: the DNG's BaselineExposure (what raw editors apply); without it, average luminance to mid grey.
    double gain = 1.0;
    if (raw.has_baseline_exposure) {
        gain = std::pow(2.0, std::clamp(raw.baseline_exposure, -4.0, 4.0));
    } else {
        double luma = 0;
        size_t sampled = 0;
        for (size_t i = 0; i < size_t(ow) * oh; ++i)
            if (count[i]) {
                luma += (0.2126 * sum[i * 3] + 0.7152 * sum[i * 3 + 1] + 0.0722 * sum[i * 3 + 2]) / count[i];
                ++sampled;
            }
        gain = sampled && luma > 0 ? std::clamp(0.18 / (luma / sampled), 0.5, 8.0) : 1.0;
    }
    std::vector<uint8_t> out(size_t(ow) * oh * 3);
    for (size_t i = 0; i < size_t(ow) * oh; ++i) {
        const float* cam = &sum[i * 3];
        const double k = count[i] ? gain / count[i] : 0.0;
        for (int r = 0; r < 3; ++r) {
            double v = (toSrgb[r * 3] * cam[0] + toSrgb[r * 3 + 1] * cam[1] + toSrgb[r * 3 + 2] * cam[2]) * k;
            v = std::max(0.0, v);
            // Soft shoulder above 0.8 so highlights roll off instead of clipping flat.
            if (v > 0.8) v = 0.8 + 0.2 * (1.0 - std::exp(-(v - 0.8) / 0.2));
            v = std::min(v, 1.0);
            v = v <= 0.0031308 ? 12.92 * v : 1.055 * std::pow(v, 1 / 2.4) - 0.055;
            out[i * 3 + size_t(r)] = uint8_t(v * 255 + 0.5);
        }
    }
    *outWidth = ow;
    *outHeight = oh;
    return out;
}

#ifndef RAWR_DNG_THUMB_NO_JPEG
std::vector<uint8_t> encodeThumbnailJpeg(const std::vector<uint8_t>& rgb, uint32_t width, uint32_t height,
                                         int quality) {
    std::vector<uint8_t> out;
    if (rgb.size() < size_t(width) * height * 3 || width == 0 || height == 0) return out;
    tjhandle h = tj3Init(TJINIT_COMPRESS);
    if (!h) return out;
    unsigned char* jpeg = nullptr;
    size_t jpegSize = 0;
    if (tj3Set(h, TJPARAM_QUALITY, quality) == 0 && tj3Set(h, TJPARAM_SUBSAMP, TJSAMP_420) == 0 &&
        tj3Compress8(h, rgb.data(), int(width), 0, int(height), TJPF_RGB, &jpeg, &jpegSize) == 0 && jpeg) {
        out.assign(jpeg, jpeg + jpegSize);
    }
    tj3Free(jpeg);
    tj3Destroy(h);
    return out;
}
#endif

}  // namespace rawrcam::encoding::dng
