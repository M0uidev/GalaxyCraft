#pragma once
#include <bit>
#include <cstring>
#include <map>
#include <vector>

#include "GuestMemory.h"

using gxc::u32;
using gxc::u64;
using gxc::u8;

// Two small RAM regions at the Wii's effective addresses, with big-endian helpers.
class FakeGuestMemory final : public gxc::GuestMemory
{
public:
  FakeGuestMemory()
  {
    m_regions[0x80000000u].resize(8u << 20);
    m_regions[0x90000000u].resize(1u << 20);
  }

  bool Read(u32 addr, void* dst, u32 n) override
  {
    u8* p = Ptr(addr, n);
    if (!p)
      return false;
    std::memcpy(dst, p, n);
    return true;
  }

  bool Write(u32 addr, const void* src, u32 n) override
  {
    u8* p = Ptr(addr, n);
    if (!p)
      return false;
    std::memcpy(p, src, n);
    writes.push_back(addr);
    return true;
  }

  std::vector<std::pair<u32, u32>> Regions() override
  {
    std::vector<std::pair<u32, u32>> out;
    for (auto& [base, mem] : m_regions)
      out.emplace_back(base, static_cast<u32>(mem.size()));
    return out;
  }

  void PutU32(u32 addr, u32 v)
  {
    v = __builtin_bswap32(v);
    std::memcpy(Ptr(addr, 4), &v, 4);
  }
  void PutF32(u32 addr, float f) { PutU32(addr, std::bit_cast<u32>(f)); }
  u32 GetU32(u32 addr)
  {
    u32 v;
    std::memcpy(&v, Ptr(addr, 4), 4);
    return __builtin_bswap32(v);
  }
  float GetF32(u32 addr) { return std::bit_cast<float>(GetU32(addr)); }
  void PutBytes(u32 addr, const void* src, u32 n) { std::memcpy(Ptr(addr, n), src, n); }

  std::vector<u32> writes;

private:
  u8* Ptr(u32 addr, u32 n)
  {
    for (auto& [base, mem] : m_regions)
      if (addr >= base && static_cast<u64>(addr) - base + n <= mem.size())
        return mem.data() + (addr - base);
    return nullptr;
  }
  std::map<u32, std::vector<u8>> m_regions;
};
