#pragma once
#include <cstdint>
#include <utility>
#include <vector>

namespace gxc
{
using u8 = std::uint8_t;
using u16 = std::uint16_t;
using u32 = std::uint32_t;
using u64 = std::uint64_t;

// Emulated Wii RAM as seen by effective addresses (0x80000000 MEM1, 0x90000000 MEM2).
// Implementations must refuse (return false) any access outside Regions().
class GuestMemory
{
public:
  virtual ~GuestMemory() = default;
  virtual bool Read(u32 addr, void* dst, u32 n) = 0;
  virtual bool Write(u32 addr, const void* src, u32 n) = 0;
  // (base address, size) of each readable region.
  virtual std::vector<std::pair<u32, u32>> Regions() = 0;
};
}  // namespace gxc
