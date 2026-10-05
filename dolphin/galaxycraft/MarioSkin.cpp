#include "MarioSkin.h"

#include <algorithm>
#include <cstring>

namespace gxc
{
namespace
{
constexpr char TEXTURE_NAME[] = "steve";
// J3D's TEX1: "TEX1", u32 size, u16 count, u16 pad, u32 headers, u32 names (offsets from the
// section). Each header is a 32-byte BTI one: u8 format, u8, u16 width, u16 height, ..., u32
// image at 0x1C (from that header). The names: u16 count, u16 pad, {u16 hash, u16 offset} each.
constexpr u32 TEX_HEADER = 0x20;
constexpr u32 MAX_SECTION = 16u << 20;

u32 BE32(const u8* p)
{
  return static_cast<u32>(p[0]) << 24 | static_cast<u32>(p[1]) << 16 | static_cast<u32>(p[2]) << 8 | p[3];
}
u32 BE16(const u8* p)
{
  return static_cast<u32>(p[0]) << 8 | p[1];
}

// The "steve" textures' texels in the TEX1 section at sec, if it is one.
void Parse(GuestMemory& mem, u32 sec, std::vector<u32>& out)
{
  u8 h[0x14];
  if (!mem.Read(sec, h, sizeof(h)))
    return;
  const u32 size = BE32(h + 4), count = BE16(h + 8), headers = BE32(h + 0xC), names = BE32(h + 0x10);
  if (size > MAX_SECTION || count == 0 || count > 256 || headers + count * TEX_HEADER > size ||
      names + 4 + count * 4 > size)
    return;
  u8 n[4];
  if (!mem.Read(sec + names, n, 4) || BE16(n) != count)
    return;
  for (u32 i = 0; i < count; i++)
  {
    u8 entry[4], name[sizeof(TEXTURE_NAME)], tex[TEX_HEADER];
    if (!mem.Read(sec + names + 4 + i * 4, entry, 4) || names + BE16(entry + 2) + sizeof(name) > size ||
        !mem.Read(sec + names + BE16(entry + 2), name, sizeof(name)) ||
        std::memcmp(name, TEXTURE_NAME, sizeof(name)) != 0)
      continue;
    const u32 at = headers + i * TEX_HEADER;
    if (!mem.Read(sec + at, tex, sizeof(tex)) || tex[0] != MarioSkin::FORMAT_RGB5A3 ||
        BE16(tex + 2) != MarioSkin::SIZE || BE16(tex + 4) != MarioSkin::SIZE)
      continue;
    const u32 image = at + BE32(tex + 0x1C);
    if (image >= at && image + MarioSkin::BYTES <= size)
      out.push_back(sec + image);
  }
}
}  // namespace

bool MarioSkin::Set(const std::vector<u8>& payload)
{
  if (payload.size() != 12 + BYTES || BE32(payload.data() + 4) != SIZE || BE32(payload.data() + 8) != SIZE)
    return false;
  m_texels.assign(payload.begin() + 12, payload.end());
  return true;
}

int MarioSkin::Apply(GuestMemory& mem) const
{
  if (m_texels.empty())
    return 0;
  int written = 0;
  for (u32 at : Find(mem))
    written += mem.Write(at, m_texels.data(), BYTES) ? 1 : 0;
  return written;
}

std::vector<u32> MarioSkin::Find(GuestMemory& mem)
{
  std::vector<u32> out;
  constexpr u32 CHUNK = 1u << 20;
  std::vector<u8> buf(CHUNK + 4);
  for (auto [base, size] : mem.Regions())
  {
    for (u32 off = 0; off < size; off += CHUNK)
    {
      const u32 n = std::min<u32>(CHUNK + 4, size - off);
      if (!mem.Read(base + off, buf.data(), n))
        continue;
      // J3D sections are 32-byte aligned in the file; RAM is only trusted to keep 4.
      for (u32 i = 0; i + 4 <= n && i < CHUNK; i += 4)
        if (std::memcmp(buf.data() + i, "TEX1", 4) == 0)
          Parse(mem, base + off + i, out);
    }
  }
  return out;
}
}  // namespace gxc
