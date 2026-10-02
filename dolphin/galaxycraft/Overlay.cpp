#include "Overlay.h"

#include "galaxycraft_protocol.h"

namespace gxc
{
std::optional<OverlayFrame> LatestOverlay(const Shm& shm)
{
  const u32 latest = shm.LoadAcquire(GXC_OFF_OVERLAY + offsetof(GxcOverlayHeader, latest));
  if (latest > 2)
    return std::nullopt;
  const u32 w = shm.GetU32(GXC_OFF_OVERLAY + offsetof(GxcOverlayHeader, width));
  const u32 h = shm.GetU32(GXC_OFF_OVERLAY + offsetof(GxcOverlayHeader, height));
  if (w == 0 || h == 0 || w > GXC_OVERLAY_MAX_W || h > GXC_OVERLAY_MAX_H)
    return std::nullopt;
  const u32 id = shm.GetU32(GXC_OFF_OVERLAY + offsetof(GxcOverlayHeader, frame_id) + 4 * latest);
  const u8* pixels = shm.Data() + GXC_OFF_OVERLAY + sizeof(GxcOverlayHeader) +
                     static_cast<size_t>(latest) * GXC_OVERLAY_FRAME_BYTES;
  return OverlayFrame{w, h, id, pixels};
}
}  // namespace gxc
