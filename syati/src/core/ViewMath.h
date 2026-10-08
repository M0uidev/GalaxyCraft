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

// Whether Mario's model (Steve) is drawn: everywhere but first person, where the camera is in
// his eyes; cutscenes always show him.
bool MarioVisible(bool following, bool demo, bool third_person);

void LookAtView(const float eye[3], const float look[3], const float up[3], float out[12]);

// Minecraft's jump on Mario: the most he may still rise this frame, units/frame, once `rose`
// units above where he left the ground, to peak at `height` while slowing by `gravity` a frame
// (frame by frame, not the continuous sqrt(2 g h)); 0 at or past the top.
float JumpCeiling(float rose, float height, float gravity);

// Minecraft's sneak at an edge, for Mario on the ground at `from` who moved to `to`: what of the
// move keeps ground under his feet (a square of half-width `half` around them, as Minecraft's
// player box): all of it, else its part along front, else along the side (sliding along the
// edge), else none; his move along up (a step, a slope) always stays. ground(p, ctx): ground lies
// within a step below p. Changes `to` in place; true if it did.
typedef bool (*GroundAt)(const float p[3], void* ctx);
bool SneakStep(const float from[3], float to[3], const float up[3], const float front[3], float half, GroundAt ground,
               void* ctx);

// a times b, both 3x4 row-major affine matrices (the bottom row 0 0 0 1 implied).
void Mul34(const float a[12], const float b[12], float out[12]);
}  // namespace gxc
