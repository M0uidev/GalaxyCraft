#include "HeldMesh.h"

namespace gxc
{
namespace
{
const u8 GX_QUADS = 0x80;
// Shades of the faces facing +x, -x, +y, -y, +z, -z.
const u8 SHADE[6] = {153, 153, 255, 128, 204, 204};
// Texture coordinates in 128ths of the sprite: across a texel is 8, down 2 (it is 4 bands tall).
const int S_TEXEL = (1 << HELD_TEX_FRAC) / HELD_SPRITE, T_TEXEL = S_TEXEL / HELD_BANDS;
const int T_BAND = T_TEXEL * HELD_SPRITE;

struct Writer
{
  u8* p;
  u8* end;
  u32 quads;
  // A quad of corners c (texels, y up) with texture coordinates t (128ths).
  bool Quad(const f32 c[4][3], const int t[4][2], int face)
  {
    if (p + 4 * HELD_VERTEX_BYTES > end)
      return false;
    for (int v = 0; v < 4; v++)
    {
      for (int k = 0; k < 3; k++)
        *p++ = static_cast<u8>(c[v][k] * 2.f + 0.5f);
      *p++ = SHADE[face], *p++ = SHADE[face], *p++ = SHADE[face], *p++ = 255;
      *p++ = static_cast<u8>(t[v][0]), *p++ = static_cast<u8>(t[v][1]);
    }
    quads++;
    return true;
  }
};

// A cube's six faces. Each face shows a whole band of the sprite, upright on the sides: BLOCK
// bands 0 (top), 1 (sides) and 2 (bottom), CUBE band 0 everywhere.
bool Cube(Writer& w, bool bands)
{
  const f32 N = static_cast<f32>(HELD_SPRITE);
  // Corners as unit offsets: per face, bottom-left, bottom-right, top-right, top-left seen from
  // outside (top meaning texture row 0 for the sides, the -z edge for the top and the bottom).
  static const f32 C[6][4][3] = {
      {{1, 0, 1}, {1, 0, 0}, {1, 1, 0}, {1, 1, 1}},  // +x
      {{0, 0, 0}, {0, 0, 1}, {0, 1, 1}, {0, 1, 0}},  // -x
      {{0, 1, 1}, {1, 1, 1}, {1, 1, 0}, {0, 1, 0}},  // +y
      {{0, 0, 0}, {1, 0, 0}, {1, 0, 1}, {0, 0, 1}},  // -y
      {{0, 0, 1}, {1, 0, 1}, {1, 1, 1}, {0, 1, 1}},  // +z
      {{1, 0, 0}, {0, 0, 0}, {0, 1, 0}, {1, 1, 0}},  // -z
  };
  const int S = S_TEXEL * HELD_SPRITE;
  for (int f = 0; f < 6; f++)
  {
    const int t0 = bands ? T_BAND * (f == 2 ? 0 : f == 3 ? 2 : 1) : 0;
    f32 c[4][3];
    for (int v = 0; v < 4; v++)
      for (int k = 0; k < 3; k++)
        c[v][k] = C[f][v][k] * N;
    const int t[4][2] = {{0, t0 + T_BAND}, {S, t0 + T_BAND}, {S, t0}, {0, t0}};
    if (!w.Quad(c, t, f))
      return false;
  }
  return true;
}

// Minecraft's flat item (ItemModelGenerator): the sprite on both faces of a slab 7.5..8.5 texels
// deep, and a side one texel wide wherever a solid texel borders a hole or the edge.
bool Flat(Writer& w, const u8* sprite)
{
  const f32 N = static_cast<f32>(HELD_SPRITE), Z0 = 7.5f, Z1 = 8.5f;
  const int W = S_TEXEL * HELD_SPRITE, H = T_BAND;  // band 0 in 128ths
  {
    const f32 front[4][3] = {{0, 0, Z1}, {N, 0, Z1}, {N, N, Z1}, {0, N, Z1}};
    const f32 back[4][3] = {{N, 0, Z0}, {0, 0, Z0}, {0, N, Z0}, {N, N, Z0}};
    const int tf[4][2] = {{0, H}, {W, H}, {W, 0}, {0, 0}};
    const int tb[4][2] = {{W, H}, {0, H}, {0, 0}, {W, 0}};
    if (!w.Quad(front, tf, 4) || !w.Quad(back, tb, 5))
      return false;
  }
  for (int y = 0; y < HELD_SPRITE; y++)
    for (int x = 0; x < HELD_SPRITE; x++)
    {
      if (!SpriteSolid(sprite, x, y))
        continue;
      // The texel's square in model space (row 0 is the top) and its center in 128ths.
      const f32 x0 = static_cast<f32>(x), x1 = x0 + 1.f;
      const f32 y1 = N - static_cast<f32>(y), y0 = y1 - 1.f;
      const int s = S_TEXEL * x + S_TEXEL / 2, t = T_TEXEL * y + T_TEXEL / 2;
      const int tc[4][2] = {{s, t}, {s, t}, {s, t}, {s, t}};
      if (x == HELD_SPRITE - 1 || !SpriteSolid(sprite, x + 1, y))
      {
        const f32 c[4][3] = {{x1, y0, Z1}, {x1, y0, Z0}, {x1, y1, Z0}, {x1, y1, Z1}};
        if (!w.Quad(c, tc, 0))
          return false;
      }
      if (x == 0 || !SpriteSolid(sprite, x - 1, y))
      {
        const f32 c[4][3] = {{x0, y0, Z0}, {x0, y0, Z1}, {x0, y1, Z1}, {x0, y1, Z0}};
        if (!w.Quad(c, tc, 1))
          return false;
      }
      if (y == 0 || !SpriteSolid(sprite, x, y - 1))
      {
        const f32 c[4][3] = {{x0, y1, Z1}, {x1, y1, Z1}, {x1, y1, Z0}, {x0, y1, Z0}};
        if (!w.Quad(c, tc, 2))
          return false;
      }
      if (y == HELD_SPRITE - 1 || !SpriteSolid(sprite, x, y + 1))
      {
        const f32 c[4][3] = {{x0, y0, Z0}, {x1, y0, Z0}, {x1, y0, Z1}, {x0, y0, Z1}};
        if (!w.Quad(c, tc, 3))
          return false;
      }
    }
  return true;
}
}  // namespace

bool SpriteSolid(const u8* sprite, int x, int y)
{
  // RGB5A3 in 4x4 texel blocks, left to right and top to bottom, 2 bytes a texel (big-endian):
  // the top bit set is opaque, else alpha is the next 3 bits.
  const int block = (y / 4) * (HELD_SPRITE / 4) + x / 4;
  const u8* t = sprite + 2 * (16 * block + 4 * (y % 4) + x % 4);
  return (t[0] & 0x80) != 0 || (t[0] & 0x70) != 0;
}

u32 HeldMesh(u32 kind, const u8* sprite, u32 fmt, u8* out, u32 cap)
{
  if (cap < 32 || kind == HELD_NONE || kind > HELD_TOOL)
    return 0;
  Writer w = {out + 3, out + cap, 0};
  const bool ok = kind == HELD_BLOCK ? Cube(w, true) : kind == HELD_CUBE ? Cube(w, false) : Flat(w, sprite);
  if (!ok || w.quads == 0)
    return 0;
  const u32 vertices = 4 * w.quads;
  out[0] = static_cast<u8>(GX_QUADS | fmt);
  out[1] = static_cast<u8>(vertices >> 8), out[2] = static_cast<u8>(vertices);
  u32 size = static_cast<u32>(w.p - out);
  while (size % 32 != 0)
  {
    if (size >= cap)
      return 0;
    out[size++] = 0;  // GX_NOP
  }
  return size;
}
}  // namespace gxc
