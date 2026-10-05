#pragma once
#include <vector>

#include "GuestMemory.h"

namespace gxc
{
// The skin on Mario's model (Steve, tools/steve/build.py): Mario.bdl carries it as one 64x64
// RGB5A3 texture named "steve" in its TEX1 section, which the game draws from the file as loaded
// in guest RAM. A skin from the mod (GXC_MSG_MARIO_SKIN, /skin) is written over it wherever such
// a texture is found.
class MarioSkin
{
public:
  static constexpr u32 SIZE = 64;
  static constexpr u32 BYTES = SIZE * SIZE * 2;
  static constexpr u32 FORMAT_RGB5A3 = 5;

  // A GXC_MSG_MARIO_SKIN payload (u32 id, width, height, texels; big-endian): false, and nothing
  // kept, unless it is a 64x64 one.
  bool Set(const std::vector<u8>& payload);
  bool Has() const { return !m_texels.empty(); }
  // Writes the skin over every texture Find returns; how many there were.
  int Apply(GuestMemory& mem) const;
  // Where the texels of each 64x64 RGB5A3 texture named "steve" are, in every TEX1 section in RAM.
  static std::vector<u32> Find(GuestMemory& mem);

private:
  std::vector<u8> m_texels;
};
}  // namespace gxc
