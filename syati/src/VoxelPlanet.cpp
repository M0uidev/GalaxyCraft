// The voxel planet inside SMG2 (docs/superpowers/specs/2026-10-02-galaxycraft-voxel-planets-design.md).
// One actor per scene owns an inbox the host fills with the planet, its chunks (display list +
// KCL, built by the Minecraft mod) and teleports. Each chunk is copied to the heap, drawn with the
// block atlas and given its own collision part; a point gravity pulls toward the center.
#include "syati.h"

#include "Game/Gravity/PointGravity.h"
#include "Game/Map/CollisionParts.h"
#include "Inbox.h"
#include "VoxelPlanet.h"
#include "ViewMath.h"
#include "atlas.h"
#include "galaxycraft_protocol.h"

extern "C" void validateCollisionParts__2MRFP14CollisionParts(CollisionParts*);
extern "C" void invalidateCollisionParts__2MRFP14CollisionParts(CollisionParts*);
extern "C" void* getCollisionDirector__2MRFv();
extern "C" long getCurrentPlacementZoneId__2MRFv();
extern "C" void setCurrentPlacementZoneId__2MRFl(long);
extern "C" void* getSceneHeapGDDR3__2MRFv();
extern "C" void* getSceneHeapNapa__2MRFv();
extern "C" u32 getFreeSize__7JKRHeapFv(void*);
// operator new(size, JKRHeap*, alignment)
extern "C" void* __nw__FUlP7JKRHeapi(u32 size, void* heap, int align);

