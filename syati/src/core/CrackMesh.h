#pragma once
#include "gxc_types.h"

namespace gxc
{
// Minecraft's cracks over the block being broken (GXC_MSG_CRACK): the six sides of the box its
// corners make (in GxcOutline's order: corner m is (di, dj, dk) = (m & 1, m >> 1 & 1, m >> 2), dk
// outward), each the whole crack tile, upright on the four sides (v0 toward dk = 1).
// Vertex format: position f32 x3, texture coordinates f32 x2, big-endian.
const u32 CRACK_VERTEX_BYTES = 12 + 8;
const u32 CRACK_DL_BYTES = (3 + 6 * 4 * CRACK_VERTEX_BYTES + 31) & ~31u;

// The display list (GX_QUADS of vertex format fmt, padded with GX_NOP) into out.
void CrackMesh(const f32 corners[8][3], const f32 uv[4], u32 fmt, u8 out[CRACK_DL_BYTES]);
}  // namespace gxc
