#include "CrackMesh.h"

namespace gxc
{
namespace
{
const u8 GX_QUADS = 0x80;

u8* PutF32(u8* p, f32 f)
{
  union
  {
    f32 f;
    u32 u;
  } v;
  v.f = f;
  p[0] = static_cast<u8>(v.u >> 24), p[1] = static_cast<u8>(v.u >> 16);
  p[2] = static_cast<u8>(v.u >> 8), p[3] = static_cast<u8>(v.u);
  return p + 4;
}
}  // namespace

void CrackMesh(const f32 corners[8][3], const f32 uv[4], u32 fmt, u8 out[CRACK_DL_BYTES])
{
  for (u32 i = 0; i < CRACK_DL_BYTES; i++)
    out[i] = 0;  // the padding: GX_NOP
  out[0] = static_cast<u8>(GX_QUADS | fmt);
  out[1] = 0, out[2] = 6 * 4;
  u8* p = out + 3;
  // A side for each bit and its two values; a and b are the other two bits, b the higher (dk, up,
  // on the four sides): its corners go round base, a, a + b, b.
  for (int bit = 1; bit < 8; bit <<= 1)
  {
    const int a = bit == 1 ? 2 : 1, b = bit == 4 ? 2 : 4;
    for (int side = 0; side < 2; side++)
    {
      const int base = side ? bit : 0;
      const int m[4] = {base, base | a, base | a | b, base | b};
      const f32 st[4][2] = {{uv[0], uv[3]}, {uv[2], uv[3]}, {uv[2], uv[1]}, {uv[0], uv[1]}};
      for (int v = 0; v < 4; v++)
      {
        for (int k = 0; k < 3; k++)
          p = PutF32(p, corners[m[v]][k]);
        p = PutF32(p, st[v][0]);
        p = PutF32(p, st[v][1]);
      }
    }
  }
}
}  // namespace gxc