namespace
{
const u32 INBOX_BYTES = 256 * 1024;
// Mario is put this far above the surface by a teleport (half a block).
const f32 DROP_ABOVE = 40.f;

struct Slot
{
  u32 version;
  u8* dl;  // 32-byte aligned, from the scene's MEM2 heap
  u32 dl_size;
  u8* kcl;
  CollisionParts* parts;
  f32 sphere[4];  // bounding sphere, center relative to the planet's
  u32 drawn;      // index in gDrawn while dl is set
};

// .pa with no fields and one entry: every triangle gets attribute 0 (plain ground).
__attribute__((aligned(32))) u8 gPa[20] = {0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 16, 0, 0, 0, 4, 0, 0, 0, 0};

// One slot per chunk of the planet (GxcPlanet.chunk_count), and the slots that have something to
// draw, so drawing does not walk a big planet's empty chunks.
Slot* gSlots = 0;
u32 gSlotCount = 0;
u32* gDrawn = 0;
u32 gDrawnCount = 0;

// Replaced chunks' memory is freed a few frames later: Mario's binder may still read the last
// triangle it stood on.
const int GRAVE_SLOTS = 64;
const u32 GRAVE_FRAMES = 8;
struct Grave
{
  void* ptr;
  u32 frames;
};
Grave gGraves[GRAVE_SLOTS];

void Bury(void* p)
{
  if (!p)
    return;
  int oldest = 0;
  for (int i = 0; i < GRAVE_SLOTS; i++)
  {
    if (!gGraves[i].ptr)
    {
      oldest = i;
      break;
    }
    if (gGraves[i].frames < gGraves[oldest].frames)
      oldest = i;
  }
  if (gGraves[oldest].ptr)
    operator delete(gGraves[oldest].ptr);
  gGraves[oldest].ptr = p;
  gGraves[oldest].frames = GRAVE_FRAMES;
}

void TickGraves()
{
  for (int i = 0; i < GRAVE_SLOTS; i++)
    if (gGraves[i].ptr && --gGraves[i].frames == 0)
    {
      operator delete(gGraves[i].ptr);
      gGraves[i].ptr = 0;
    }
}
// Mario's hitbox (VoxelPlanetHitbox): lines with a color each, built in turn in two lists.
const int HB_SEGMENTS = 16;
// 2 circles of the cylinder, its 8 sides, 3 probes (2 lines each), 3 balls of 2 circles.
const u32 HB_VERTICES = 2 * HB_SEGMENTS * 2 + 8 * 2 + 3 * 4 + 3 * 2 * HB_SEGMENTS * 2;
const u32 HB_DL_BYTES = (3 + HB_VERTICES * 16 + 31) & ~31u;
// cos and sin of k/16 of a turn.
const f32 HB_COS[HB_SEGMENTS] = {1.f,     0.92388f,  0.70711f,  0.38268f,  0.f,      -0.38268f, -0.70711f, -0.92388f,
                                 -1.f,    -0.92388f, -0.70711f, -0.38268f, 0.f,      0.38268f,  0.70711f,  0.92388f};
MarioHitbox gHitbox;
bool gHitboxOn = false;
u8* gHitboxDl[2] = {0, 0};
u32 gHitboxNext = 0;

f32 HbSin(int k)
{
  return HB_COS[(k + 3 * HB_SEGMENTS / 4) % HB_SEGMENTS];
}

struct HbWriter
{
  u8* p;
  void Vertex(const f32 v[3], u32 rgba)
  {
    memcpy(p, v, 12);
    memcpy(p + 12, &rgba, 4);
    p += 16;
  }
  void Line(const f32 a[3], const f32 b[3], u32 rgba)
  {
    Vertex(a, rgba);
    Vertex(b, rgba);
  }
  // A circle around c in the plane of the unit vectors u and v.
  void Circle(const f32 c[3], const f32 u[3], const f32 v[3], f32 r, u32 rgba)
  {
    for (int k = 0; k < HB_SEGMENTS; k++)
    {
      f32 a[3], b[3];
      const int n = (k + 1) % HB_SEGMENTS;
      for (int i = 0; i < 3; i++)
      {
        a[i] = c[i] + r * (HB_COS[k] * u[i] + HbSin(k) * v[i]);
        b[i] = c[i] + r * (HB_COS[n] * u[i] + HbSin(n) * v[i]);
      }
      Line(a, b, rgba);
    }
  }
};

class VoxelPlanetActor;
VoxelPlanetActor* gActor = 0;
u8* gInbox = 0;
// The atlas copied to 32-byte aligned memory: the GPU drops an address's low 5 bits, and Kamek
// does not keep the array's own alignment.
u8* gAtlas = 0;

// Chunk memory comes from the scene's MEM2 heap (the larger one), 32-byte aligned for the GPU.
// A failed JKRHeap allocation can stop the game, so this leaves the game a reserve instead.
const u32 HEAP_RESERVE = 2 * 1024 * 1024;

u8* Alloc32(u32 size)
{
  void* heap = getSceneHeapGDDR3__2MRFv();
  return getFreeSize__7JKRHeapFv(heap) > size + HEAP_RESERVE ?
             static_cast<u8*>(__nw__FUlP7JKRHeapi(size, heap, 32)) :
             0;
}

// The Map keeper's zone 0, where the planet's collision goes (see GalaxyCraft.cpp for the layout:
// director +0x14 keepers, keeper +0x20 zones). Null if the stage has none: then no collision,
// as CollisionParts::init would read through it.
bool MainZoneReady()
{
  const u32 director = reinterpret_cast<u32>(getCollisionDirector__2MRFv());
  if (!director)
    return false;
  const u32 keepers = *reinterpret_cast<const u32*>(director + 0x14);
  if (!keepers)
    return false;
  const u32 keeper = *reinterpret_cast<const u32*>(keepers);
  return keeper && *reinterpret_cast<const u32*>(keeper + 0x20) != 0;
}

void Identity(TPos3f* m, const f32 t[3])
{
  for (int r = 0; r < 3; r++)
    for (int c = 0; c < 4; c++)
      m->mMtx[r][c] = (r == c) ? 1.f : 0.f;
  m->mMtx[0][3] = t[0], m->mMtx[1][3] = t[1], m->mMtx[2][3] = t[2];
}

class VoxelPlanetActor : public LiveActor
{
public:
  VoxelPlanetActor()
      : LiveActor("GxcVoxelPlanet"), mGravity(0), mPlanet(0), mParts(0), mOutlineOn(false), mOutlineNext(0),
        mOutlineDraw(0)
  {
    mOutlineDl[0] = mOutlineDl[1] = 0;
    mCenter[0] = mCenter[1] = mCenter[2] = 0.f;
    mSurface = mOccluder = mMarioRadius = 0.f;
  }

