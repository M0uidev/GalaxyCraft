#pragma once
#include "gxc_types.h"

namespace gxc
{
// Where a body's gravity begins, drawn as faint lines: a unit sphere (8 meridians and 5
// parallels, 32 segments each) for a planet, scaled to its gravity's range, and a unit cube
// ([-1, 1]: its 12 edges and 4 lines across each side) for a station, by its gravity box's
// matrix. Display lists of GX_LINES (vertex format fmt: f32 positions, big-endian), padded with
// GX_NOP.
const u32 SHELL_MERIDIANS = 8, SHELL_PARALLELS = 5, SHELL_SEGMENTS = 32;
const u32 SHELL_SPHERE_VERTS = (SHELL_MERIDIANS + SHELL_PARALLELS) * SHELL_SEGMENTS * 2;
const u32 SHELL_BOX_VERTS = (12 + 6 * 4) * 2;
const u32 SHELL_SPHERE_DL_BYTES = (3 + SHELL_SPHERE_VERTS * 12 + 31) & ~31u;
const u32 SHELL_BOX_DL_BYTES = (3 + SHELL_BOX_VERTS * 12 + 31) & ~31u;
void ShellSphereList(u32 fmt, u8 out[SHELL_SPHERE_DL_BYTES]);
void ShellBoxList(u32 fmt, u8 out[SHELL_BOX_DL_BYTES]);

// How much of the shell shows (0..1) for a camera `outside` units past the gravity's edge
// (negative: inside it): all of it from outside, fading out over `fade` units inside, so on a
// planet's ground (deeper in than that) nothing shows.
f32 ShellAlpha(f32 outside, f32 fade);

// A body's shell shows how much (0..1), the camera `outside` units past its gravity's edge, and
// `in_any`: the camera is inside some body's gravity. Out in space every shell shows; inside a
// gravity only that body's own (fading as above), not the others' around it.
f32 ShellShown(f32 outside, f32 fade, bool in_any);
}  // namespace gxc
