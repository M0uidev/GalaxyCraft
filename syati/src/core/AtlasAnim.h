#pragma once
#include "gxc_types.h"

namespace gxc
{
// Animated block textures (water, lava, fire, portals...): the mod puts every frame of each in a
// tile of its own after the atlas' other tiles, and after all levels' texels a table (big-endian):
//   u32 'ANIM', u32 count, then count x { u16 live tile, u16 game frames per frame, u16 n, u16 0,
//   n x u16 frame tiles, padded to 4 bytes }.
// Each frame of the game, the live tile (the one models use) takes its current frame's texels.
class AtlasAnim
{
public:
  static const u32 MAX = 256;
  static const u32 MAGIC = 0x414E494D;  // "ANIM"

  // The table at the end of an atlas of these texels (total bytes, texel bytes before it). False
  // (and no animations) if it is not one; no table at all is fine.
  bool Load(const u8* atlas, u32 total, u32 texels, u32 width, u32 height, u32 levels);
  // Frame `frame` of the game: copies into atlas each live tile whose frame changed; lo and hi
  // (bytes) the span written, hi 0 if nothing.
  void Tick(u8* atlas, u32 frame, u32* lo, u32* hi);
  u32 Count() const { return mCount; }

private:
  struct Anim
  {
    u16 live, ticks, frames, shown;
    const u8* list;  // frames x u16 tile
  };
  void CopyTile(u8* atlas, u32 from, u32 to, u32* lo, u32* hi) const;
  Anim mAnims[MAX];
  u32 mCount, mWidth, mHeight, mLevels;
};

// Byte offset of texel (x, y) in a GX RGB5A3 level width texels wide (4x4 texel blocks of 32 bytes).
u32 Rgb5a3Offset(u32 width, u32 x, u32 y);
}  // namespace gxc