  virtual void init(const JMapInfoIter&)
  {
    initHitSensor(1);
    MR::addHitSensorMapObj(this, "body", 8, 0.f, TVec3f(0.f, 0.f, 0.f));
    MR::connectToScene(this, 0x21, -1, -1, 0x0E);  // MovementType_MapObj, DrawType_ElectricRail
    MR::invalidateClipping(this);
    mGravity = new PointGravity();
    mGravity->mRange = 1.f;  // off until a planet arrives
    mGravity->mPriority = 100;
    mGravity->updateIdentityMtx();
    MR::registerGravity(mGravity);
    makeActorAppeared();
  }

  void Apply(const gxc::InboxRecord& r)
  {
    gVoxelStats.records++;
    if (r.type == gxc::InboxRecord::PLANET)
    {
      if (r.planet.id != mPlanet || r.planet.chunk_count != gSlotCount)
        NewSlots(r.planet.id ? r.planet.chunk_count : 0);
      mPlanet = r.planet.id;
      for (int k = 0; k < 3; k++)
        mCenter[k] = r.planet.center[k];
      mSurface = r.planet.surface;
      mOccluder = r.planet.occluder;
      mMarioRadius = r.planet.mario_radius;
      mGravity->mLocalPos = TVec3f(mCenter[0], mCenter[1], mCenter[2]);
      mGravity->mRange = mPlanet ? r.planet.gravity_range : 1.f;
      mGravity->updateIdentityMtx();
      mTranslation = mGravity->mLocalPos;
    }
    else if (r.type == gxc::InboxRecord::CHUNK && mPlanet && r.chunk.slot < gSlotCount)
    {
      Replace(r.chunk);
    }
    else if (r.type == gxc::InboxRecord::OUTLINE)
    {
      SetOutline(r.outline);
    }
    else if (r.type == gxc::InboxRecord::TELEPORT && mPlanet)
    {
      const TVec3f* mario = MR::getPlayerPos();
      const f32 m[3] = {mario->x, mario->y, mario->z};
      f32 to[3];
      gxc::PlanetDrop(mCenter, mSurface, DROP_ABOVE, m, to);
      MR::setPlayerPos(TVec3f(to[0], to[1], to[2]));
    }
  }

  bool MarioRadius(const f32 pos[3], f32* radius) const
  {
    if (!mPlanet || mMarioRadius <= 0.f)
      return false;
    const f32 d[3] = {pos[0] - mCenter[0], pos[1] - mCenter[1], pos[2] - mCenter[2]};
    const f32 range = mGravity->mRange;
    const f32 dist2 = d[0] * d[0] + d[1] * d[1] + d[2] * d[2];
    if (dist2 > range * range)
      return false;
    // The cells narrow toward the center (a cell at 3/4 of the radius is 3/4 as wide): so does he.
    const f32 dist = gxc::Sqrt(dist2);
    *radius = dist < mSurface ? mMarioRadius * dist / mSurface : mMarioRadius;
    return true;
  }

  // The outline's 12 edges as a display list of lines (vertex format 6: f32 positions from the
  // planet's center). Two, used in turn: the GPU may still be reading last frame's.
  void SetOutline(const gxc::InboxOutline& o)
  {
    mOutlineOn = false;
    if (!o.visible)
      return;
    u8*& dl = mOutlineDl[mOutlineNext];
    if (!dl)
      dl = Alloc32(OUTLINE_DL_BYTES);
    if (!dl)
      return;
    memset(dl, 0, OUTLINE_DL_BYTES);  // the padding: GX_NOP
    dl[0] = GX_LINES | GX_VTXFMT6;
    dl[1] = 0, dl[2] = 24;
    f32* v = reinterpret_cast<f32*>(dl + 3);  // GX takes unaligned vertex data from a list
    for (int m = 0; m < 8; m++)
      for (int bit = 1; bit < 8; bit <<= 1)
        if (!(m & bit))
          for (int end = 0; end < 2; end++)
          {
            const f32* c = o.corners[end ? (m | bit) : m];
            memcpy(v, c, 12);
            v += 3;
          }
    DCFlushRange(dl, OUTLINE_DL_BYTES);
    mOutlineDraw = dl;
    mOutlineNext ^= 1;
    mOutlineOn = true;
  }

