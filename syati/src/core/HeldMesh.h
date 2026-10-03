#pragma once
#include "gxc_types.h"

namespace gxc
{
// What Steve holds (GxcHeld) as Minecraft models it, in its item model space: texels, a block
// being 16 a side, y up, the sprite facing +z. Kinds as GXC_HELD_* in the protocol.
enum HeldKind
{
  HELD_NONE = 0,
  HELD_BLOCK = 1,  // a cube with tiles of the 4x4 block atlas (top, sides, bottom)
  HELD_CUBE = 2,   // a cube with the sprite on every face
  HELD_ITEM = 3,   // the sprite, one texel thick, with a side wherever a texel borders a hole
  HELD_TOOL = 4,   // the same mesh (only how it is held differs)
};

const int HELD_SPRITE = 16;
// The block atlas is 4x4 tiles of 16 texels (tools/voxel_atlas.py).
const int HELD_ATLAS = 64;
// The longest list: two faces and four sides for each texel of the sprite.
const u32 HELD_MAX_QUADS = 2 + 4 * HELD_SPRITE * HELD_SPRITE;
// Vertex format: position u8 x3 with 1 fraction bit (half texels), color RGBA8, texture
// coordinate u8 x2 with 6 fraction bits (64ths: texels of the 64-wide atlas, or 4 per texel of
// the 16-wide sprite).
const u32 HELD_VERTEX_BYTES = 3 + 4 + 2;
const u32 HELD_DL_MAX = (3 + HELD_MAX_QUADS * 4 * HELD_VERTEX_BYTES + 31) & ~31u;

// Whether texel (x, y) of a 16x16 GX RGB5A3 sprite (y = 0 at the top) is not a hole (alpha > 0).
bool SpriteSolid(const u8* sprite, int x, int y);

// The display list (GX_QUADS of vertex format fmt, padded with GX_NOP to 32 bytes) into out (cap
// bytes, HELD_DL_MAX is always enough); its size, 0 if there is nothing to draw. Faces are shaded
// as Minecraft shades blocks: top 1, sides 0.8 and 0.6, bottom 0.5.
u32 HeldMesh(u32 kind, const u32 tiles[3], const u8* sprite, u32 fmt, u8* out, u32 cap);
}  // namespace gxc
