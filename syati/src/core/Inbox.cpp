#include "Inbox.h"

#include "ViewMath.h"

namespace gxc
{
u32 ReadBE32(const u8* p)
{
  return (u32(p[0]) << 24) | (u32(p[1]) << 16) | (u32(p[2]) << 8) | u32(p[3]);
}

namespace
{
f32 ReadF32(const u8* p)
{
  union
  {
    u32 u;
    f32 f;
  } v;
  v.u = ReadBE32(p);
  return v.f;
}
}  // namespace

bool NextInboxRecord(const u8* records, u32 bytes, u32* offset, u32 max_slots, InboxRecord* out)
{
  const u32 at = *offset;
  if (at + 8 > bytes)
    return false;
  const u32 type = ReadBE32(records + at) >> 16;
  const u32 len = ReadBE32(records + at + 4);
  if (len > bytes - at - 8)
    return false;
  const u8* p = records + at + 8;
  out->type = type;
  if (type == InboxRecord::PLANET)
  {
    if (len != 24)
      return false;
    out->planet.id = ReadBE32(p);
    for (int k = 0; k < 3; k++)
      out->planet.center[k] = ReadF32(p + 4 + 4 * k);
    out->planet.surface = ReadF32(p + 16);
    out->planet.gravity_range = ReadF32(p + 20);
  }
  else if (type == InboxRecord::CHUNK)
  {
    if (len < 16)
      return false;
    InboxChunk& c = out->chunk;
    c.slot = ReadBE32(p);
    c.version = ReadBE32(p + 4);
    c.dl_size = ReadBE32(p + 8);
    c.kcl_size = ReadBE32(p + 12);
    if (c.slot >= max_slots || c.dl_size % 32 != 0 || c.kcl_size % 4 != 0 || c.dl_size > len - 16 ||
        c.kcl_size != len - 16 - c.dl_size || (c.dl_size == 0) != (c.kcl_size == 0))
      return false;
    c.dl = p + 16;
    c.kcl = p + 16 + c.dl_size;
  }
  else if (type == InboxRecord::TELEPORT)
  {
    if (len != 0)
      return false;
  }
  else
  {
    return false;
  }
  *offset = at + 8 + ((len + 3) & ~3u);
  return true;
}

void PlanetDrop(const f32 center[3], f32 surface, f32 above, const f32 mario[3], f32 out[3])
{
  f32 d[3] = {mario[0] - center[0], mario[1] - center[1], mario[2] - center[2]};
  const f32 len = Sqrt(d[0] * d[0] + d[1] * d[1] + d[2] * d[2]);
  if (len < 1.f)
    d[0] = 0.f, d[1] = 1.f, d[2] = 0.f;
  else
    d[0] /= len, d[1] /= len, d[2] /= len;
  for (int k = 0; k < 3; k++)
    out[k] = center[k] + d[k] * (surface + above);
}

void ViewTranslate(const f32 view[12], const f32 t[3], f32 out[12])
{
  for (int r = 0; r < 3; r++)
  {
    const f32* v = view + 4 * r;
    out[4 * r] = v[0], out[4 * r + 1] = v[1], out[4 * r + 2] = v[2];
    out[4 * r + 3] = v[0] * t[0] + v[1] * t[1] + v[2] * t[2] + v[3];
  }
}
}  // namespace gxc