  void Replace(const gxc::InboxChunk& c)
  {
    Slot& s = gSlots[c.slot];
    gVoxelStats.last_slot = c.slot;
    gVoxelStats.last_version = c.version;
    if (c.version <= s.version)
      return;
    Free(s);
    s.version = c.version;
    for (int k = 0; k < 4; k++)
      s.sphere[k] = c.sphere[k];
    if (c.dl_size == 0)
      return;
    u8* kcl = 0;
    u8* dl = Alloc32(c.dl_size);
    if (dl && c.kcl_size)
      kcl = Alloc32(c.kcl_size);
    if (!dl || (c.kcl_size && !kcl))
    {
      gVoxelStats.alloc_failed++;
      Bury(dl);
      return;
    }
    memcpy(dl, c.dl, c.dl_size);
    DCFlushRange(dl, c.dl_size);
    s.dl = dl;
    s.dl_size = c.dl_size;
    s.drawn = gDrawnCount;
    gDrawn[gDrawnCount++] = c.slot;
    if (kcl && !MainZoneReady())
    {
      gVoxelStats.no_zone++;
      Bury(kcl);
      kcl = 0;
    }
    if (kcl)
    {
      memcpy(kcl, c.kcl, c.kcl_size);
      s.kcl = kcl;
      TPos3f m;
      Identity(&m, mCenter);
      s.parts = new CollisionParts();
      // init files the part under the zone being placed, which outside of a stage's placement is
      // stale (a zone with no collision: null); the planet belongs to the main zone.
      const long zone = getCurrentPlacementZoneId__2MRFv();
      setCurrentPlacementZoneId__2MRFl(0);
      s.parts->init(m, getSensor("body"), s.kcl, gPa, 0, false);
      setCurrentPlacementZoneId__2MRFl(zone);
      validateCollisionParts__2MRFP14CollisionParts(s.parts);
      gVoxelStats.parts_made++;
      mParts++;
    }
    gVoxelStats.chunks = gDrawnCount;
    gVoxelStats.parts_live = mParts;
  }

  // The old collision part leaves every zone and stays allocated (a few hundred bytes); its KCL
  // and display list are freed a few frames later.
  void Free(Slot& s)
  {
    if (s.parts)
    {
      invalidateCollisionParts__2MRFP14CollisionParts(s.parts);
      mParts--;
    }
    if (s.dl)
    {
      const u32 last = gDrawn[--gDrawnCount];
      gDrawn[s.drawn] = last;
      gSlots[last].drawn = s.drawn;
    }
    Bury(s.dl);
    Bury(s.kcl);
    s.dl = s.kcl = 0;
    s.parts = 0;
    s.dl_size = 0;
  }

  void NewSlots(u32 count)
  {
    for (u32 i = 0; i < gSlotCount; i++)
      Free(gSlots[i]);
    if (gSlots)
      operator delete(gSlots);
    if (gDrawn)
      operator delete(gDrawn);
    gSlots = 0;
    gDrawn = 0;
    gSlotCount = gDrawnCount = 0;
    if (count == 0)
      return;
    gSlots = reinterpret_cast<Slot*>(Alloc32(count * sizeof(Slot)));
    gDrawn = reinterpret_cast<u32*>(Alloc32(count * sizeof(u32)));
    if (!gSlots || !gDrawn)
    {
      gVoxelStats.alloc_failed++;
      Bury(gSlots);
      Bury(gDrawn);
      gSlots = 0;
      gDrawn = 0;
      return;
    }
    memset(gSlots, 0, count * sizeof(Slot));
    gSlotCount = count;
  }

