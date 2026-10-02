#pragma once
#include <optional>

#include "Shm.h"

namespace gxc
{
// The newest complete overlay frame the mod published (RGBA8, rows top to bottom).
struct OverlayFrame
{
  u32 width;
  u32 height;
  u32 frame_id;
  const u8* rgba;
};

// Empty if the mod never published a frame or the header is out of range.
std::optional<OverlayFrame> LatestOverlay(const Shm& shm);
}  // namespace gxc
