#pragma once
#include "gxc_types.h"

namespace gxc
{
// What Steve holds (GxcHeld) as Minecraft models it, in its item model space: texels, a block
// being 16 a side, y up, the sprite facing +z. Kinds as GXC_HELD_* in the protocol.
enum HeldKind
{
  HELD_NONE = 0,
  HELD_BLOCK = 1,  // a cube: the sprite's band 0 on top, band 1 on the sides, band 2 below
  HELD_CUBE = 2,   // a cube with band 0 on every face
  HELD_ITEM = 3,   // band 0, one texel thick, with a side wherever a texel borders a hole
  HELD_TOOL = 4,   // the same mesh (only how it is held differs)
};

const int HELD_SPRITE = 16;
// The sprite is 16 texels wide and 4 bands of 16 tall (GxcHeld): 16x64 RGB5A3.
const int HELD_BANDS = 4;
const u32 HELD_SPRITE_BYTES = HELD_SPRITE * HELD_SPRITE * HELD_BANDS * 2;
// The longest list: two faces and four sides for each texel of the sprite.
const u32 HELD_MAX_QUADS = 2 + 4 * HELD_SPRITE * HELD_SPRITE;
// Vertex format: position u8 x3 with 1 fraction bit (half texels), color RGBA8, texture
// coordinate u8 x2 with 7 fraction bits (128ths of the 16x64 sprite: 8 per texel across, 2 down).
const int HELD_TEX_FRAC = 7;
const u32 HELD_VERTEX_BYTES = 3 + 4 + 2;
const u32 HELD_DL_MAX = (3 + HELD_MAX_QUADS * 4 * HELD_VERTEX_BYTES + 31) & ~31u;

// Whether texel (x, y) of the sprite's band 0 (GX RGB5A3, y = 0 at the top) is not a hole (alpha > 0).
bool SpriteSolid(const u8* sprite, int x, int y);

// The display list (GX_QUADS of vertex format fmt, padded with GX_NOP to 32 bytes) into out (cap
// bytes, HELD_DL_MAX is always enough); its size, 0 if there is nothing to draw. Faces are shaded
// as Minecraft shades blocks: top 1, sides 0.8 and 0.6, bottom 0.5.
u32 HeldMesh(u32 kind, const u8* sprite, u32 fmt, u8* out, u32 cap);
}  // namespace gxc