  virtual void draw() const
  {
    if (!mPlanet)
      return;
    if (gDrawnCount == 0)
    {
      if (gHitboxOn)
        DrawHitbox();
      return;
    }
    GXClearVtxDesc();
    GXSetVtxDesc(GX_VA_POS, GX_DIRECT);
    GXSetVtxDesc(GX_VA_CLR0, GX_DIRECT);
    GXSetVtxDesc(GX_VA_TEX0, GX_DIRECT);
    // Positions: s16 with 3 fraction bits from the chunk's center (PlanetMesher), color RGB565.
    GXSetVtxAttrFmt(GX_VTXFMT7, GX_VA_POS, GX_POS_XYZ, GX_S16, 3);
    GXSetVtxAttrFmt(GX_VTXFMT7, GX_VA_CLR0, GX_CLR_RGB, GX_RGB565, 0);
    GXSetVtxAttrFmt(GX_VTXFMT7, GX_VA_TEX0, GX_TEX_ST, GX_U16, 10);
    GXSetNumChans(1);
    GXSetChanCtrl(GX_COLOR0A0, GX_FALSE, GX_SRC_REG, GX_SRC_VTX, 0, GX_DF_NONE, GX_AF_NONE);
    GXSetNumTexGens(1);
    GXSetTexCoordGen2(GX_TEXCOORD0, GX_TG_MTX2x4, GX_TG_TEX0, GX_IDENTITY, GX_FALSE, GX_PTIDENTITY);
    GXTexObj tex;
    // Crisp texels up close (nearest), mipmaps blended far away: no shimmering grass in the distance.
    GXInitTexObj(&tex, gAtlas, GXC_ATLAS_SIZE, GXC_ATLAS_SIZE, GX_TF_RGB565, GX_CLAMP, GX_CLAMP, GX_TRUE);
    GXInitTexObjLOD(&tex, GX_NEAR_MIP_LIN, GX_NEAR, 0.f, static_cast<f32>(GXC_ATLAS_LEVELS - 1), 0.f, GX_FALSE,
                    GX_TRUE, GX_ANISO_1);
    GXLoadTexObj(&tex, GX_TEXMAP0);
    GXSetNumIndStages(0);
    GXSetTevDirect(GX_TEVSTAGE0);
    GXSetNumTevStages(1);
    GXSetTevSwapMode(GX_TEVSTAGE0, GX_TEV_SWAP0, GX_TEV_SWAP0);
    GXSetTevSwapModeTable(GX_TEV_SWAP0, GX_CH_RED, GX_CH_GREEN, GX_CH_BLUE, GX_CH_ALPHA);
    GXSetTevOrder(GX_TEVSTAGE0, GX_TEXCOORD0, GX_TEXMAP0, GX_COLOR0A0);
    GXSetTevOp(GX_TEVSTAGE0, GX_MODULATE);
    GXColor black = {0, 0, 0, 0};
    GXSetFog(GX_FOG_NONE, 0.f, 0.f, 0.f, 0.f, black);
    GXSetZMode(GX_TRUE, GX_LEQUAL, GX_TRUE);
    GXSetZCompLoc(GX_TRUE);
    GXSetBlendMode(GX_BM_NONE, GX_BL_ONE, GX_BL_ZERO, GX_LO_NOOP);
    GXSetAlphaCompare(GX_ALWAYS, 0, GX_AOP_AND, GX_ALWAYS, 0);
    GXSetCullMode(GX_CULL_FRONT);  // the mesh is counter-clockwise seen from outside
    GXSetColorUpdate(GX_TRUE);
    GXSetAlphaUpdate(GX_FALSE);
    GXSetDstAlpha(GX_FALSE, 0);

    f32 view[12];
    const MtxPtr cam = MR::getCameraViewMtx();
    for (int r = 0; r < 3; r++)
      for (int c = 0; c < 4; c++)
        view[4 * r + c] = cam[r][c];
    GXSetCurrentMtx(GX_PNMTX0);

    // The camera in the planet's frame: chunks behind it or past the horizon are skipped. The
    // bedrock shell (unbreakable) is the ball that hides them.
    // From the view matrix this frame draws with (in first person GalaxyCraft's, not the game
    // camera's): position -Rᵀt, forward -(third row), as GX cameras look down -z.
    f32 eye[3], fwd[3];
    gxc::ViewEye(view, eye, fwd);
    eye[0] -= mCenter[0], eye[1] -= mCenter[1], eye[2] -= mCenter[2];
    const f32 origin[3] = {0.f, 0.f, 0.f};
    u32 drawn = 0;
    for (u32 i = 0; i < gDrawnCount; i++)
    {
      const Slot& s = gSlots[gDrawn[i]];
      if (gxc::SphereHidden(eye, fwd, origin, mOccluder, s.sphere, s.sphere[3]))
        continue;
      // Each chunk's vertices are relative to its own center.
      const f32 at[3] = {mCenter[0] + s.sphere[0], mCenter[1] + s.sphere[1], mCenter[2] + s.sphere[2]};
      f32 pos[12];
      gxc::ViewTranslate(view, at, pos);
      GXLoadPosMtxImm(reinterpret_cast<f32(*)[4]>(pos), GX_PNMTX0);
      GXCallDisplayList(s.dl, s.dl_size);
      drawn++;
    }
    gVoxelStats.drawn_last = drawn;
    if (mOutlineOn)
      DrawOutline(view);
    if (gHitboxOn)
      DrawHitbox();
  }

