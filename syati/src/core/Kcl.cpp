#include "Kcl.h"

namespace gxc
{
u32 KclSizeFromHeader(const u32 header[4], u32 base)
{
  enum
  {
    HEADER_SIZE = 0x38,
    MAX_SIZE = 0x1000000,  // 16 MiB, far beyond any stage collision
  };
  const bool pointers = header[0] >= base;
  u32 off[4];
  for (int k = 0; k < 4; k++)
  {
    if ((header[k] >= base) != pointers)
      return 0;  // half converted: not something setData leaves behind
    off[k] = pointers ? header[k] - base : header[k];
  }
  // positions, normals, prisms (1-based: the offset is 0x10 before the first one), octree.
  if (off[0] < HEADER_SIZE || off[1] < off[0] || off[2] + 0x10 < off[1] || off[3] < off[2] + 0x10 ||
      off[3] > MAX_SIZE)
    return 0;
  return off[3];
}
}  // namespace gxc
