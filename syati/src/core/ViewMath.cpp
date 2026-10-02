#include "ViewMath.h"

namespace gxc
{
namespace
{
float Dot(const float a[3], const float b[3])
{
  return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
}

void Cross(const float a[3], const float b[3], float out[3])
{
  out[0] = a[1] * b[2] - a[2] * b[1];
  out[1] = a[2] * b[0] - a[0] * b[2];
  out[2] = a[0] * b[1] - a[1] * b[0];
}

// Normalizes v into out; returns false (out untouched) if v is too short to have a direction.
bool Normalize(const float v[3], float out[3])
{
  const float len2 = Dot(v, v);
  if (!(len2 > 1e-12f))
    return false;
  const float inv = 1.f / Sqrt(len2);
  out[0] = v[0] * inv, out[1] = v[1] * inv, out[2] = v[2] * inv;
  return true;
}
}  // namespace

float Sqrt(float x)
{
  // Gekko has no fsqrt and -nodefaults leaves no libm: inverse square root by bit trick plus
  // Newton steps, which reaches float precision after three.
  if (!(x > 0.f))
    return 0.f;
  union
  {
    float f;
    u32 i;
  } u;
  u.f = x;
  u.i = 0x5F3759DFu - (u.i >> 1);
  float y = u.f;
  for (int k = 0; k < 3; k++)
    y = y * (1.5f - 0.5f * x * y * y);
  return x * y;
}

void LookAtView(const float eye[3], const float look[3], const float up[3], float out[12])
{
  // Rows are right, up', z = -look (GX cameras look down -z); translation is -R * eye.
  float z[3] = {0.f, 0.f, 1.f};
  const float back[3] = {-look[0], -look[1], -look[2]};
  Normalize(back, z);

  float x[3];
  float side[3];
  Cross(up, z, side);
  if (!Normalize(side, x))
  {
    // up is null or parallel to look: use the world axis least aligned with z.
    float axis[3] = {0.f, 0.f, 0.f};
    int best = 0;
    for (int k = 1; k < 3; k++)
      if ((z[k] < 0 ? -z[k] : z[k]) < (z[best] < 0 ? -z[best] : z[best]))
        best = k;
    axis[best] = 1.f;
    Cross(axis, z, side);
    Normalize(side, x);
  }
  float y[3];
  Cross(z, x, y);

  const float* rows[3] = {x, y, z};
  for (int r = 0; r < 3; r++)
  {
    out[4 * r] = rows[r][0];
    out[4 * r + 1] = rows[r][1];
    out[4 * r + 2] = rows[r][2];
    out[4 * r + 3] = -Dot(rows[r], eye);
  }
}
}  // namespace gxc