  // Mario's hitbox (gHitbox), seen through the blocks: what he collides with is what he touches.
  void DrawHitbox() const
  {
    u8*& dl = gHitboxDl[gHitboxNext];
    if (!dl)
      dl = Alloc32(HB_DL_BYTES);
    if (!dl)
      return;
    gHitboxNext ^= 1;
    const MarioHitbox& h = gHitbox;
    // A frame around his up: s toward where he faces, t beside it.
    f32 s[3], t[3];
    const f32 along = h.front[0] * h.up[0] + h.front[1] * h.up[1] + h.front[2] * h.up[2];
    for (int i = 0; i < 3; i++)
      s[i] = h.front[i] - along * h.up[i];
    f32 len = gxc::Sqrt(s[0] * s[0] + s[1] * s[1] + s[2] * s[2]);
    if (len < 1e-3f)
    {
      // Facing straight up or down: any direction across up will do.
      const bool upx = h.up[0] > 0.9f || h.up[0] < -0.9f;
      const f32 x[3] = {upx ? 0.f : 1.f, upx ? 1.f : 0.f, 0.f};
      const f32 a = x[0] * h.up[0] + x[1] * h.up[1];
      for (int i = 0; i < 3; i++)
        s[i] = x[i] - a * h.up[i];
      len = gxc::Sqrt(s[0] * s[0] + s[1] * s[1] + s[2] * s[2]);
    }
    for (int i = 0; i < 3; i++)
      s[i] /= len;
    t[0] = h.up[1] * s[2] - h.up[2] * s[1];
    t[1] = h.up[2] * s[0] - h.up[0] * s[2];
    t[2] = h.up[0] * s[1] - h.up[1] * s[0];

    memset(dl, 0, HB_DL_BYTES);
    dl[0] = GX_LINES | GX_VTXFMT5;
    dl[1] = static_cast<u8>(HB_VERTICES >> 8), dl[2] = static_cast<u8>(HB_VERTICES);
    HbWriter w = {dl + 3};
    const u32 RED = 0xFF3030E0, YELLOW = 0xFFE020F0, BLUE = 0x40A0FFC0;
    // Up to the top of the highest ball.
    f32 height = 0.f;
    for (int b = 0; b < 3; b++)
    {
      const f32 top = (h.balls[b][0] - h.feet[0]) * h.up[0] + (h.balls[b][1] - h.feet[1]) * h.up[1] +
                      (h.balls[b][2] - h.feet[2]) * h.up[2] + h.radius;
      if (top > height)
        height = top;
    }
    for (int c = 0; c < 2; c++)
    {
      f32 at[3];
      for (int i = 0; i < 3; i++)
        at[i] = h.feet[i] + (c ? height : 0.f) * h.up[i];
      w.Circle(at, s, t, h.radius, RED);
    }
    for (int k = 0; k < HB_SEGMENTS; k += 2)
    {
      f32 a[3], b[3];
      for (int i = 0; i < 3; i++)
      {
        a[i] = h.feet[i] + h.radius * (HB_COS[k] * s[i] + HbSin(k) * t[i]);
        b[i] = a[i] + height * h.up[i];
      }
      w.Line(a, b, RED);
    }
    // The probes, from his feet out to where each feels the ground, and a tick down there.
    for (int p = 0; p < 3; p++)
    {
      const int k = (p * HB_SEGMENTS + 1) / 3;  // 0, 120 and 240 degrees (to the nearest 1/16)
      f32 a[3], b[3];
      for (int i = 0; i < 3; i++)
      {
        a[i] = h.feet[i] + h.radius * (HB_COS[k] * s[i] + HbSin(k) * t[i]);
        b[i] = a[i] - 0.25f * h.radius * h.up[i];
      }
      w.Line(h.feet, a, YELLOW);
      w.Line(a, b, YELLOW);
    }
    for (int b = 0; b < 3; b++)
    {
      w.Circle(h.balls[b], s, t, h.radius, BLUE);
      w.Circle(h.balls[b], s, h.up, h.radius, BLUE);
    }
    DCFlushRange(dl, HB_DL_BYTES);

    GXClearVtxDesc();
    GXSetVtxDesc(GX_VA_POS, GX_DIRECT);
    GXSetVtxDesc(GX_VA_CLR0, GX_DIRECT);
    GXSetVtxAttrFmt(GX_VTXFMT5, GX_VA_POS, GX_POS_XYZ, GX_F32, 0);
    GXSetVtxAttrFmt(GX_VTXFMT5, GX_VA_CLR0, GX_CLR_RGBA, GX_RGBA8, 0);
    GXSetNumChans(1);
    GXSetChanCtrl(GX_COLOR0A0, GX_FALSE, GX_SRC_REG, GX_SRC_VTX, 0, GX_DF_NONE, GX_AF_NONE);
    GXSetNumTexGens(0);
    GXSetNumTevStages(1);
    GXSetTevOrder(GX_TEVSTAGE0, GX_TEXCOORD_NULL, GX_TEXMAP_NULL, GX_COLOR0A0);
    GXSetTevOp(GX_TEVSTAGE0, GX_PASSCLR);
    GXSetBlendMode(GX_BM_BLEND, GX_BL_SRCALPHA, GX_BL_INVSRCALPHA, GX_LO_NOOP);
    GXSetAlphaCompare(GX_ALWAYS, 0, GX_AOP_AND, GX_ALWAYS, 0);
    GXSetZMode(GX_FALSE, GX_ALWAYS, GX_FALSE);
    GXSetCullMode(GX_CULL_NONE);
    GXSetLineWidth(12, GX_TO_ZERO);
    f32 view[12];
    const MtxPtr cam = MR::getCameraViewMtx();
    for (int r = 0; r < 3; r++)
      for (int c = 0; c < 4; c++)
        view[4 * r + c] = cam[r][c];
    GXSetCurrentMtx(GX_PNMTX0);
    GXLoadPosMtxImm(reinterpret_cast<f32(*)[4]>(view), GX_PNMTX0);
    GXCallDisplayList(dl, HB_DL_BYTES);
  }

