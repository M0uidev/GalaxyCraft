// g++ tests for syati/src/core: the module's game-independent logic.
#include <cmath>
#include <cstdio>

#include <cstring>
#include <vector>

#include "CodePatch.h"
#include "Graves.h"
#include "HeldMesh.h"
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

// Several planets: a chunk's slot carries its planet's id in the top byte, a far view's part has
// bit 23 set (its face below); a planet may come with flags (GONE); a teleport may name its planet.
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
  CHECK(NextInboxRecord(c.data(), c.size(), &off, 131072, &r) && r.chunk.far && r.chunk.slot == 1535);
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

static void TestInboxOutline()
{
  std::vector<u8> b;
  Put32(b, 105u << 16), Put32(b, 100), Put32(b, 1);
  for (int k = 0; k < 24; k++)
    PutF(b, static_cast<float>(k));
  Put32(b, 105u << 16), Put32(b, 96);  // short: malformed
  InboxRecord r;
  u32 off = 0;
  CHECK(NextInboxRecord(b.data(), b.size(), &off, 512, &r) && r.type == InboxRecord::OUTLINE);
  CHECK(r.outline.visible == 1 && r.outline.corners[0][0] == 0.f && r.outline.corners[7][2] == 23.f);
  CHECK(!NextInboxRecord(b.data(), b.size(), &off, 512, &r));
}

static void TestInboxHeld()
{
  std::vector<u8> b;
  Put32(b, 106u << 16), Put32(b, 2064), Put32(b, 1), Put32(b, 0), Put32(b, 0), Put32(b, 0);
  for (int k = 0; k < 2048; k++)
    b.push_back(static_cast<u8>(k));
  Put32(b, 106u << 16), Put32(b, 2064), Put32(b, 5), Put32(b, 0), Put32(b, 0), Put32(b, 0);  // no kind 5
  b.resize(b.size() + 2048);
  InboxRecord r;
  u32 off = 0;
  CHECK(NextInboxRecord(b.data(), b.size(), &off, 512, &r) && r.type == InboxRecord::HELD);
  CHECK(r.held.kind == HELD_BLOCK && r.held.sprite == b.data() + 24 && r.held.sprite[2047] == 255);
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
  // Rejected: past its end, a wrong total, not a power of two, too big, mipmaps under 8 texels.
  const u32 bad[][6] = {{256, 128, 4, total, total - 5, 10}, {256, 128, 4, total + 2, 0, 10},
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

int main()
{
  TestInboxRecords();
  TestInboxRejectsBadChunks();
  TestInboxPlanetIdsAndFarView();
  TestPlanetDropAndViewTranslate();
  TestCodePatch();
  TestInboxOutline();
  TestInboxHeld();
  TestInboxEntities();
  TestInboxAtlas();
  TestMul34();
  TestHeldBlock();
  TestHeldFlatItem();
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
