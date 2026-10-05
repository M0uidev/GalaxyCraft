#include "AtlasAnim.h"

namespace gxc
{
namespace
{
u32 BE32(const u8* p)
{
  return (u32(p[0]) << 24) | (u32(p[1]) << 16) | (u32(p[2]) << 8) | u32(p[3]);
}

u16 BE16(const u8* p)
{
  return static_cast<u16>((u32(p[0]) << 8) | p[1]);
}

const u32 TILE = 16;
}  // namespace

u32 Rgb5a3Offset(u32 width, u32 x, u32 y)
{
  return (((y >> 2) * (width >> 2) + (x >> 2)) * 16 + (y & 3) * 4 + (x & 3)) * 2;
}

bool AtlasAnim::Load(const u8* atlas, u32 total, u32 texels, u32 width, u32 height, u32 levels)
{
  mCount = 0;
  mWidth = width, mHeight = height, mLevels = levels;
  if (total == texels)
    return true;
  if (total < texels + 8 || BE32(atlas + texels) != MAGIC)
    return false;
  const u32 count = BE32(atlas + texels + 4), tiles = (width / TILE) * (height / TILE);
  if (count > MAX)
    return false;
  u32 at = texels + 8;
  for (u32 i = 0; i < count; i++)
  {
    if (at + 8 > total)
      return mCount = 0, false;
    Anim& a = mAnims[i];
    a.live = BE16(atlas + at), a.ticks = BE16(atlas + at + 2), a.frames = BE16(atlas + at + 4);
    a.shown = 0xFFFF;
    a.list = atlas + at + 8;
    const u32 bytes = (8 + 2u * a.frames + 3) & ~3u;
    if (a.frames == 0 || a.ticks == 0 || a.live >= tiles || at + bytes > total)
      return mCount = 0, false;
    for (u32 f = 0; f < a.frames; f++)
      if (BE16(a.list + 2 * f) >= tiles)
        return mCount = 0, false;
    at += bytes;
  }
  mCount = count;
  return true;
}

void AtlasAnim::Tick(u8* atlas, u32 frame, u32* lo, u32* hi)
{
  *lo = 0xFFFFFFFF, *hi = 0;
  for (u32 i = 0; i < mCount; i++)
  {
    Anim& a = mAnims[i];
    const u16 now = static_cast<u16>((frame / a.ticks) % a.frames);
    if (now == a.shown)
      continue;
    a.shown = now;
    CopyTile(atlas, BE16(a.list + 2 * now), a.live, lo, hi);
  }
  if (*hi == 0)
    *lo = 0;
}

void AtlasAnim::CopyTile(u8* atlas, u32 from, u32 to, u32* lo, u32* hi) const
{
  const u32 across = mWidth / TILE;
  u32 base = 0;
  for (u32 l = 0; l < mLevels; l++)
  {
    const u32 w = mWidth >> l, h = mHeight >> l, t = TILE >> l;
    if (t == 0)
      break;
    const u32 fx = (from % across) * t, fy = (from / across) * t, tx = (to % across) * t, ty = (to / across) * t;
    for (u32 y = 0; y < t; y++)
      for (u32 x = 0; x < t; x++)
      {
        const u32 s = base + Rgb5a3Offset(w, fx + x, fy + y), d = base + Rgb5a3Offset(w, tx + x, ty + y);
        atlas[d] = atlas[s];
        atlas[d + 1] = atlas[s + 1];
        if (d < *lo)
          *lo = d;
        if (d + 2 > *hi)
          *hi = d + 2;
      }
    base += w * h * 2;
  }
}
}  // namespace gxc