  // Minecraft's block outline: thin translucent black lines, hidden behind what is in front.
  void DrawOutline(const f32 view[12]) const
  {
    GXClearVtxDesc();
    GXSetVtxDesc(GX_VA_POS, GX_DIRECT);
    GXSetVtxAttrFmt(GX_VTXFMT6, GX_VA_POS, GX_POS_XYZ, GX_F32, 0);
    GXSetNumChans(1);
    GXSetChanCtrl(GX_COLOR0A0, GX_FALSE, GX_SRC_REG, GX_SRC_REG, 0, GX_DF_NONE, GX_AF_NONE);
    const GXColor color = {0, 0, 0, 110};
    GXSetChanMatColor(GX_COLOR0A0, color);
    GXSetNumTexGens(0);
    GXSetTevOrder(GX_TEVSTAGE0, GX_TEXCOORD_NULL, GX_TEXMAP_NULL, GX_COLOR0A0);
    GXSetTevOp(GX_TEVSTAGE0, GX_PASSCLR);
    GXSetBlendMode(GX_BM_BLEND, GX_BL_SRCALPHA, GX_BL_INVSRCALPHA, GX_LO_NOOP);
    GXSetZMode(GX_TRUE, GX_LEQUAL, GX_FALSE);
    GXSetCullMode(GX_CULL_NONE);
    GXSetLineWidth(12, GX_TO_ZERO);  // sixths of a pixel
    f32 pos[12];
    gxc::ViewTranslate(view, mCenter, pos);
    GXLoadPosMtxImm(reinterpret_cast<f32(*)[4]>(pos), GX_PNMTX0);
    GXCallDisplayList(mOutlineDraw, OUTLINE_DL_BYTES);
  }

