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

u32 AtlasBytes(u32 width, u32 height, u32 levels)
{
  u32 bytes = 0;
  for (u32 l = 0; l < levels; l++)
    bytes += (width >> l) * (height >> l) * 2;
  return bytes;
}

namespace
{
bool PowerOfTwo(u32 v, u32 lo, u32 hi)
{
  return v >= lo && v <= hi && (v & (v - 1)) == 0;
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
    // kind, three reserved words, the sprite (GxcHeld); kinds up to TOOL.
    if (len != 16 + 2048)
      return false;
    out->held.kind = ReadBE32(p);
    out->held.sprite = p + 16;
    if (out->held.kind > 4)
      return false;
  }
  else if (type == InboxRecord::ATLAS)
  {
    // GxcAtlas, then its data: a power-of-two texture of 8 to 1024 texels a side, 1 to 4 levels
    // (each at least 8 texels: GX blocks are 4x4), whose total the header gets right.
    const u32 HEAD = 24;
    if (len < HEAD)
      return false;
    InboxAtlas& a = out->atlas;
    a.id = ReadBE32(p);
    a.width = ReadBE32(p + 4);
    a.height = ReadBE32(p + 8);
    a.levels = ReadBE32(p + 12);
    a.total = ReadBE32(p + 16);
    a.offset = ReadBE32(p + 20);
    a.size = len - HEAD;
    a.data = p + HEAD;
    if (!PowerOfTwo(a.width, 8, 1024) || !PowerOfTwo(a.height, 8, 1024) || a.levels < 1 || a.levels > 4 ||
        (a.width >> (a.levels - 1)) < 8 || (a.height >> (a.levels - 1)) < 8 ||
        a.total != AtlasBytes(a.width, a.height, a.levels) || a.offset > a.total || a.size > a.total - a.offset)
      return false;
  }
  else if (type == InboxRecord::SKIN)
  {
    if (len < 12)
      return false;
    InboxSkin& k = out->skin;
    k.id = ReadBE32(p);
    k.width = ReadBE32(p + 4);
    k.height = ReadBE32(p + 8);
    k.data = p + 12;
    if (k.id >= ENT_MAX_SKINS || k.width == 0 || k.height == 0 || k.width % 4 || k.height % 4 ||
        k.width > ENT_SKIN_MAX || k.height > ENT_SKIN_MAX || len != 12 + k.width * k.height * 2)
      return false;
  }
  else if (type == InboxRecord::MODEL)
  {
    if (len < 8)
      return false;
    InboxModel& m = out->model;
    m.id = ReadBE32(p);
    m.dl_size = ReadBE32(p + 4);
    m.dl = p + 8;
    if (m.id >= ENT_MAX_MODELS || m.dl_size == 0 || m.dl_size % 32 || m.dl_size > ENT_DL_MAX ||
        len != 8 + m.dl_size)
      return false;
  }
  else if (type == InboxRecord::ENTITIES)
  {
    if (len < 4)
      return false;
    out->entities.count = ReadBE32(p);
    out->entities.list = p + 4;
    if (out->entities.count > ENT_MAX || len != 4 + out->entities.count * ENT_BYTES)
      return false;
  }
  else if (type == InboxRecord::SEAT)
  {
    if (len != 16)
      return false;
    for (int k = 0; k < 3; k++)
      out->seat.pos[k] = ReadF32(p + 4 * k);
    out->seat.riding = ReadBE32(p + 12);
  }
  else if (type == InboxRecord::HURT)
  {
    if (len != 16)
      return false;
    for (int k = 0; k < 3; k++)
      out->hurt.from[k] = ReadF32(p + 4 * k);
    out->hurt.kind = ReadBE32(p + 12);
  }
  else if (type == InboxRecord::TELEPORT)
  {
    if (len != 0 && len != 4)
      return false;
    out->teleport.ground = len == 4 ? ReadF32(p) : 0.f;
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

bool SphereOutsideView(const f32 proj[7], const f32 v[3], f32 r)
{
  if (proj[0] != 0.f)
    return false;
  // Seen where -w <= m00 x + m02 z <= w (w = -z), and the same in y: the four side planes through
  // the camera, with these normals (pointing out of the view).
  const f32 planes[4][3] = {{proj[1], 0.f, proj[2] + 1.f}, {-proj[1], 0.f, 1.f - proj[2]},
                            {0.f, proj[3], proj[4] + 1.f}, {0.f, -proj[3], 1.f - proj[4]}};
  for (int i = 0; i < 4; i++)
  {
    const f32* n = planes[i];
    const f32 len = Sqrt(n[0] * n[0] + n[1] * n[1] + n[2] * n[2]);
    if (n[0] * v[0] + n[1] * v[1] + n[2] * v[2] > r * len)
      return true;
  }
  return false;
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
