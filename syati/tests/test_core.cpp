// g++ tests for syati/src/core: the module's game-independent logic.
#include <cmath>
#include <cstdio>

#include <cstring>
#include <vector>

#include "Inbox.h"
#include "Kcl.h"
#include "Parts.h"
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
  Put32(b, 102u << 16), Put32(b, 32), Put32(b, 7), PutF(b, 1.f), PutF(b, 2.f), PutF(b, 3.f), PutF(b, 1280.f),
      PutF(b, 4480.f), Put32(b, 162), PutF(b, 640.f);
  Put32(b, 103u << 16), Put32(b, 32 + 32 + 8), Put32(b, 5), Put32(b, 2), Put32(b, 32), Put32(b, 8);
  PutF(b, 10.f), PutF(b, 20.f), PutF(b, 30.f), PutF(b, 99.f);
  for (int k = 0; k < 40; k++)
    b.push_back(static_cast<u8>(k));
  Put32(b, 104u << 16), Put32(b, 0);
  InboxRecord r;
  u32 off = 0;
  CHECK(NextInboxRecord(b.data(), b.size(), &off, 512, &r) && r.type == InboxRecord::PLANET);
  CHECK(r.planet.id == 7 && r.planet.center[2] == 3.f && r.planet.surface == 1280.f && r.planet.gravity_range == 4480.f &&
        r.planet.chunk_count == 162 && r.planet.occluder == 640.f);
  CHECK(NextInboxRecord(b.data(), b.size(), &off, 512, &r) && r.type == InboxRecord::CHUNK);
  CHECK(r.chunk.slot == 5 && r.chunk.version == 2 && r.chunk.dl[0] == 0 && r.chunk.kcl[0] == 32 &&
        r.chunk.sphere[1] == 20.f && r.chunk.sphere[3] == 99.f);
  CHECK(NextInboxRecord(b.data(), b.size(), &off, 512, &r) && r.type == InboxRecord::TELEPORT);
  CHECK(!NextInboxRecord(b.data(), b.size(), &off, 512, &r));
  CHECK(off == b.size());
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
}

int main()
{
  TestInboxRecords();
  TestInboxRejectsBadChunks();
  TestPlanetDropAndViewTranslate();
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
  if (g_failures)
  {
    std::printf("%d of %d checks failed\n", g_failures, g_checks);
    return 1;
  }
  std::printf("%d checks passed\n", g_checks);
  return 0;
}