  PointGravity* mGravity;
  u32 mPlanet;
  u32 mParts;
  f32 mCenter[3];
  f32 mSurface;
  f32 mOccluder;
  f32 mMarioRadius;
  static const u32 OUTLINE_DL_BYTES = 320;  // 3 + 24 * 12, padded to 32
  bool mOutlineOn;
  u32 mOutlineNext;
  u8* mOutlineDl[2];
  u8* mOutlineDraw;
};
}  // namespace

VoxelStats gVoxelStats;

void VoxelPlanetCreate()
{
  // A new scene: the old one's heap (chunks, parts, inbox) is gone, so forget it, don't free it.
  gSlots = 0;
  gDrawn = 0;
  gSlotCount = gDrawnCount = 0;
  gHitboxDl[0] = gHitboxDl[1] = 0;
  gHitboxOn = false;
  memset(gGraves, 0, sizeof(gGraves));
  gInbox = static_cast<u8*>(operator new(INBOX_BYTES));
  gAtlas = Alloc32(sizeof(gVoxelAtlas));
  memcpy(gAtlas, gVoxelAtlas, sizeof(gVoxelAtlas));
  DCFlushRange(gAtlas, sizeof(gVoxelAtlas));
  memset(gInbox, 0, sizeof(GxcInboxHeader));
  gActor = new VoxelPlanetActor();
  gActor->initWithoutIter();
}

void VoxelPlanetHitbox(const MarioHitbox* box)
{
  gHitboxOn = box != 0;
  if (box)
    gHitbox = *box;
}

bool VoxelPlanetMarioRadius(const float pos[3], float* radius)
{
  return gActor && gActor->MarioRadius(pos, radius);
}

void VoxelPlanetFrame(uint32_t scene_id, uint32_t* inbox_addr, uint32_t* inbox_size)
{
  *inbox_addr = reinterpret_cast<u32>(gInbox);
  *inbox_size = gInbox ? INBOX_BYTES : 0;
  if (!gActor || !gInbox)
    return;
  TickGraves();
  GxcInboxHeader* h = reinterpret_cast<GxcInboxHeader*>(gInbox);
  if (h->state != 1)
    return;
  gVoxelStats.batches++;
  gVoxelStats.free_mem2 = getFreeSize__7JKRHeapFv(getSceneHeapGDDR3__2MRFv());
  gVoxelStats.free_mem1 = getFreeSize__7JKRHeapFv(getSceneHeapNapa__2MRFv());
  if (h->scene_id == scene_id && h->bytes <= INBOX_BYTES - sizeof(GxcInboxHeader))
  {
    const u8* rec = gInbox + sizeof(GxcInboxHeader);
    u32 off = 0;
    gxc::InboxRecord r;
    for (u32 n = 0; n < h->count && gxc::NextInboxRecord(rec, h->bytes, &off, GXC_PLANET_MAX_CHUNKS, &r); n++)
      gActor->Apply(r);
  }
  h->state = 0;
}
