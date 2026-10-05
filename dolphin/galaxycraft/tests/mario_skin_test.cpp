#include <array>
#include <cstring>
#include <vector>

#include "FakeGuestMemory.h"
#include "MarioSkin.h"
#include "TestRunner.h"

using namespace gxc;

namespace
{
// A TEX1 section at sec with the given textures (name, format, size), images after the names.
void PutTex1(FakeGuestMemory& mem, u32 sec, const std::vector<std::pair<const char*, std::array<u32, 2>>>& texs)
{
  const u32 count = static_cast<u32>(texs.size());
  const u32 headers = 0x20, names = headers + 0x20 * count;
  u32 strings = names + 4 + 4 * count;
  u32 image = (strings + 16 * count + 31) & ~31u;
  const u32 size = image + count * MarioSkin::BYTES;
  const u8 magic[] = {'T', 'E', 'X', '1'};
  mem.PutBytes(sec, magic, 4);
  mem.PutU32(sec + 4, size);
  mem.PutU32(sec + 8, count << 16 | 0xFFFF);
  mem.PutU32(sec + 0xC, headers);
  mem.PutU32(sec + 0x10, names);
  mem.PutU32(sec + names, count << 16 | 0xFFFF);
  for (u32 i = 0; i < count; i++)
  {
    const u32 h = sec + headers + 0x20 * i;
    mem.PutU32(h, texs[i].second[0] << 24 | 64);  // format, alpha 0, width 64
    mem.PutU32(h + 4, texs[i].second[1] << 16);   // height
    mem.PutU32(h + 0x1C, image + i * MarioSkin::BYTES - (headers + 0x20 * i));
    const u32 at = strings + 16 * i;
    mem.PutU32(sec + names + 4 + 4 * i, at - names);
    mem.PutBytes(sec + at, texs[i].first, static_cast<u32>(std::strlen(texs[i].first) + 1));
  }
}

std::vector<u8> Payload(u32 w, u32 h, u8 fill)
{
  std::vector<u8> p(12 + w * h * 2, fill);
  for (int k = 0; k < 12; k++)
    p[k] = 0;
  p[7] = static_cast<u8>(w), p[11] = static_cast<u8>(h);
  return p;
}
}  // namespace

TEST(mario_skin_takes_only_64x64)
{
  MarioSkin s;
  CHECK(!s.Set(Payload(32, 32, 1)) && !s.Has());
  CHECK(!s.Set({1, 2, 3}) && !s.Has());
  CHECK(s.Set(Payload(64, 64, 1)) && s.Has());
}

TEST(mario_skin_finds_steve_among_other_textures)
{
  FakeGuestMemory mem;
  constexpr u32 SEC = 0x80100004u;  // only 4-byte aligned
  PutTex1(mem, SEC, {{"eye", {MarioSkin::FORMAT_RGB5A3, 64}}, {"steve", {MarioSkin::FORMAT_RGB5A3, 64}},
                     {"stevex", {MarioSkin::FORMAT_RGB5A3, 64}}});
  // Another model's "steve" in another format, and one too short: left alone.
  PutTex1(mem, 0x90000000u, {{"steve", {14, 64}}, {"steve", {MarioSkin::FORMAT_RGB5A3, 32}}});
  const std::vector<u32> found = MarioSkin::Find(mem);
  CHECK(found.size() == 1);
  MarioSkin s;
  CHECK(s.Set(Payload(64, 64, 0x5A)));
  CHECK(s.Apply(mem) == 1);
  CHECK(mem.GetU32(found[0]) == 0x5A5A5A5Au);
  CHECK(mem.GetU32(found[0] - 4) == 0);                        // the first texture's image
  CHECK(mem.GetU32(found[0] + MarioSkin::BYTES) == 0);         // the third's
}

TEST(mario_skin_ignores_bad_sections)
{
  FakeGuestMemory mem;
  // "TEX1" with offsets past its own size.
  const u8 bad[] = {'T', 'E', 'X', '1', 0, 0, 0, 0x40, 0, 1, 0xFF, 0xFF, 0, 0, 0x10, 0, 0, 0, 0x10, 0};
  mem.PutBytes(0x80200000u, bad, sizeof(bad));
  CHECK(MarioSkin::Find(mem).empty());
  MarioSkin s;
  CHECK(s.Apply(mem) == 0);  // no skin yet
}
