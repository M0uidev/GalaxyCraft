#include "Shell.h"

namespace gxc
{
namespace
{
const u8 GX_LINES = 0xA8;

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

u8* Put(u8* p, f32 x, f32 y, f32 z)
{
  return PutF32(PutF32(PutF32(p, x), y), z);
}

u8* Start(u8* out, u32 fmt, u32 verts)
{
  out[0] = static_cast<u8>(GX_LINES | fmt);
  out[1] = static_cast<u8>(verts >> 8), out[2] = static_cast<u8>(verts);
  return out + 3;
}

void Pad(u8* from, u8* end)
{
  while (from < end)
    *from++ = 0;  // GX_NOP
}

// The unit circle at SHELL_SEGMENTS + 1 points (the last the first again), turning by the
// segment's angle (cos and sin of 2 pi / 32): no sin/cos in the game's code.
void Circle(f32 c[SHELL_SEGMENTS + 1], f32 s[SHELL_SEGMENTS + 1])
{
  const f32 dc = 0.98078528f, ds = 0.19509032f;
  c[0] = 1.f, s[0] = 0.f;
  for (u32 i = 1; i <= SHELL_SEGMENTS; i++)
  {
    c[i] = c[i - 1] * dc - s[i - 1] * ds;
    s[i] = s[i - 1] * dc + c[i - 1] * ds;
  }
  c[SHELL_SEGMENTS] = 1.f, s[SHELL_SEGMENTS] = 0.f;
}
}  // namespace

void ShellSphereList(u32 fmt, u8 out[SHELL_SPHERE_DL_BYTES])
{
  f32 c[SHELL_SEGMENTS + 1], s[SHELL_SEGMENTS + 1];
  Circle(c, s);
  u8* p = Start(out, fmt, SHELL_SPHERE_VERTS);
  // Meridians: great circles through both poles, every 180/8 degrees around.
  for (u32 m = 0; m < SHELL_MERIDIANS; m++)
  {
    const u32 a = m * SHELL_SEGMENTS / (2 * SHELL_MERIDIANS);
    for (u32 i = 0; i < SHELL_SEGMENTS; i++)
    {
      p = Put(p, s[i] * c[a], c[i], s[i] * s[a]);
      p = Put(p, s[i + 1] * c[a], c[i + 1], s[i + 1] * s[a]);
    }
  }
  // Parallels at 30, 60, 90, 120 and 150 degrees from the top.
  static const f32 Y[SHELL_PARALLELS] = {0.8660254f, 0.5f, 0.f, -0.5f, -0.8660254f};
  static const f32 R[SHELL_PARALLELS] = {0.5f, 0.8660254f, 1.f, 0.8660254f, 0.5f};
  for (u32 k = 0; k < SHELL_PARALLELS; k++)
    for (u32 i = 0; i < SHELL_SEGMENTS; i++)
    {
      p = Put(p, R[k] * c[i], Y[k], R[k] * s[i]);
      p = Put(p, R[k] * c[i + 1], Y[k], R[k] * s[i + 1]);
    }
  Pad(p, out + SHELL_SPHERE_DL_BYTES);
}

void ShellBoxList(u32 fmt, u8 out[SHELL_BOX_DL_BYTES])
{
  u8* p = Start(out, fmt, SHELL_BOX_VERTS);
  // Lines along axis a, at (u, v) on the other two (b, c).
  for (int a = 0; a < 3; a++)
  {
    const int b = (a + 1) % 3, c = (a + 2) % 3;
    // The 4 edges along a.
    for (int e = 0; e < 4; e++)
    {
      f32 from[3], to[3];
      from[a] = -1.f, to[a] = 1.f;
      from[b] = to[b] = (e & 1) ? 1.f : -1.f;
      from[c] = to[c] = (e & 2) ? 1.f : -1.f;
      p = Put(Put(p, from[0], from[1], from[2]), to[0], to[1], to[2]);
    }
    // Across the two sides facing b and the two facing c: 2 lines each along a, at a third and
    // two thirds of the way.
    for (int side = 0; side < 4; side++)
      for (int t = 0; t < 2; t++)
      {
        const int n = side < 2 ? b : c, m = side < 2 ? c : b;
        const f32 at = t ? 1.f / 3.f : -1.f / 3.f;
        f32 from[3], to[3];
        from[a] = -1.f, to[a] = 1.f;
        from[n] = to[n] = (side & 1) ? 1.f : -1.f;
        from[m] = to[m] = at;
        p = Put(Put(p, from[0], from[1], from[2]), to[0], to[1], to[2]);
      }
  }
  Pad(p, out + SHELL_BOX_DL_BYTES);
}

f32 ShellShown(f32 outside, f32 fade, bool in_any)
{
  if (in_any && outside >= 0.f)
    return 0.f;
  return ShellAlpha(outside, fade);
}

f32 ShellAlpha(f32 outside, f32 fade)
{
  if (outside >= 0.f)
    return 1.f;
  if (fade <= 0.f || outside <= -fade)
    return 0.f;
  return 1.f + outside / fade;
}
}  // namespace gxc
