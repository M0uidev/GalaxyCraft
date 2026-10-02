#pragma once
#include "gxc_types.h"

namespace gxc
{
// Bytes of a KCL loaded at base that a reader needs: up to the octree. The header's four
// offsets (positions, normals, prisms, octree) are absolute pointers once
// KCollisionServer::setData ran, plain offsets before. Returns 0 if the header is incoherent.
u32 KclSizeFromHeader(const u32 header[4], u32 base);
}  // namespace gxc
