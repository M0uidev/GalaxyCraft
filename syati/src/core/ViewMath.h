#pragma once
#include "gxc_types.h"

namespace gxc
{
float Sqrt(float x);

// World -> view matrix (3x4 row-major, GX convention: camera looks down -z), equivalent to
// C_MTXLookAt(eye, up, eye + look). Degenerate look/up fall back to other axes, never NaN.
// Where the camera goes: Mario's feet this frame plus the offset Minecraft's camera has from the
// player's feet (eyes in first person, behind or in front in third).
void CameraEye(const float feet[3], const float offset[3], float out[3]);

void LookAtView(const float eye[3], const float look[3], const float up[3], float out[12]);
}  // namespace gxc
