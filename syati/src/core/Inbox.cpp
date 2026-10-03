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
    if (len != 36)
      return false;
    out->planet.id = ReadBE32(p);
    for (int k = 0; k < 3; k++)
      out->planet.center[k] = ReadF32(p + 4 + 4 * k);
    out->planet.surface = ReadF32(p + 16);
    out->planet.gravity_range = ReadF32(p + 20);
    out->planet.chunk_count = ReadBE32(p + 24);
    out->planet.occluder = ReadF32(p + 28);
    out->planet.mario_radius = ReadF32(p + 32);
    if (out->planet.chunk_count > max_slots)
      return false;
  }
  else if (type == InboxRecord::CHUNK)
  {
    const u32 HEAD = 32;
    if (len < HEAD)
      return false;
    InboxChunk& c = out->chunk;
    c.slot = ReadBE32(p);
    c.version = ReadBE32(p + 4);
    c.dl_size = ReadBE32(p + 8);
    c.kcl_size = ReadBE32(p + 12);
    for (int k = 0; k < 4; k++)
      c.sphere[k] = ReadF32(p + 16 + 4 * k);
    if (c.slot >= max_slots || c.dl_size % 32 != 0 || c.kcl_size % 4 != 0 || c.dl_size > len - HEAD ||
        c.kcl_size != len - HEAD - c.dl_size || (c.dl_size == 0 && c.kcl_size != 0))
      return false;
    c.dl = p + HEAD;
    c.kcl = p + HEAD + c.dl_size;
  }
  else if (type == InboxRecord::OUTLINE)
  {
    if (len != 100)
      return false;
    out->outline.visible = ReadBE32(p);
    for (int k = 0; k < 24; k++)
      out->outline.corners[k / 3][k % 3] = ReadF32(p + 4 + 4 * k);
  }
  else if (type == InboxRecord::HELD)
  {
    // kind, three tiles, the sprite (GxcHeld); tiles of the 4x4 atlas, kinds up to TOOL.
    if (len != 16 + 512)
      return false;
    out->held.kind = ReadBE32(p);
    for (int k = 0; k < 3; k++)
      out->held.tiles[k] = ReadBE32(p + 4 + 4 * k);
    out->held.sprite = p + 16;
    if (out->held.kind > 4 || out->held.tiles[0] > 15 || out->held.tiles[1] > 15 || out->held.tiles[2] > 15)
      return false;
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

bool SphereHidden(const f32 cam[3], const f32 fwd[3], const f32 center[3], f32 occluder, const f32 c[3], f32 r)
{
  const f32 v[3] = {c[0] - cam[0], c[1] - cam[1], c[2] - cam[2]};
  if (v[0] * fwd[0] + v[1] * fwd[1] + v[2] * fwd[2] < -r)
    return true;  // behind the camera
  const f32 p[3] = {center[0] - cam[0], center[1] - cam[1], center[2] - cam[2]};
  const f32 d2 = p[0] * p[0] + p[1] * p[1] + p[2] * p[2];
  const f32 R2 = occluder * occluder;
  if (d2 <= R2)
    return false;
  const f32 x2 = v[0] * v[0] + v[1] * v[1] + v[2] * v[2];
  const f32 x = Sqrt(x2), d = Sqrt(d2);
  // Nearer than the horizon (where the view grazes the ball): in front of it.
  if (x - r <= Sqrt(d2 - R2))
    return false;
  if (r >= x)
    return false;
  // Angle between the sphere and the ball's center, widened by the sphere's own angular size,
  // must stay inside the ball's angular radius a: cos(theta + delta) > cos(a).
  const f32 cos_t = (v[0] * p[0] + v[1] * p[1] + v[2] * p[2]) / (x * d);
  if (cos_t <= 0.f)
    return false;
  const f32 sin_t = Sqrt(1.f - cos_t * cos_t);
  const f32 sin_dl = r / x, cos_dl = Sqrt(1.f - sin_dl * sin_dl);
  const f32 cos_a = Sqrt(1.f - R2 / d2);
  return cos_t * cos_dl - sin_t * sin_dl > cos_a;
}

void ViewEye(const f32 view[12], f32 eye[3], f32 fwd[3])
{
  for (int k = 0; k < 3; k++)
  {
    eye[k] = -(view[k] * view[3] + view[4 + k] * view[7] + view[8 + k] * view[11]);
    fwd[k] = -view[8 + k];
  }
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
