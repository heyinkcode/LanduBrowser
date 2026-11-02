// Copyright 2026 Landu Browser contributors.
// SPDX-License-Identifier: BSD-3-Clause
#ifndef THIRD_PARTY_BLINK_RENDERER_PLATFORM_GRAPHICS_LANDU_CANVAS_READBACK_H_
#define THIRD_PARTY_BLINK_RENDERER_PLATFORM_GRAPHICS_LANDU_CANVAS_READBACK_H_

#include "base/check.h"
#include "base/command_line.h"
#include "third_party/blink/public/common/landu/landu_canvas_noise.h"
#include "third_party/skia/include/core/SkBitmap.h"
#include "third_party/skia/include/core/SkImage.h"
#include "ui/gfx/skia_span_util.h"

namespace blink {
inline std::optional<uint64_t> LanduCanvasSeed() {
  if (!base::CommandLine::InitializedForCurrentProcess()) return std::nullopt;
  const auto* command = base::CommandLine::ForCurrentProcess();
  if (!command->HasSwitch("landu-canvas-seed")) return std::nullopt;
  auto seed = landu::CanvasNoiseSeed(command->GetSwitchValueASCII("landu-canvas-seed"));
  CHECK(seed);  // An invalid enabled setting must not silently leak real pixels.
  return seed;
}

inline bool NoiseLanduCanvasReadback(const SkPixmap& pixels, int origin_x = 0,
                                    int origin_y = 0) {
  const auto seed = LanduCanvasSeed();
  if (!seed) return true;
  landu::CanvasPixelFormat format;
  switch (pixels.colorType()) {
    case kRGBA_8888_SkColorType: format = landu::CanvasPixelFormat::kRgba8; break;
    case kBGRA_8888_SkColorType: format = landu::CanvasPixelFormat::kBgra8; break;
    case kRGBA_F16_SkColorType:
    case kRGBA_F16Norm_SkColorType: format = landu::CanvasPixelFormat::kRgbaF16; break;
    default: return false;
  }
  auto bytes = gfx::SkPixmapToWritableSpan(pixels);
  return landu::NoiseCanvasPixels(std::span<uint8_t>(bytes),
      pixels.width(), pixels.height(), pixels.rowBytes(), format, *seed, origin_x, origin_y);
}

// Prepare one immutable private snapshot before sync, idle or worker encoding.
// Unpremultiplication happens before perturbation, as it does for getImageData.
// Preserve half-float exports; normalize other layouts to lossless RGBA8.
inline sk_sp<SkImage> LanduCanvasExportImage(sk_sp<SkImage> image) {
  if (!LanduCanvasSeed() || !image) return image;
  const bool half = image->colorType() == kRGBA_F16_SkColorType ||
                    image->colorType() == kRGBA_F16Norm_SkColorType;
  const SkImageInfo info = image->imageInfo()
      .makeColorType(half ? kRGBA_F16_SkColorType : kRGBA_8888_SkColorType)
      .makeAlphaType(kUnpremul_SkAlphaType);
  SkBitmap owned;
  if (!owned.tryAllocPixels(info) || !image->readPixels(owned.pixmap(), 0, 0) ||
      !NoiseLanduCanvasReadback(owned.pixmap()))
    return nullptr;
  owned.setImmutable();
  return owned.asImage();
}
}  // namespace blink
#endif
