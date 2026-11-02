#ifndef THIRD_PARTY_BLINK_RENDERER_CORE_GEOMETRY_LANDU_CLIENT_RECTS_H_
#define THIRD_PARTY_BLINK_RENDERER_CORE_GEOMETRY_LANDU_CLIENT_RECTS_H_

#include "base/command_line.h"
#include "third_party/blink/public/common/landu/landu_client_rects_noise.h"
#include "third_party/blink/renderer/core/geometry/dom_rect_list.h"

namespace blink {

inline std::optional<uint64_t> LanduClientRectsSeed() {
  if (!base::CommandLine::InitializedForCurrentProcess())
    return std::nullopt;
  return landu::ClientRectsSeed(
      base::CommandLine::ForCurrentProcess()->GetSwitchValueASCII(
          "landu-client-rects-seed"));
}

inline DOMRectList* NoiseLanduClientRects(DOMRectList* list) {
  const auto seed = LanduClientRectsSeed();
  if (!seed)
    return list;
  for (unsigned index = 0; index < list->length(); ++index) {
    DOMRect* rect = list->item(index);
    const auto value = landu::NoiseClientRect(
        {rect->x(), rect->y(), rect->width(), rect->height()}, *seed);
    rect->setX(value.x);
    rect->setY(value.y);
    rect->setWidth(value.width);
    rect->setHeight(value.height);
  }
  return list;
}

inline DOMRect* LanduClientRectsBounds(DOMRectList* list) {
  landu::ClientRectsBounds bounds;
  for (unsigned index = 0; index < list->length(); ++index) {
    const DOMRect* rect = list->item(index);
    bounds.Add({rect->x(), rect->y(), rect->width(), rect->height()});
  }
  const auto value = bounds.Get();
  return value ? DOMRect::Create(value->x, value->y, value->width, value->height)
               : nullptr;
}

}  // namespace blink
#endif
