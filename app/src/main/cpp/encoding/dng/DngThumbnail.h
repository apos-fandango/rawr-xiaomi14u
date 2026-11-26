#pragma once
// X14U (Nov 26): small sRGB thumbnail of the DNG's own RAW, embedded as IFD1 so Google Photos, the system media
// scanner (ExifInterface: a thumbnail must be at most 512 x 512) and galleries show a picture instead of a grey
// placeholder or decoding the whole RAW. debug.rawr.dng_thumb 0 leaves it out.
#include <cstdint>
#include <vector>

#include "encoding/dng/DngWriteParams.h"

namespace rawrcam::encoding::dng {

// RGB8 thumbnail (unrotated, like an EXIF thumbnail) of the bound CFA payload, longest side maxSide. Uses the
// DNG's own black / white, AsShotNeutral, ForwardMatrix1 and BaselineExposure. Empty when the layout is not a 2x2
// Bayer 16-bit payload.
std::vector<uint8_t> makeDngThumbnailRgb(const TinyDngWriteParams& params, uint32_t maxSide, uint32_t* width,
                                         uint32_t* height);

// Baseline JPEG of an RGB8 image (4:2:0); empty on failure.
std::vector<uint8_t> encodeThumbnailJpeg(const std::vector<uint8_t>& rgb, uint32_t width, uint32_t height, int quality);

}  // namespace rawrcam::encoding::dng
