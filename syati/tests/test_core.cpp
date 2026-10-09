// g++ tests for syati/src/core: the module's game-independent logic.
#include <cmath>
#include <cstdio>

#include <cstring>
#include <vector>

#include "CodePatch.h"
#include "CrackMesh.h"
#include "Graves.h"
#include "AtlasAnim.h"
#include "HeldMesh.h"
#include "Inbox.h"
#include "Kcl.h"
#include "Parts.h"
#include "Shell.h"
#include "ViewMath.h"

using namespace gxc;

static int g_failures = 0;
static int g_checks = 0;
#define CHECK(cond)                                                                                \
  do                                                                                               \
  {                                                                                                \
    ++g_checks;                                                                                    \
    if (!(cond))                                                                                   \
    {                                                                                              \
      std::printf("  FAILED %s:%d: %s\n", __FILE__, __LINE__, #cond);                              \
      ++g_failures;                                                                                \
    }                                                                                              \
  } while (0)

static bool Near(float a, float b, float eps = 1e-4f)
{
  return std::fabs(a - b) <= eps;
}

static PartCandidate At(u32 id, float x, float y, float z, float r)
{
  PartCandidate p = {id, 0x80500000u + id * 0x100u, 0x100u, {1, 0, 0, x, 0, 1, 0, y, 0, 0, 1, z}, r};
  return p;
}

static void TestJumpCeilingPeaksAtTheHeight()
{
  // Launched at the ceiling and slowing by g, a jump tops out at the height, never above.
  const float g = 2.1f, h = 100.f;
  float rose = 0, v = 30.f, top = 0;
  for (int f = 0; f < 60; f++)
  {
    const float cap = gxc::JumpCeiling(rose, h, g);
    if (v > cap)
      v = cap;
    rose += v;
    top = rose > top ? rose : top;
    v -= g;
  }
  CHECK(top <= h + 0.01f);
  CHECK(top > h - 2.5f);  // the last frame's step short of it, at most
  CHECK(gxc::JumpCeiling(100.f, h, g) == 0.f && gxc::JumpCeiling(150.f, h, g) == 0.f);
}

static void TestSelectNearest64()
{
  PartCandidate c[70];
  for (int i = 0; i < 70; i++)  // id i at distance 70 - i: the last 64 win, nearest first
    c[i] = At(static_cast<u32>(i), 0, 0, static_cast<float>(70 - i), 0);
  const float pos[3] = {0, 0, 0};
  int out[64];
  const int n = SelectParts(c, 70, pos, 3000, out, 64);
  CHECK(n == 64);
  for (int k = 0; k < n; k++)
    CHECK(out[k] == 69 - k);
}

static void TestBigRadiusWinsOrder()
{
  // Centre 1000 away with radius 950 (surface at 50) beats centre 100 away with radius 0.
  PartCandidate c[2] = {At(1, 100, 0, 0, 0), At(2, 1000, 0, 0, 950)};
  const float pos[3] = {0, 0, 0};
  int out[64];
  CHECK(SelectParts(c, 2, pos, 3000, out, 64) == 2);
  CHECK(out[0] == 1 && out[1] == 0);
}

static void TestNoneInRange()
{
  PartCandidate c[3] = {At(1, 5000, 0, 0, 100), At(2, 0, -9000, 0, 10), At(3, 0, 0, 3001, 0)};
  const float pos[3] = {0, 0, 0};
  int out[64];
  CHECK(SelectParts(c, 3, pos, 3000, out, 64) == 0);
  CHECK(SelectParts(c, 0, pos, 3000, out, 64) == 0);
}

static void TestMaxOutCapped()
{
  PartCandidate c[3] = {At(1, 30, 0, 0, 0), At(2, 10, 0, 0, 0), At(3, 20, 0, 0, 0)};
  const float pos[3] = {0, 0, 0};
  int out[2];
  CHECK(SelectParts(c, 3, pos, 3000, out, 2) == 2);
  CHECK(out[0] == 1 && out[1] == 2);
}

static void Apply(const float m[12], const float p[3], float out[3])
{
  for (int r = 0; r < 3; r++)
    out[r] = m[4 * r] * p[0] + m[4 * r + 1] * p[1] + m[4 * r + 2] * p[2] + m[4 * r + 3];
}

static void TestViewIdentity()
{
  const float eye[3] = {0, 0, 0}, look[3] = {0, 0, -1}, up[3] = {0, 1, 0};
  float m[12];
  LookAtView(eye, look, up, m);
  const float id[12] = {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0};
  for (int k = 0; k < 12; k++)
    CHECK(Near(m[k], id[k]));
}

static void TestViewTarget()
{
  // Eye somewhere, looking along +x with z up: eye + look lands at (0, 0, -1) in view space,
  // eye + up at (0, 1, 0).
  const float eye[3] = {10, -20, 30}, look[3] = {2, 0, 0}, up[3] = {0, 0, 1};
  float m[12];
  LookAtView(eye, look, up, m);
  const float target[3] = {11, -20, 30}, above[3] = {10, -20, 31};
  float v[3];
  Apply(m, target, v);
  CHECK(Near(v[0], 0) && Near(v[1], 0) && Near(v[2], -1));
  Apply(m, above, v);
  CHECK(Near(v[0], 0) && Near(v[1], 1) && Near(v[2], 0));
  Apply(m, eye, v);
  CHECK(Near(v[0], 0) && Near(v[1], 0) && Near(v[2], 0));
}

static void CheckOrthonormal(const float m[12])
{
  for (int k = 0; k < 12; k++)
    CHECK(m[k] == m[k]);  // no NaN
  for (int a = 0; a < 3; a++)
  {
    for (int b = 0; b < 3; b++)
    {
      const float d = m[4 * a] * m[4 * b] + m[4 * a + 1] * m[4 * b + 1] + m[4 * a + 2] * m[4 * b + 2];
      CHECK(Near(d, a == b ? 1.f : 0.f));
    }
  }
}

static void TestViewDegenerate()
{
  const float eye[3] = {1, 2, 3};
  const float up_y[3] = {0, 1, 0}, look_y[3] = {0, 5, 0}, zero[3] = {0, 0, 0};
  float m[12];
  LookAtView(eye, look_y, up_y, m);  // look parallel to up
  CheckOrthonormal(m);
  CHECK(Near(m[8], 0) && Near(m[9], -1) && Near(m[10], 0));  // still looks along +y
  LookAtView(eye, look_y, zero, m);  // null up
  CheckOrthonormal(m);
  CHECK(Near(m[9], -1));
  LookAtView(eye, zero, up_y, m);  // null look
  CheckOrthonormal(m);
  LookAtView(eye, zero, zero, m);
  CheckOrthonormal(m);
}

static void TestSqrt()
{
  const float xs[] = {0.f, 1e-6f, 0.25f, 1.f, 2.f, 9.f, 12345.f, 3e9f};
  for (float x : xs)
    CHECK(Near(Sqrt(x), std::sqrt(x), std::sqrt(x) * 1e-5f + 1e-7f));
  CHECK(Sqrt(-4.f) == 0.f);
}

static void TestCameraEye()
{
  // Third person: the camera sits wherever Minecraft put it, relative to Mario's feet now.
  const float feet[3] = {100, -20, 5}, offset[3] = {0, 130, -320};
  float eye[3];
  CameraEye(feet, offset, eye);
  CHECK(Near(eye[0], 100) && Near(eye[1], 110) && Near(eye[2], -315));
}

static void TestMarioVisible()
{
  CHECK(MarioVisible(false, false, false));  // the game's own play (Wii Remote mode)
  CHECK(!MarioVisible(true, false, false));  // first person
  CHECK(MarioVisible(true, false, true));    // third person and the Galaxy view
  CHECK(MarioVisible(true, true, false));    // cutscene
}

static void TestKclSize()
{
  const u32 base = 0x80600000u;
  const u32 offsets[4] = {0x3C, 0x9C, 0xF0, 0x200};
  const u32 pointers[4] = {base + 0x3C, base + 0x9C, base + 0xF0, base + 0x200};
  CHECK(KclSizeFromHeader(offsets, base) == 0x200);
  CHECK(KclSizeFromHeader(pointers, base) == 0x200);
  const u32 mixed[4] = {0x3C, base + 0x9C, 0xF0, base + 0x200};
  CHECK(KclSizeFromHeader(mixed, base) == 0);
  const u32 backwards[4] = {0x9C, 0x3C, 0xF0, 0x200};
  CHECK(KclSizeFromHeader(backwards, base) == 0);
  const u32 zero[4] = {0, 0, 0, 0};
  CHECK(KclSizeFromHeader(zero, base) == 0);
  const u32 elsewhere[4] = {0x80001000u, 0x80001100u, 0x80001200u, 0x80001300u};  // below base
  CHECK(KclSizeFromHeader(elsewhere, base) == 0);
  const u32 huge[4] = {0x3C, 0x9C, 0xF0, 0x7000000u};
  CHECK(KclSizeFromHeader(huge, base) == 0);
}

static void Put32(std::vector<u8>& b, u32 v)
{
  for (int k = 3; k >= 0; k--)
    b.push_back(static_cast<u8>(v >> (8 * k)));
}

static void PutF(std::vector<u8>& b, float f)
{
  u32 v;
  std::memcpy(&v, &f, 4);
  Put32(b, v);
}

static void TestInboxRecords()
{
  std::vector<u8> b;
  Put32(b, 102u << 16), Put32(b, 36), Put32(b, 7), PutF(b, 1.f), PutF(b, 2.f), PutF(b, 3.f), PutF(b, 1280.f),
      PutF(b, 4480.f), Put32(b, 162), PutF(b, 640.f), PutF(b, 28.f);
  Put32(b, 103u << 16), Put32(b, 32 + 32 + 8), Put32(b, 5), Put32(b, 2), Put32(b, 32), Put32(b, 8);
  PutF(b, 10.f), PutF(b, 20.f), PutF(b, 30.f), PutF(b, 99.f);
  for (int k = 0; k < 40; k++)
    b.push_back(static_cast<u8>(k));
  Put32(b, 104u << 16), Put32(b, 0);
  Put32(b, 104u << 16), Put32(b, 4), PutF(b, 1500.f);
  InboxRecord r;
  u32 off = 0;
  CHECK(NextInboxRecord(b.data(), b.size(), &off, 512, &r) && r.type == InboxRecord::PLANET);
  CHECK(r.planet.id == 7 && r.planet.center[2] == 3.f && r.planet.surface == 1280.f && r.planet.gravity_range == 4480.f &&
        r.planet.chunk_count == 162 && r.planet.occluder == 640.f &&
        r.planet.mario_radius == 28.f && r.planet.flags == 0);
  CHECK(NextInboxRecord(b.data(), b.size(), &off, 512, &r) && r.type == InboxRecord::CHUNK);
  CHECK(r.chunk.slot == 5 && r.chunk.planet == 0 && !r.chunk.far && r.chunk.version == 2 && r.chunk.dl[0] == 0 && r.chunk.kcl[0] == 32 &&
        r.chunk.sphere[1] == 20.f && r.chunk.sphere[3] == 99.f);
  CHECK(NextInboxRecord(b.data(), b.size(), &off, 512, &r) && r.type == InboxRecord::TELEPORT && r.teleport.ground == 0.f);
  CHECK(NextInboxRecord(b.data(), b.size(), &off, 512, &r) && r.type == InboxRecord::TELEPORT && r.teleport.ground == 1500.f &&
        r.teleport.planet == 0);
  CHECK(!NextInboxRecord(b.data(), b.size(), &off, 512, &r));
  CHECK(off == b.size());
  std::vector<u8> sky;  // the sky's light at night
  Put32(sky, 113u << 16), Put32(sky, 12), PutF(sky, 0.25f), PutF(sky, 0.3f), PutF(sky, 0.5f);
  off = 0;
  CHECK(NextInboxRecord(sky.data(), sky.size(), &off, 512, &r) && r.type == InboxRecord::SKY && r.sky[2] == 0.5f);
  std::vector<u8> a;  // aimed: where to land
  Put32(a, 104u << 16), Put32(a, 20), PutF(a, 1500.f), Put32(a, 3), PutF(a, 0.f), PutF(a, 1.f), PutF(a, 0.f);
  off = 0;
  CHECK(NextInboxRecord(a.data(), a.size(), &off, 512, &r) && r.type == InboxRecord::TELEPORT && r.teleport.aimed &&
        r.teleport.planet == 3 && r.teleport.dir[1] == 1.f && r.teleport.ground == 1500.f);
}

static void TestInboxRejectsBadChunks()
{
  InboxRecord r;
  std::vector<u8> b;
  Put32(b, 103u << 16), Put32(b, 32 + 40), Put32(b, 600), Put32(b, 1), Put32(b, 32), Put32(b, 8);
  b.resize(b.size() + 16 + 40);
  u32 off = 0;
  CHECK(!NextInboxRecord(b.data(), b.size(), &off, 512, &r));  // slot out of range
  CHECK(off == 0);
  std::vector<u8> c;
  Put32(c, 103u << 16), Put32(c, 32 + 40), Put32(c, 1), Put32(c, 1), Put32(c, 33), Put32(c, 7);
  c.resize(c.size() + 16 + 40);
  CHECK(!NextInboxRecord(c.data(), c.size(), &off, 512, &r));  // display list not 32-aligned
  std::vector<u8> d;
  Put32(d, 103u << 16), Put32(d, 32), Put32(d, 1), Put32(d, 1), Put32(d, 0), Put32(d, 0);
  d.resize(d.size() + 16);
  CHECK(NextInboxRecord(d.data(), d.size(), &off, 512, &r) && r.chunk.dl_size == 0);  // empty chunk
  std::vector<u8> g;
  Put32(g, 103u << 16), Put32(g, 32 + 32), Put32(g, 1), Put32(g, 1), Put32(g, 32), Put32(g, 0);
  g.resize(g.size() + 16 + 32);
  off = 0;
  CHECK(NextInboxRecord(g.data(), g.size(), &off, 512, &r) && r.chunk.kcl_size == 0);  // drawn only
  std::vector<u8> k;
  Put32(k, 103u << 16), Put32(k, 32 + 8), Put32(k, 1), Put32(k, 1), Put32(k, 0), Put32(k, 8);
  k.resize(k.size() + 16 + 8);
  off = 0;
  CHECK(!NextInboxRecord(k.data(), k.size(), &off, 512, &r));  // collision without anything drawn
  std::vector<u8> e;
  Put32(e, 103u << 16), Put32(e, 1000), Put32(e, 1);
  off = 0;
  CHECK(!NextInboxRecord(e.data(), e.size(), &off, 512, &r));  // longer than the inbox
}

// Water: a chunk's translucent faces are a second display list after the first (CHUNK_TRANSLUCENT
// in the slot word, then a word: where it starts).
static void TestInboxTranslucentChunks()
{
  InboxRecord r;
  u32 off = 0;
  std::vector<u8> b;
  Put32(b, 103u << 16), Put32(b, 36 + 64 + 8), Put32(b, 0x200000u | 5), Put32(b, 2), Put32(b, 64), Put32(b, 8);
  PutF(b, 10.f), PutF(b, 20.f), PutF(b, 30.f), PutF(b, 99.f), Put32(b, 32);
  b.resize(b.size() + 64 + 8);
  CHECK(NextInboxRecord(b.data(), b.size(), &off, 512, &r) && r.chunk.slot == 5 && r.chunk.dl_size == 64 &&
        r.chunk.solid_size == 32 && r.chunk.kcl_size == 8 && r.chunk.dl == b.data() + 8 + 36);
  std::vector<u8> c;  // no flag: all of it opaque
  Put32(c, 103u << 16), Put32(c, 32 + 32), Put32(c, 5), Put32(c, 2), Put32(c, 32), Put32(c, 0);
  PutF(c, 10.f), PutF(c, 20.f), PutF(c, 30.f), PutF(c, 99.f);
  c.resize(c.size() + 32);
  off = 0;
  CHECK(NextInboxRecord(c.data(), c.size(), &off, 512, &r) && r.chunk.solid_size == 32);
  std::vector<u8> d;  // the split past the end
  Put32(d, 103u << 16), Put32(d, 36 + 32), Put32(d, 0x200000u | 5), Put32(d, 2), Put32(d, 32), Put32(d, 0);
  PutF(d, 10.f), PutF(d, 20.f), PutF(d, 30.f), PutF(d, 99.f), Put32(d, 64);
  d.resize(d.size() + 32);
  off = 0;
  CHECK(!NextInboxRecord(d.data(), d.size(), &off, 512, &r));
}

// Animated tiles: each live tile takes its frame's texels, every level, when the frame changes.
static void TestAtlasAnim()
{
  const u32 w = 64, h = 64, levels = 2, texels = w * h * 2 + (w / 2) * (h / 2) * 2;
  std::vector<u8> a(texels, 0);
  // Tile 5 (x 1, y 1) is all 0x1111, tile 6 all 0x2222, on both levels.
  for (u32 l = 0; l < levels; l++)
  {
    const u32 lw = w >> l, t = 16 >> l, base = l ? w * h * 2 : 0;
    for (u32 y = 0; y < t; y++)
      for (u32 x = 0; x < t; x++)
      {
        a[base + Rgb5a3Offset(lw, t + x, t + y)] = 0x11;
        a[base + Rgb5a3Offset(lw, 2 * t + x, t + y)] = 0x22;
      }
  }
  Put32(a, AtlasAnim::MAGIC), Put32(a, 1);
  a.push_back(0), a.push_back(0);  // live tile 0
  a.push_back(0), a.push_back(3);  // 3 game frames each
  a.push_back(0), a.push_back(2);  // 2 frames
  a.push_back(0), a.push_back(0);
  a.push_back(0), a.push_back(5), a.push_back(0), a.push_back(6);
  AtlasAnim anim;
  CHECK(anim.Load(a.data(), a.size(), texels, w, h, levels) && anim.Count() == 1);
  u32 lo, hi;
  anim.Tick(a.data(), 0, &lo, &hi);
  CHECK(hi > lo && a[Rgb5a3Offset(w, 15, 15)] == 0x11 && a[w * h * 2 + Rgb5a3Offset(w / 2, 7, 7)] == 0x11);
  anim.Tick(a.data(), 2, &lo, &hi);
  CHECK(hi == 0);  // same frame: nothing copied
  anim.Tick(a.data(), 3, &lo, &hi);
  CHECK(hi > 0 && a[Rgb5a3Offset(w, 0, 0)] == 0x22 && a[w * h * 2 + Rgb5a3Offset(w / 2, 0, 0)] == 0x22);
  CHECK(a[Rgb5a3Offset(w, 16, 0)] == 0);  // its neighbor untouched
  CHECK(anim.Load(a.data(), texels, texels, w, h, levels) && anim.Count() == 0);  // no table
  a[texels + 9] = 99;  // live tile past the atlas
  CHECK(!anim.Load(a.data(), a.size(), texels, w, h, levels) && anim.Count() == 0);
}

// Several planets: a chunk's slot carries its planet's id in the top byte, a far view's part has
// bit 23 set (its tile below, bit 22 if covered); a planet may come with flags (GONE); a teleport may name its planet.
static void TestInboxFlatPlanet()
{
  std::vector<u8> b;  // a station: the flags, then its gravity box
  Put32(b, 102u << 16), Put32(b, 88), Put32(b, 7), PutF(b, 100.f), PutF(b, 0.f), PutF(b, 0.f), PutF(b, 400.f),
      PutF(b, 900.f), Put32(b, 12), PutF(b, 0.f), PutF(b, 24.f), Put32(b, PLANET_FLAT);
  PutF(b, 0.f), PutF(b, 1.f), PutF(b, 0.f);      // up
  PutF(b, 0.f), PutF(b, 0.f), PutF(b, 1.f);      // forward
  PutF(b, 520.f), PutF(b, 1000.f), PutF(b, 520.f);  // half extents
  PutF(b, 0.f), PutF(b, 960.f), PutF(b, 0.f);    // the box's center from the station's
  InboxRecord r;
  u32 off = 0;
  CHECK(NextInboxRecord(b.data(), b.size(), &off, 512, &r) && r.type == InboxRecord::PLANET);
  CHECK(r.planet.flat && r.planet.flags == PLANET_FLAT && r.planet.chunk_count == 12);
  CHECK(r.planet.up[1] == 1.f && r.planet.forward[2] == 1.f && r.planet.half[0] == 520.f && r.planet.box_center[1] == 960.f);
  f32 m[3][4];
  FlatBoxMatrix(r.planet, m);
  // Columns: right (up x forward) * half x, up * half y, forward * half z; translation: the box's center.
  CHECK(m[0][0] == 520.f && m[1][1] == 1000.f && m[2][2] == 520.f && m[0][1] == 0.f);
  CHECK(m[0][3] == 100.f && m[1][3] == 960.f && m[2][3] == 0.f);
  std::vector<u8> planet;  // a planet's record never reads past its flags
  Put32(planet, 102u << 16), Put32(planet, 40), Put32(planet, 8), PutF(planet, 0.f), PutF(planet, 0.f), PutF(planet, 0.f),
      PutF(planet, 1.f), PutF(planet, 2.f), Put32(planet, 0), PutF(planet, 0.f), PutF(planet, 0.f), Put32(planet, 0);
  off = 0;
  CHECK(NextInboxRecord(planet.data(), planet.size(), &off, 512, &r) && !r.planet.flat);
  std::vector<u8> bad;  // FLAT without its box
  Put32(bad, 102u << 16), Put32(bad, 40), Put32(bad, 8), PutF(bad, 0.f), PutF(bad, 0.f), PutF(bad, 0.f),
      PutF(bad, 1.f), PutF(bad, 2.f), Put32(bad, 0), PutF(bad, 0.f), PutF(bad, 0.f), Put32(bad, PLANET_FLAT);
  off = 0;
  CHECK(!NextInboxRecord(bad.data(), bad.size(), &off, 512, &r));
}

static void TestInboxPlanetIdsAndFarView()
{
  std::vector<u8> b;
  Put32(b, 102u << 16), Put32(b, 40), Put32(b, 9), PutF(b, 1.f), PutF(b, 2.f), PutF(b, 3.f), PutF(b, 1280.f),
      PutF(b, 4480.f), Put32(b, 0), PutF(b, 640.f), PutF(b, 28.f), Put32(b, 1);
  Put32(b, 103u << 16), Put32(b, 32 + 32), Put32(b, (9u << 24) | 70000), Put32(b, 2), Put32(b, 32), Put32(b, 0);
  PutF(b, 10.f), PutF(b, 20.f), PutF(b, 30.f), PutF(b, 99.f);
  b.resize(b.size() + 32);
  Put32(b, 103u << 16), Put32(b, 32 + 32), Put32(b, (9u << 24) | 0x800000u | 5), Put32(b, 3), Put32(b, 32), Put32(b, 0);
  PutF(b, 10.f), PutF(b, 20.f), PutF(b, 30.f), PutF(b, 99.f);
  b.resize(b.size() + 32);
  Put32(b, 104u << 16), Put32(b, 8), PutF(b, 1500.f), Put32(b, 9);
  InboxRecord r;
  u32 off = 0;
  CHECK(NextInboxRecord(b.data(), b.size(), &off, 131072, &r) && r.planet.id == 9 && r.planet.flags == 1 &&
        r.planet.chunk_count == 0);
  CHECK(NextInboxRecord(b.data(), b.size(), &off, 131072, &r) && r.chunk.planet == 9 && !r.chunk.far &&
        r.chunk.slot == 70000);
  CHECK(NextInboxRecord(b.data(), b.size(), &off, 131072, &r) && r.chunk.planet == 9 && r.chunk.far && r.chunk.slot == 5);
  CHECK(NextInboxRecord(b.data(), b.size(), &off, 131072, &r) && r.teleport.ground == 1500.f && r.teleport.planet == 9);
  std::vector<u8> c;  // a far view has 6 × 16 × 16 tiles
  Put32(c, 103u << 16), Put32(c, 32), Put32(c, 0x800000u | 1535), Put32(c, 1), Put32(c, 0), Put32(c, 0);
  c.resize(c.size() + 16);
  off = 0;
  CHECK(NextInboxRecord(c.data(), c.size(), &off, 131072, &r) && r.chunk.far && r.chunk.slot == 1535 && !r.chunk.covered);
  c.clear();  // covered (its tile is chunks in the game), no display list: keep the one it has
  Put32(c, 103u << 16), Put32(c, 32), Put32(c, 0x800000u | 0x400000u | 1535), Put32(c, 2), Put32(c, 0), Put32(c, 0);
  c.resize(c.size() + 16);
  off = 0;
  CHECK(NextInboxRecord(c.data(), c.size(), &off, 131072, &r) && r.chunk.far && r.chunk.covered && r.chunk.slot == 1535);
  c.clear();
  Put32(c, 103u << 16), Put32(c, 32), Put32(c, 0x800000u | 1536), Put32(c, 1), Put32(c, 0), Put32(c, 0);
  c.resize(c.size() + 16);
  off = 0;
  CHECK(!NextInboxRecord(c.data(), c.size(), &off, 131072, &r));
}

static long gFirstFreed;
static int gFreedCount;
static void CountFree(void* p)
{
  if (gFreedCount++ == 0)
    gFirstFreed = reinterpret_cast<long>(p);
}

// Replaced chunks' memory waits its frames however many are replaced at once (a planet streaming
// in replaces dozens a frame): Mario's binder may still read the last triangle he stood on.
static void TestGraves()
{
  static Graves g;
  g.Reset();
  gFreedCount = 0;
  for (long i = 1; i <= 100; i++)
    g.Bury(reinterpret_cast<void*>(i), 8, CountFree);
  g.Bury(0, 8, CountFree);  // nothing
  CHECK(g.Count() == 100 && gFreedCount == 0);
  for (int f = 0; f < 7; f++)
    g.Tick(CountFree);
  CHECK(gFreedCount == 0);
  g.Bury(reinterpret_cast<void*>(101), 8, CountFree);
  g.Tick(CountFree);  // the 8th frame: the first hundred go, the last one waits
  CHECK(gFreedCount == 100 && g.Count() == 1 && gFirstFreed == 1);
  for (int f = 0; f < 7; f++)
    g.Tick(CountFree);
  CHECK(gFreedCount == 101 && g.Count() == 0 && g.Early() == 0);
  // More than it holds: the oldest goes early, and that is counted.
  g.Reset();
  gFreedCount = 0;
  for (u32 i = 0; i < Graves::SLOTS + 3; i++)
    g.Bury(reinterpret_cast<void*>(static_cast<long>(i + 1)), 8, CountFree);
  CHECK(g.Count() == Graves::SLOTS && g.Early() == 3 && gFreedCount == 3 && gFirstFreed == 1);
}

static void TestPlanetDropAndViewTranslate()
{
  const f32 c[3] = {100.f, 0.f, 0.f}, mario[3] = {100.f, 0.f, 50.f}, at_center[3] = {100.f, 0.f, 0.f};
  f32 out[3];
  PlanetDrop(c, 1280.f, 40.f, mario, out);
  CHECK(Near(out[0], 100.f, 0.05f) && Near(out[1], 0.f, 0.05f) && Near(out[2], 1320.f, 0.05f));
  PlanetDrop(c, 1280.f, 40.f, at_center, out);
  CHECK(Near(out[1], 1320.f, 0.05f));
  const f32 view[12] = {1, 0, 0, 5, 0, 0, 1, 6, 0, -1, 0, 7};
  const f32 t[3] = {1.f, 2.f, 3.f};
  f32 m[12];
  ViewTranslate(view, t, m);
  // A camera at (1, 2, 3) looking down -x (its z axis is +x): LookAt-built views give it back.
  {
    const f32 at[3] = {1, 2, 3}, look[3] = {-1, 0, 0}, up[3] = {0, 1, 0};
    f32 v[12], e[3], f[3];
    LookAtView(at, look, up, v);
    ViewEye(v, e, f);
    CHECK(Near(e[0], 1.f) && Near(e[1], 2.f) && Near(e[2], 3.f));
    CHECK(Near(f[0], -1.f) && Near(f[1], 0.f) && Near(f[2], 0.f));
  }
  // 100,000 blocks out (8e6 units), planets up to 2000 blocks from the camera, cameras every way:
  // ViewTranslate's float sum of two big numbers that cancel is off by units; ViewRelative is
  // as good as the floats it is given (the floating origin keeps those small).
  {
    const f32 up[3] = {0, 1, 0};
    f32 v[12], rel[12], abs_[12];
    f32 worst_rel = 0.f, worst_abs = 0.f;
    u32 seed = 12345u;
    for (int i = 0; i < 500; i++)
    {
      f32 r[9];
      for (int k = 0; k < 9; k++)
        r[k] = static_cast<f32>((seed = seed * 1664525u + 1013904223u) >> 8) / 8388608.f - 1.f;  // [-1, 1)
      const f32 eye[3] = {r[0] * 8.0e6f, r[1] * 8.0e6f, r[2] * 8.0e6f}, look[3] = {r[3], r[4], r[5]};
      const f32 t[3] = {eye[0] + r[6] * 160000.f, eye[1] + r[7] * 160000.f, eye[2] + r[8] * 160000.f};
      LookAtView(eye, look, up, v);
      ViewRelative(v, eye, t, rel);
      ViewTranslate(v, t, abs_);
      for (int row = 0; row < 3; row++)
      {
        double exact = 0;
        for (int k = 0; k < 3; k++)
          exact += static_cast<double>(v[4 * row + k]) * (static_cast<double>(t[k]) - eye[k]);
        const f32 a = static_cast<f32>(rel[4 * row + 3] - exact), b = static_cast<f32>(abs_[4 * row + 3] - exact);
        worst_rel = a > worst_rel ? a : (-a > worst_rel ? -a : worst_rel);
        worst_abs = b > worst_abs ? b : (-b > worst_abs ? -b : worst_abs);
      }
    }
    CHECK(worst_rel < 0.02f);  // the last bit of a float near 160000
    CHECK(worst_abs > 0.5f);   // why it exists
    const f32 look[3] = {0.6f, 0.f, -0.8f};
    // Near the origin both agree.
    const f32 eye0[3] = {10.f, 20.f, 30.f}, b0[3] = {12.f, 21.f, 27.f};
    LookAtView(eye0, look, up, v);
    ViewRelative(v, eye0, b0, rel);
    ViewTranslate(v, b0, abs_);
    for (int k = 0; k < 12; k++)
      CHECK(Near(rel[k], abs_[k], 1e-4f));
  }
  // A ball of radius 100 at the origin, the camera at (0, 0, 300) looking at it (-z).
  const f32 cam[3] = {0, 0, 300}, fwd[3] = {0, 0, -1}, o[3] = {0, 0, 0};
  const f32 front[3] = {0, 0, 110}, back[3] = {0, 0, -110}, side[3] = {110, 0, 0}, behind[3] = {0, 0, 400};
  CHECK(!SphereHidden(cam, fwd, o, 100.f, front, 5.f));
  CHECK(SphereHidden(cam, fwd, o, 100.f, back, 5.f));
  CHECK(!SphereHidden(cam, fwd, o, 100.f, side, 5.f));  // on the rim: seen
  CHECK(SphereHidden(cam, fwd, o, 100.f, behind, 5.f));
  const f32 tall[3] = {0, 0, -400};  // far behind the ball but huge: sticks out
  CHECK(!SphereHidden(cam, fwd, o, 100.f, tall, 300.f));
  CHECK(Near(m[3], 6.f) && Near(m[7], 9.f) && Near(m[11], 5.f) && m[6] == 1.f);

  // A 90° wide, 60° tall view (as C_MTXPerspective builds it: m00 = cot(fovx/2), m11 = cot(fovy/2)).
  const f32 proj[7] = {0.f, 1.f, 0.f, 1.7320508f, 0.f, -1.f, -10.f};
  const f32 ahead[3] = {0, 0, -100}, right[3] = {150, 0, -100}, rightEdge[3] = {104, 0, -100};
  const f32 up[3] = {0, 70, -100}, upEdge[3] = {0, 60, -100}, back2[3] = {0, 0, 100};
  CHECK(!SphereOutsideView(proj, ahead, 1.f));
  CHECK(SphereOutsideView(proj, right, 10.f));
  CHECK(!SphereOutsideView(proj, rightEdge, 10.f));  // pokes into the view
  CHECK(SphereOutsideView(proj, up, 1.f));
  CHECK(!SphereOutsideView(proj, upEdge, 3.f));
  CHECK(SphereOutsideView(proj, back2, 1.f));
  CHECK(!SphereOutsideView(proj, back2, 500.f));  // around the camera
  const f32 ortho[7] = {1.f, 1.f, 0.f, 1.f, 0.f, -1.f, 0.f};
  CHECK(!SphereOutsideView(ortho, right, 1.f));
}

static float GetBEF(const u8* p)
{
  const u32 u = (u32(p[0]) << 24) | (u32(p[1]) << 16) | (u32(p[2]) << 8) | u32(p[3]);
  float f;
  memcpy(&f, &u, 4);
  return f;
}

static void TestInboxOutline()
{
  std::vector<u8> b;
  Put32(b, 105u << 16), Put32(b, 8 + 2 * 24), Put32(b, 1), Put32(b, 2);
  for (int k = 0; k < 12; k++)
    PutF(b, static_cast<float>(k));
  Put32(b, 105u << 16), Put32(b, 8), Put32(b, 0), Put32(b, 0);  // none
  Put32(b, 105u << 16), Put32(b, 8 + 24), Put32(b, 1), Put32(b, 2);  // says two, has one: malformed
  b.resize(b.size() + 24);
  InboxRecord r;
  u32 off = 0;
  CHECK(NextInboxRecord(b.data(), b.size(), &off, 512, &r) && r.type == InboxRecord::OUTLINE);
  CHECK(r.outline.visible == 1 && r.outline.count == 2);
  std::vector<u8> dl(OUTLINE_DL_BYTES, 0xEE);
  OutlineList(r.outline, 6, dl.data());
  CHECK(OUTLINE_DL_BYTES % 32 == 0 && OUTLINE_DL_BYTES >= 3 + OUTLINE_MAX_EDGES * 24);
  CHECK(dl[0] == (0xA8 | 6) && dl[1] == 0 && dl[2] == 4);  // GX_LINES, four vertices
  CHECK(GetBEF(dl.data() + 3) == 0.f && GetBEF(dl.data() + 3 + 11 * 4) == 11.f);
  CHECK(dl[3 + 48] == 0 && dl[OUTLINE_DL_BYTES - 1] == 0);  // GX_NOP after them
  CHECK(NextInboxRecord(b.data(), b.size(), &off, 512, &r) && r.outline.visible == 0 && r.outline.count == 0);
  CHECK(!NextInboxRecord(b.data(), b.size(), &off, 512, &r));
  std::vector<u8> many;
  Put32(many, 105u << 16), Put32(many, 8 + 97 * 24), Put32(many, 1), Put32(many, 97);  // too many
  many.resize(many.size() + 97 * 24);
  off = 0;
  CHECK(!NextInboxRecord(many.data(), many.size(), &off, 512, &r));
}

static void TestInboxOriginAndStars()
{
  std::vector<u8> b;
  Put32(b, 116u << 16), Put32(b, 16), Put32(b, 7), Put32(b, static_cast<u32>(-3)), Put32(b, 0), Put32(b, 12);
  // Four stars: 1, 2.4, 3 and 9 pixels.
  const float px[4] = {1.f, 2.4f, 3.f, 9.f};
  Put32(b, 117u << 16), Put32(b, 4 + 4 * 20), Put32(b, 4);
  for (int i = 0; i < 4; i++)
  {
    PutF(b, 0.f), PutF(b, 1.f), PutF(b, static_cast<float>(i));
    PutF(b, px[i]), Put32(b, 0x11223300u + i);
  }
  Put32(b, 116u << 16), Put32(b, 12), Put32(b, 7), Put32(b, 0), Put32(b, 0);  // short: malformed
  InboxRecord r;
  u32 off = 0;
  CHECK(NextInboxRecord(b.data(), b.size(), &off, 512, &r) && r.type == InboxRecord::ORIGIN);
  CHECK(r.origin.epoch == 7 && r.origin.shift[0] == -3 && r.origin.shift[1] == 0 && r.origin.shift[2] == 12);
  CHECK(NextInboxRecord(b.data(), b.size(), &off, 512, &r) && r.type == InboxRecord::STARS && r.stars.count == 4);
  std::vector<u8> dl(STARS_DL_BYTES, 0xEE);
  u32 at[STAR_CLASSES], bytes[STAR_CLASSES];
  StarLists(r.stars, 6, dl.data(), at, bytes);
  // 1 px -> 6, 2.4 px -> 12, 3 px -> 18, 9 px -> 30 (the biggest class).
  for (u32 k = 0; k < STAR_CLASSES; k++)
  {
    CHECK(bytes[k] == 32 && at[k] == 32 * k);
    CHECK(dl[at[k]] == (0xB8 | 6) && dl[at[k] + 1] == 0 && dl[at[k] + 2] == 1);  // GX_POINTS, one vertex
    CHECK(GetBEF(dl.data() + at[k] + 3 + 4) == 1.f && GetBEF(dl.data() + at[k] + 3 + 8) == static_cast<float>(k));
    CHECK(dl[at[k] + 3 + 12] == 0x11 && dl[at[k] + 3 + 15] == k);  // its color
    CHECK(dl[at[k] + 19] == 0 && dl[at[k] + 31] == 0);  // GX_NOP after it
  }
  CHECK(!NextInboxRecord(b.data(), b.size(), &off, 512, &r));
  // More stars than the game takes: refused.
  std::vector<u8> many;
  Put32(many, 117u << 16), Put32(many, 4 + (STARS_MAX + 1) * 20), Put32(many, STARS_MAX + 1);
  many.resize(many.size() + (STARS_MAX + 1) * 20);
  off = 0;
  CHECK(!NextInboxRecord(many.data(), many.size(), &off, 512, &r));
}

static void TestInboxCrack()
{
  std::vector<u8> b;
  Put32(b, 115u << 16), Put32(b, 120), Put32(b, 4), Put32(b, 9);
  PutF(b, 0.25f), PutF(b, 0.5f), PutF(b, 0.3125f), PutF(b, 0.5625f);
  for (int k = 0; k < 24; k++)
    PutF(b, static_cast<float>(k));
  Put32(b, 115u << 16), Put32(b, 120), Put32(b, 4), Put32(b, 10);  // no stage 10
  b.resize(b.size() + 112);
  InboxRecord r;
  u32 off = 0;
  CHECK(NextInboxRecord(b.data(), b.size(), &off, 512, &r) && r.type == InboxRecord::CRACK);
  CHECK(r.crack.visible == 4 && r.crack.stage == 9);
  CHECK(r.crack.uv[0] == 0.25f && r.crack.uv[3] == 0.5625f);
  CHECK(r.crack.corners[0][0] == 0.f && r.crack.corners[7][2] == 23.f);
  CHECK(!NextInboxRecord(b.data(), b.size(), &off, 512, &r));
  std::vector<u8> s;
  Put32(s, 115u << 16), Put32(s, 100), Put32(s, 4);  // an outline's size: malformed
  s.resize(s.size() + 96);
  off = 0;
  CHECK(!NextInboxRecord(s.data(), s.size(), &off, 512, &r));
}

static void TestCrackMesh()
{
  // A unit box: corner m at (dj, dk, di) = (m >> 1 & 1, m >> 2, m & 1).
  f32 corners[8][3];
  for (int m = 0; m < 8; m++)
    corners[m][0] = static_cast<f32>(m >> 1 & 1), corners[m][1] = static_cast<f32>(m >> 2),
    corners[m][2] = static_cast<f32>(m & 1);
  const f32 uv[4] = {0.25f, 0.5f, 0.3125f, 0.5625f};
  std::vector<u8> dl(CRACK_DL_BYTES, 0xEE);
  CrackMesh(corners, uv, 6, dl.data());
  CHECK(CRACK_DL_BYTES % 32 == 0 && CRACK_DL_BYTES >= 3 + 24 * 20);
  CHECK(dl[0] == (0x80 | 6) && dl[1] == 0 && dl[2] == 24);
  CHECK(dl[3 + 24 * 20] == 0 && dl[CRACK_DL_BYTES - 1] == 0);  // GX_NOP after the quads
  // Each side: its four corners share the coordinate of its axis, and they span the other two.
  int sides[3][2] = {{0, 0}, {0, 0}, {0, 0}};
  for (int q = 0; q < 6; q++)
  {
    float v[4][5];
    for (int i = 0; i < 4; i++)
      for (int k = 0; k < 5; k++)
        v[i][k] = GetBEF(dl.data() + 3 + (q * 4 + i) * 20 + 4 * k);
    int axis = -1;
    for (int k = 0; k < 3; k++)
      if (v[0][k] == v[1][k] && v[1][k] == v[2][k] && v[2][k] == v[3][k])
        axis = k;
    CHECK(axis >= 0);
    if (axis < 0)
      continue;
    sides[axis][v[0][axis] > 0.5f]++;
    // The whole tile: two corners at u0 and two at u1, and the same for v.
    int u0 = 0, v0 = 0;
    for (int i = 0; i < 4; i++)
    {
      CHECK(v[i][3] == uv[0] || v[i][3] == uv[2]);
      CHECK(v[i][4] == uv[1] || v[i][4] == uv[3]);
      u0 += v[i][3] == uv[0], v0 += v[i][4] == uv[1];
    }
    CHECK(u0 == 2 && v0 == 2);
    // Upright on the four sides: the top of the tile (v0) is up (y = 1).
    if (axis != 1)
      for (int i = 0; i < 4; i++)
        CHECK((v[i][4] == uv[1]) == (v[i][1] == 1.f));
  }
  for (int k = 0; k < 3; k++)
    CHECK(sides[k][0] == 1 && sides[k][1] == 1);
}

static void TestInboxHeld()
{
  std::vector<u8> b;
  Put32(b, 106u << 16), Put32(b, 2064), Put32(b, 1), Put32(b, 0), Put32(b, 0), Put32(b, 0);
  for (int k = 0; k < 2048; k++)
    b.push_back(static_cast<u8>(k));
  Put32(b, 106u << 16), Put32(b, 2064), Put32(b, 3), Put32(b, 1), Put32(b, 0), Put32(b, 0);  // the off hand
  b.resize(b.size() + 2048);
  Put32(b, 106u << 16), Put32(b, 2064), Put32(b, 3), Put32(b, 2), Put32(b, 0), Put32(b, 0);  // no hand 2
  b.resize(b.size() + 2048);
  Put32(b, 106u << 16), Put32(b, 2064), Put32(b, 5), Put32(b, 0), Put32(b, 0), Put32(b, 0);  // no kind 5
  b.resize(b.size() + 2048);
  InboxRecord r;
  u32 off = 0;
  CHECK(NextInboxRecord(b.data(), b.size(), &off, 512, &r) && r.type == InboxRecord::HELD);
  CHECK(r.held.kind == HELD_BLOCK && r.held.hand == 0 && r.held.sprite == b.data() + 24 && r.held.sprite[2047] == 255);
  CHECK(NextInboxRecord(b.data(), b.size(), &off, 512, &r) && r.held.kind == HELD_ITEM && r.held.hand == 1);
  CHECK(!NextInboxRecord(b.data(), b.size(), &off, 512, &r));
}

static void TestInboxEntities()
{
  std::vector<u8> b;
  Put32(b, 108u << 16), Put32(b, 12 + 8 * 4 * 2), Put32(b, 3), Put32(b, 8), Put32(b, 4);
  b.resize(b.size() + 64);
  Put32(b, 109u << 16), Put32(b, 8 + 32), Put32(b, 7), Put32(b, 32);
  b.resize(b.size() + 32);
  Put32(b, 110u << 16), Put32(b, 4 + 2 * ENT_BYTES), Put32(b, 2);
  b.resize(b.size() + 2 * ENT_BYTES);
  Put32(b, 111u << 16), Put32(b, 16), Put32(b, 0x3F800000), Put32(b, 0), Put32(b, 0), Put32(b, 2);
  Put32(b, 112u << 16), Put32(b, 16), Put32(b, 0), Put32(b, 0x40000000), Put32(b, 0), Put32(b, 1);
  Put32(b, 109u << 16), Put32(b, 8 + 16), Put32(b, 1), Put32(b, 16);  // not a multiple of 32
  b.resize(b.size() + 16);
  InboxRecord r;
  u32 off = 0;
  CHECK(NextInboxRecord(b.data(), b.size(), &off, 512, &r) && r.type == InboxRecord::SKIN);
  CHECK(r.skin.id == 3 && r.skin.width == 8 && r.skin.height == 4 && r.skin.data == b.data() + 20);
  CHECK(NextInboxRecord(b.data(), b.size(), &off, 512, &r) && r.type == InboxRecord::MODEL);
  CHECK(r.model.id == 7 && r.model.dl_size == 32);
  CHECK(NextInboxRecord(b.data(), b.size(), &off, 512, &r) && r.type == InboxRecord::ENTITIES);
  CHECK(r.entities.count == 2);
  CHECK(NextInboxRecord(b.data(), b.size(), &off, 512, &r) && r.type == InboxRecord::HURT);
  CHECK(r.hurt.from[0] == 1.f && r.hurt.kind == 2);
  CHECK(NextInboxRecord(b.data(), b.size(), &off, 512, &r) && r.type == InboxRecord::SEAT);
  CHECK(r.seat.pos[1] == 2.f && r.seat.riding == 1);
  CHECK(!NextInboxRecord(b.data(), b.size(), &off, 512, &r));
}

// A GxcAtlas record: header words, then size bytes of data.
static void PutAtlas(std::vector<u8>& b, u32 id, u32 w, u32 h, u32 levels, u32 total, u32 offset, u32 size)
{
  Put32(b, 107u << 16), Put32(b, 24 + size);
  Put32(b, id), Put32(b, w), Put32(b, h), Put32(b, levels), Put32(b, total), Put32(b, offset);
  for (u32 k = 0; k < size; k++)
    b.push_back(static_cast<u8>(k));
  while (b.size() % 4)
    b.push_back(0);
}

static void TestInboxAtlas()
{
  CHECK(AtlasBytes(64, 64, 4) == (64 * 64 + 32 * 32 + 16 * 16 + 8 * 8) * 2);
  CHECK(AtlasBytes(1024, 512, 1) == 1024 * 512 * 2);
  const u32 total = AtlasBytes(256, 128, 4);
  std::vector<u8> b;
  PutAtlas(b, 3, 256, 128, 4, total, 100, 10);
  PutAtlas(b, 3, 256, 128, 4, total, total - 10, 10);  // the last piece
  InboxRecord r;
  u32 off = 0;
  CHECK(NextInboxRecord(b.data(), b.size(), &off, 512, &r) && r.type == InboxRecord::ATLAS);
  CHECK(r.atlas.id == 3 && r.atlas.width == 256 && r.atlas.height == 128 && r.atlas.levels == 4);
  CHECK(r.atlas.offset == 100 && r.atlas.size == 10 && r.atlas.data == b.data() + 8 + 24 && r.atlas.data[9] == 9);
  CHECK(NextInboxRecord(b.data(), b.size(), &off, 512, &r) && r.atlas.offset == total - 10);
  CHECK(!NextInboxRecord(b.data(), b.size(), &off, 512, &r));
  // Rejected: past its end, a total under its texels or too far over (an animation table), not a power of two, too big, mipmaps under 8 texels.
  const u32 bad[][6] = {{256, 128, 4, total, total - 5, 10}, {256, 128, 4, total - 2, 0, 10}, {256, 128, 4, total + 65540, 0, 10},
                        {200, 128, 1, 200 * 128 * 2, 0, 10}, {2048, 8, 1, 2048 * 8 * 2, 0, 10},
                        {64, 16, 3, AtlasBytes(64, 16, 3), 0, 10}};
  for (const auto& v : bad)
  {
    std::vector<u8> c;
    PutAtlas(c, 1, v[0], v[1], v[2], v[3], v[4], v[5]);
    off = 0;
    CHECK(!NextInboxRecord(c.data(), c.size(), &off, 512, &r));
  }
}

// A 16x64 RGB5A3 sprite whose band 0 has the texels for which solid(x, y) holds opaque, the
// rest holes.
template <typename F>
static std::vector<u8> Sprite(F solid)
{
  std::vector<u8> s(HELD_SPRITE_BYTES, 0);
  for (int y = 0; y < 16; y++)
    for (int x = 0; x < 16; x++)
      if (solid(x, y))
        s[2 * (16 * ((y / 4) * 4 + x / 4) + 4 * (y % 4) + x % 4)] = 0x80;
  return s;
}

// The vertices of a held mesh: position (texels), texture coordinate (128ths).
struct HeldVertex
{
  float x, y, z;
  int s, t;
  u8 shade;
};

static std::vector<HeldVertex> HeldVertices(const std::vector<u8>& dl, u32 size)
{
  std::vector<HeldVertex> v;
  const u32 n = (u32(dl[1]) << 8) | dl[2];
  for (u32 i = 0; i < n; i++)
  {
    const u8* p = dl.data() + 3 + i * HELD_VERTEX_BYTES;
    v.push_back({p[0] / 2.f, p[1] / 2.f, p[2] / 2.f, p[7], p[8], p[3]});
  }
  CHECK(3 + n * HELD_VERTEX_BYTES <= size);
  return v;
}

static void TestHeldBlock()
{
  std::vector<u8> dl(HELD_DL_MAX);
  const u32 size = HeldMesh(HELD_BLOCK, 0, 4, dl.data(), dl.size());
  CHECK(size % 32 == 0 && size >= 3 + 24 * HELD_VERTEX_BYTES && dl[0] == (0x80 | 4));
  const std::vector<HeldVertex> v = HeldVertices(dl, size);
  CHECK(v.size() == 24);
  bool top = false, bottom = false;
  for (size_t q = 0; q < v.size(); q += 4)
  {
    bool up = true, down = true;
    for (int k = 0; k < 4; k++)
    {
      CHECK(v[q + k].x >= 0 && v[q + k].x <= 16 && v[q + k].y >= 0 && v[q + k].y <= 16);
      up = up && v[q + k].y == 16;
      down = down && v[q + k].y == 0;
    }
    // Each face spans the sprite's width; the top shows band 0 (t 0..32), the sides band 1, the
    // bottom band 2.
    const int t = v[q].t < v[q + 2].t ? v[q].t : v[q + 2].t;
    const int s0 = v[q].s < v[q + 2].s ? v[q].s : v[q + 2].s, s1 = v[q].s < v[q + 2].s ? v[q + 2].s : v[q].s;
    top = top || up;
    bottom = bottom || down;
    CHECK(s0 == 0 && s1 == 128 && t == (up ? 0 : down ? 64 : 32));
    CHECK(v[q].shade == (up ? 255 : down ? 128 : v[q].shade));
    if (!up && !down)  // sides upright: the bottom edge at the bottom of the band
      CHECK(v[q].y == 0 && v[q].t == 64 && v[q + 2].y == 16 && v[q + 2].t == 32);
  }
  CHECK(top && bottom);
  // CUBE: band 0 on every face.
  const u32 cube = HeldMesh(HELD_CUBE, 0, 4, dl.data(), dl.size());
  for (const HeldVertex& c : HeldVertices(dl, cube))
    CHECK(c.t == 0 || c.t == 32);
}

static void TestHeldFlatItem()
{
  std::vector<u8> dl(HELD_DL_MAX);
  // One texel at (3, 0): both faces and its four sides.
  std::vector<u8> one = Sprite([](int x, int y) { return x == 3 && y == 0; });
  CHECK(SpriteSolid(one.data(), 3, 0) && !SpriteSolid(one.data(), 4, 0) && !SpriteSolid(one.data(), 3, 1));
  u32 size = HeldMesh(HELD_ITEM, one.data(), 4, dl.data(), dl.size());
  std::vector<HeldVertex> v = HeldVertices(dl, size);
  CHECK(v.size() == 6 * 4);
  for (size_t i = 8; i < v.size(); i++)  // the sides: inside the texel's column, its color
  {
    CHECK(v[i].x >= 3 && v[i].x <= 4 && v[i].y >= 15 && v[i].y <= 16 && v[i].z >= 7.5f && v[i].z <= 8.5f);
    CHECK(v[i].s == 28 && v[i].t == 1);
  }
  // The front face maps the whole of band 0, row 0 at the top.
  CHECK(v[0].z == 8.5f && v[0].y == 0 && v[0].t == 32 && v[3].y == 16 && v[3].t == 0 && v[1].s == 128);
  // A full sprite: sides only around the edge.
  std::vector<u8> full = Sprite([](int, int) { return true; });
  size = HeldMesh(HELD_TOOL, full.data(), 4, dl.data(), dl.size());
  CHECK(HeldVertices(dl, size).size() == (2 + 4 * 16) * 4);
  // A checkerboard is the worst case and still fits.
  std::vector<u8> checker = Sprite([](int x, int y) { return (x + y) % 2 == 0; });
  size = HeldMesh(HELD_ITEM, checker.data(), 4, dl.data(), dl.size());
  CHECK(size != 0 && HeldVertices(dl, size).size() == (2 + 4 * 128) * 4);
  // Nothing held, or nothing in the sprite.
  std::vector<u8> empty = Sprite([](int, int) { return false; });
  CHECK(HeldMesh(HELD_NONE, 0, 4, dl.data(), dl.size()) == 0);
  CHECK(HeldMesh(HELD_ITEM, empty.data(), 4, dl.data(), dl.size()) == 96);  // the two faces, cut out
  CHECK(HeldMesh(HELD_ITEM, checker.data(), 4, dl.data(), 1000) == 0);       // no room
}

static void TestMul34()
{
  // A turn of 90 degrees about z after a move by (1, 2, 3), applied to the origin and to x.
  const float turn[12] = {0, -1, 0, 0, 1, 0, 0, 0, 0, 0, 1, 0};
  const float move[12] = {1, 0, 0, 1, 0, 1, 0, 2, 0, 0, 1, 3};
  float m[12];
  Mul34(turn, move, m);
  CHECK(m[3] == -2.f && m[7] == 1.f && m[11] == 3.f);  // the origin goes to turn(1, 2, 3)
  CHECK(m[0] == 0.f && m[4] == 1.f && m[8] == 0.f);    // x turns into y
}

static void TestCodePatch()
{
  // Mario::checkAllWall's wall radius: lfs f30, 3552(r2) at 0x80390dfc.
  const u32 insn = 0xC3C20DE0u;
  CHECK(IsLfsR2(insn) && !IsLfsR2(0xC3CC0DE0u) && !IsLfsR2(0x3D808077u));
  CHECK(EncodeBranch(0x80390dfcu, 0x80391000u) == 0x48000204u);
  CHECK(EncodeBranch(0x80391000u, 0x80390dfcu) == 0x4BFFFDFCu);  // backwards
  u32 stub[3];
  BuildLoadStub(insn, 0x80390dfcu, 0x8076A010u, 0x80700000u, stub);
  CHECK(stub[0] == 0x3D808077u);  // lis r12, 0x8077 (the low half is negative)
  CHECK(stub[1] == 0xC3CCA010u);  // lfs f30, -0x5ff0(r12)
  CHECK(stub[2] == EncodeBranch(0x80700008u, 0x80390e00u));
}

// A platform 2 x 2 around the origin (x, z in [-1, 1]), up +y: ground under p.
static bool OnPlatform(const float p[3], void*)
{
  return p[0] >= -1.f && p[0] <= 1.f && p[2] >= -1.f && p[2] <= 1.f;
}

static void TestSneakStep()
{
  const float up[3] = {0, 1, 0}, front[3] = {0, 0, 1}, from[3] = {0.5f, 0, 0};
  // Inside: the move stands.
  float in[3] = {0.6f, 0, 0.1f};
  CHECK(!SneakStep(from, in, up, front, 0.3f, OnPlatform, 0));
  CHECK(in[0] == 0.6f && in[2] == 0.1f);
  // Overhanging while the box still touches: allowed, as in Minecraft.
  float hang[3] = {1.2f, 0, 0};
  CHECK(!SneakStep(from, hang, up, front, 0.3f, OnPlatform, 0));
  // Off the edge diagonally: the part along the edge stays (sliding along it).
  float off[3] = {1.6f, 0, 0.2f};
  CHECK(SneakStep(from, off, up, front, 0.3f, OnPlatform, 0));
  CHECK(std::fabs(off[0] - 0.5f) < 1e-6f && std::fabs(off[2] - 0.2f) < 1e-6f);
  // Straight off a corner: nothing of it.
  const float corner[3] = {1.2f, 0, 1.2f};
  float out[3] = {1.6f, 0.05f, 1.6f};
  CHECK(SneakStep(corner, out, up, front, 0.3f, OnPlatform, 0));
  CHECK(out[0] == corner[0] && out[2] == corner[2]);
  CHECK(std::fabs(out[1] - 0.05f) < 1e-6f);  // up and down are his own (a step, a slope)
}

static float BeF32(const u8* p)
{
  const u32 u = (u32(p[0]) << 24) | (u32(p[1]) << 16) | (u32(p[2]) << 8) | p[3];
  float f;
  std::memcpy(&f, &u, 4);
  return f;
}

static void TestShell()
{
  static u8 sphere[SHELL_SPHERE_DL_BYTES], box[SHELL_BOX_DL_BYTES];
  ShellSphereList(6, sphere);
  CHECK(sphere[0] == (0xA8 | 6));
  CHECK(((sphere[1] << 8) | sphere[2]) == int(SHELL_SPHERE_VERTS));
  bool unit = true, top = false, bottom = false;
  for (u32 v = 0; v < SHELL_SPHERE_VERTS; v++)
  {
    const u8* p = sphere + 3 + 12 * v;
    const float x = BeF32(p), y = BeF32(p + 4), z = BeF32(p + 8);
    unit = unit && std::fabs(x * x + y * y + z * z - 1.f) < 1e-4f;
    top = top || y > 0.9999f;
    bottom = bottom || y < -0.9999f;
  }
  CHECK(unit && top && bottom);
  CHECK(sphere[SHELL_SPHERE_DL_BYTES - 1] == 0);
  ShellBoxList(6, box);
  CHECK(((box[1] << 8) | box[2]) == int(SHELL_BOX_VERTS));
  bool onSides = true;
  for (u32 v = 0; v < SHELL_BOX_VERTS; v++)
  {
    const u8* p = box + 3 + 12 * v;
    float m = 0;
    for (int k = 0; k < 3; k++)
      m = std::fmax(m, std::fabs(BeF32(p + 4 * k)));
    onSides = onSides && std::fabs(m - 1.f) < 1e-6f;  // every line lies on the cube's sides
  }
  CHECK(onSides);
  CHECK(ShellAlpha(10.f, 100.f) == 1.f);
  CHECK(std::fabs(ShellAlpha(-25.f, 100.f) - 0.75f) < 1e-6f);
  CHECK(ShellAlpha(-100.f, 100.f) == 0.f && ShellAlpha(-500.f, 100.f) == 0.f);
  // In space all shells show; inside one gravity, only its own (fading), not the others'.
  CHECK(ShellShown(10.f, 100.f, false) == 1.f);
  CHECK(ShellShown(10.f, 100.f, true) == 0.f);
  // A panorama capture hides the shells: the sky message's red is sent 2 or more over the 0..1 it means.
  CHECK(ShellsHidden(2.f) && ShellsHidden(2.8f) && ShellsHidden(3.f));
  CHECK(!ShellsHidden(1.f) && !ShellsHidden(0.f) && !ShellsHidden(0.5f) && !ShellsHidden(-1.f) && !ShellsHidden(1.99f));
  CHECK(SkyChannel(2.5f) == 0.5f && SkyChannel(2.f) == 0.f && SkyChannel(0.25f) == 0.25f && SkyChannel(-3.f) == 0.f && SkyChannel(7.f) == 1.f);
  CHECK(std::fabs(ShellShown(-25.f, 100.f, true) - 0.75f) < 1e-6f);
}

int main()
{
  TestShell();
  TestSneakStep();
  TestInboxRecords();
  TestInboxRejectsBadChunks();
  TestInboxTranslucentChunks();
  TestAtlasAnim();
  TestInboxPlanetIdsAndFarView();
  TestInboxFlatPlanet();
  TestPlanetDropAndViewTranslate();
  TestCodePatch();
  TestInboxOutline();
  TestInboxCrack();
  TestInboxOriginAndStars();
  TestCrackMesh();
  TestInboxHeld();
  TestInboxEntities();
  TestInboxAtlas();
  TestMul34();
  TestHeldBlock();
  TestHeldFlatItem();
  TestJumpCeilingPeaksAtTheHeight();
  TestSelectNearest64();
  TestBigRadiusWinsOrder();
  TestNoneInRange();
  TestMaxOutCapped();
  TestViewIdentity();
  TestViewTarget();
  TestViewDegenerate();
  TestSqrt();
  TestKclSize();
  TestCameraEye();
  TestMarioVisible();
  TestGraves();
  if (g_failures)
  {
    std::printf("%d of %d checks failed\n", g_failures, g_checks);
    return 1;
  }
  std::printf("%d checks passed\n", g_checks);
  return 0;
}
