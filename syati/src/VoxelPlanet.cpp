// The voxel planet inside SMG2 (docs/superpowers/specs/2026-10-02-galaxycraft-voxel-planets-design.md).
// One actor per scene owns an inbox the host fills with the planet, its chunks (display list +
// KCL, built by the Minecraft mod), the block atlas (in pieces) and teleports. Each chunk is copied
// to the heap, drawn with the atlas and given its own collision part; a point gravity pulls toward
// the center.
#include "syati.h"

#include "Game/Gravity/PointGravity.h"
#include "Game/Map/CollisionParts.h"
#include "EntityDraw.h"
#include "AtlasAnim.h"
#include "CrackMesh.h"
#include "Graves.h"
#include "HeldItem.h"
#include "Inbox.h"
#include "VoxelPlanet.h"

#include "Boot.h"
#include "ViewMath.h"
#include "galaxycraft_protocol.h"

extern "C" void validateCollisionParts__2MRFP14CollisionParts(CollisionParts*);
extern "C" void invalidateCollisionParts__2MRFP14CollisionParts(CollisionParts*);
extern "C" void* getCollisionDirector__2MRFv();
extern "C" long getCurrentPlacementZoneId__2MRFv();
extern "C" void* getZone__26CollisionCategorizedKeeperFi(void* keeper, int zone);
extern "C" void setCurrentPlacementZoneId__2MRFl(long);
extern "C" void* getSceneHeapGDDR3__2MRFv();
extern "C" void* getSceneHeapNapa__2MRFv();
extern "C" u32 getFreeSize__7JKRHeapFv(void*);
extern "C" u32 getTotalFreeSize__7JKRHeapFv(void*);
extern "C" s32 getSize__7JKRHeapFPv(void* heap, void* p);
extern "C" void free__7JKRHeapFPvP7JKRHeap(void* p, void* heap);
extern "C" void* create__10JKRExpHeapFUlP7JKRHeapb(u32 size, void* parent, bool errorFlag);
extern "C" void GXGetProjectionv(f32* p);
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
  u32 solid_size;  // bytes of dl drawn opaque; the rest (water) is drawn after every planet, blended
  u8* kcl;
  CollisionParts* parts;
  f32 sphere[4];  // bounding sphere, center relative to the planet's
  u32 drawn;      // index in gDrawn while dl is set
};

// .pa with no fields and one entry: every triangle gets attribute 0 (plain ground).
__attribute__((aligned(32))) u8 gPa[20] = {0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 16, 0, 0, 0, 4, 0, 0, 0, 0};

// A part of a planet's far view (one tile of a face of its cube, PlanetLod.tile): the mod sends
// each tile either as its chunks (near Mario, its render distance) or as this, so both are drawn;
// a tile of chunks has its part too, covered: drawn instead of them only from afar.
struct FarPart
{
  u32 version;
  u8* dl;  // 32-byte aligned, positions in whole units from the planet's center
  u32 dl_size;
  f32 sphere[4];
  bool covered;  // its tile is chunks: drawn only from afar
};

// The planets of the scene, by the mod's id (0: an unused entry). Each has one slot per chunk
// (GxcPlanet.chunk_count, 0 if it comes only as its far view) and the slots that have something to
// draw, so drawing does not walk a big planet's empty chunks.
// A galaxy's catalog has up to 64 planets: the 8 nearest Mario complete (gravity, chunks or their
// far view), the others drawn only (gravity_range 0: no gravity, chunk_count 0: no chunks). The
// first GRAVITY_SLOTS entries have a gravity of the game's and take the complete ones; the rest
// only draw, so far planets never leave a complete one without room (and SMG2 gets 16 gravities
// of ours, not one per entry).
const u32 GRAVITY_SLOTS = 16;
const u32 MAX_PLANETS = 96;
struct Planet
{
  u32 id;
  f32 center[3];
  f32 surface, occluder, mario_radius;
  PointGravity* gravity;
  Slot* slots;
  u32 slot_count;
  u32* drawn;
  u32 drawn_count;
  u32 parts;    // collision parts alive
  FarPart* far;  // far_count of them, from the module's heap with the first one (0: none yet)
  u32 far_count;  // 6 for a planet drawn only (its whole faces), FAR_VIEW_PARTS once it has tiles
};
Planet gPlanets[MAX_PLANETS];
// The camera is this far above a planet's surface (or its radius, if more), galaxy units, or
// farther: its far view alone is drawn, covered parts and all, not its chunks.
const f32 FAR_VIEW_ABOVE = 96.f * 80.f;

// Replaced chunks' memory is freed a few frames later: Mario's binder may still read the last
// triangle it stood on, the GPU the last display list. A planet streaming in replaces dozens of
// chunks a frame: the graves hold thousands, first in first out.
const u32 GRAVE_FRAMES = 8;
gxc::Graves gGraves;
// A replaced chunk's collision: Mario's binder may keep the triangle he stands on without looking
// again while he stands still (away from the keyboard, then water flows or a block changes next to
// him), and SMG2 reads it every frame: it waits for frames in which he moved.
gxc::Graves gKclGraves;
bool gMarioMoved;

// The module's own heap, in the scene's MEM2 heap. That one is a JKRSolidHeap (read live: its
// vtable is JKRSolidHeap's): it only grows and frees nothing until the scene ends, so every chunk
// sent again (a block broken or placed), every far view and every removed planet stayed until
// then, and a long session ran out. This one frees for real; it is made at the first allocation
// of a scene (the stage is loaded by then) and goes with the scene's heap.
void* gModHeap = 0;
// What the game keeps of the scene's MEM2 heap: an eighth of what is free then, 8 MB at least.
const u32 GAME_KEEPS_MIN = 8 * 1024 * 1024;

void* ModHeap()
{
  if (gModHeap)
    return gModHeap;
  void* scene = getSceneHeapGDDR3__2MRFv();
  const u32 free = getFreeSize__7JKRHeapFv(scene);
  const u32 keep = free / 8 > GAME_KEEPS_MIN ? free / 8 : GAME_KEEPS_MIN;
  if (free <= keep + 1024 * 1024)
    return 0;
  gModHeap = create__10JKRExpHeapFUlP7JKRHeapb((free - keep) & ~31u, scene, false);
  return gModHeap;
}

void FreeHeap(void* p)
{
  void* heap = gModHeap;
  if (!p || !heap)
    return;
  const s32 size = getSize__7JKRHeapFPv(heap, p);
  if (size > 0)
    gVoxelStats.module_bytes -= size;
  free__7JKRHeapFPvP7JKRHeap(p, heap);
}

void Bury(void* p)
{
  gGraves.Bury(p, GRAVE_FRAMES, FreeHeap);
}

void BuryKcl(void* p)
{
  gKclGraves.Bury(p, GRAVE_FRAMES, FreeHeap);
}

void TickGraves()
{
  gGraves.Tick(FreeHeap);
  if (gMarioMoved)
    gKclGraves.Tick(FreeHeap);
  gMarioMoved = false;
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
// The light of full sky light now (GXC_MSG_SKY): white by day, dim and bluish at night.
GXColor gSky = {255, 255, 255, 255};
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
// The block atlas (GXC_MSG_ATLAS): GX RGB5A3 with its mipmaps, put together from the pieces of
// one id in 32-byte aligned memory (the GPU drops an address's low 5 bits). Drawn with once all
// its bytes are in; pieces are sent in order, so a count of the bytes is enough.
struct Atlas
{
  u8* tex;
  u32 id, width, height, levels, total, have;
  bool ready;
  gxc::AtlasAnim anim;  // its animated tiles (after the texels), loaded once it is all in
  u32 frame;            // frames drawn since, to step them
};
Atlas gAtlas;

u8* Alloc32(u32 size);

void AtlasPiece(const gxc::InboxAtlas& a)
{
  if (!gAtlas.tex || a.id != gAtlas.id || a.width != gAtlas.width || a.height != gAtlas.height ||
      a.levels != gAtlas.levels)
  {
    // A new atlas: the old one's memory is reused if it is big enough (one per scene, mostly).
    if (gAtlas.tex && gAtlas.total < a.total)
    {
      Bury(gAtlas.tex);
      gAtlas.tex = 0;
    }
    if (!gAtlas.tex)
      gAtlas.tex = Alloc32(a.total);
    gAtlas.id = a.id, gAtlas.width = a.width, gAtlas.height = a.height, gAtlas.levels = a.levels;
    gAtlas.total = a.total;
    gAtlas.have = 0;
    gAtlas.ready = false;
    if (!gAtlas.tex)
    {
      gVoxelStats.alloc_failed++;
      return;
    }
  }
  // The mod sends an atlas from offset 0 up, again in each scene: pieces left over from the last
  // scene's sending do not count toward this one.
  if (a.offset == 0)
  {
    gAtlas.have = 0;
    gAtlas.ready = false;
  }
  memcpy(gAtlas.tex + a.offset, a.data, a.size);
  gAtlas.have += a.size;
  if (!gAtlas.ready && gAtlas.have >= gAtlas.total)
  {
    gAtlas.anim.Load(gAtlas.tex, gAtlas.total, gxc::AtlasBytes(gAtlas.width, gAtlas.height, gAtlas.levels), gAtlas.width,
                     gAtlas.height, gAtlas.levels);
    gAtlas.frame = 0;
    DCFlushRange(gAtlas.tex, gAtlas.total);
    gAtlas.ready = true;
  }
  gVoxelStats.atlas_id = gAtlas.id;
  gVoxelStats.atlas_bytes = gAtlas.have;
  gVoxelStats.atlas_ready = gAtlas.ready ? 1 : 0;
}

// Chunk memory comes from the module's heap (ModHeap, in the scene's MEM2 heap), 32-byte aligned
// for the GPU. A failed JKRHeap allocation can stop the game, so a request it may not meet gets 0.
const u32 HEAP_RESERVE = 64 * 1024;

u8* Alloc32(u32 size)
{
  void* heap = ModHeap();
  u8* p = heap && getFreeSize__7JKRHeapFv(heap) > size + HEAP_RESERVE ?
              static_cast<u8*>(__nw__FUlP7JKRHeapi(size, heap, 32)) :
              0;
  if (p)
  {
    const s32 got = getSize__7JKRHeapFPv(heap, p);
    if (got > 0)
      gVoxelStats.module_bytes += got;
  }
  return p;
}

// The Map keeper's zone 0, where the planet's collision goes (see GalaxyCraft.cpp for the layout:
// director +0x14 keepers, keeper +0x20 zones). The keeper makes its zones the first time one is
// asked for (getZone), which a stage with no collision of its own (GalaxyCraftSpace) never does:
// then we ask. False if there is still none: no collision, as CollisionParts::init would read
// through it.
const u32 ZONE_PARTS_FOR_PLANETS = 512 - 32;

bool MainZoneReady()
{
  const u32 director = reinterpret_cast<u32>(getCollisionDirector__2MRFv());
  if (!director)
    return false;
  const u32 keepers = *reinterpret_cast<const u32*>(director + 0x14);
  if (!keepers)
    return false;
  const u32 keeper = *reinterpret_cast<const u32*>(keepers);
  if (!keeper)
    return false;
  if (*reinterpret_cast<const u32*>(keeper + 0x20) == 0)
    getZone__26CollisionCategorizedKeeperFi(reinterpret_cast<void*>(keeper), 0);
  const u32 zone = *reinterpret_cast<const u32*>(keeper + 0x20);
  // A zone holds 512 parts, its count right after them (+0x804): a 513th overwrites the count, and
  // every removal after searches all of memory for its part (the game hangs). The game's own
  // objects add parts too, so ours stop short of it.
  return zone != 0 && *reinterpret_cast<const u32*>(zone + 0x804) < ZONE_PARTS_FOR_PLANETS;
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
      : LiveActor("GxcVoxelPlanet"), mOutlineOn(false), mOutlinePlanet(0), mOutlineNext(0), mOutlineDraw(0),
        mCrackOn(false), mCrackPlanet(0), mCrackNext(0), mCrackDraw(0)
  {
    mOutlineDl[0] = mOutlineDl[1] = 0;
    mCrackDl[0] = mCrackDl[1] = 0;
  }

  virtual void init(const JMapInfoIter&)
  {
    initHitSensor(1);
    MR::addHitSensorMapObj(this, "body", 8, 0.f, TVec3f(0.f, 0.f, 0.f));
    MR::connectToScene(this, 0x21, -1, -1, 0x0E);  // MovementType_MapObj, DrawType_ElectricRail
    MR::invalidateClipping(this);
    for (u32 i = 0; i < GRAVITY_SLOTS; i++)
    {
      PointGravity* g = new PointGravity();
      g->mRange = 1.f;  // off until a planet arrives
      g->mPriority = 100;
      g->updateIdentityMtx();
      MR::registerGravity(g);
      gPlanets[i].gravity = g;
    }
    makeActorAppeared();
  }

  // The planet with this id (0: the first there is), or null.
  static Planet* Find(u32 id)
  {
    for (u32 i = 0; i < MAX_PLANETS; i++)
      if (gPlanets[i].id && (id == 0 || gPlanets[i].id == id))
        return &gPlanets[i];
    return 0;
  }

  void Apply(const gxc::InboxRecord& r)
  {
    gVoxelStats.records++;
    if (r.type == gxc::InboxRecord::PLANET)
    {
      ApplyPlanet(r.planet);
    }
    else if (r.type == gxc::InboxRecord::CHUNK)
    {
      Planet* p = Find(r.chunk.planet);
      if (p && r.chunk.far)
        ReplaceFar(*p, r.chunk);
      else if (p && r.chunk.slot < p->slot_count)
        Replace(*p, r.chunk);
    }
    else if (r.type == gxc::InboxRecord::OUTLINE)
    {
      SetOutline(r.outline);
    }
    else if (r.type == gxc::InboxRecord::CRACK)
    {
      SetCrack(r.crack);
    }
    else if (r.type == gxc::InboxRecord::HELD)
    {
      HeldItemSet(r.held);
    }
    else if (r.type == gxc::InboxRecord::ATLAS)
    {
      AtlasPiece(r.atlas);
    }
    else if (r.type == gxc::InboxRecord::SKIN)
    {
      EntityDrawSkin(r.skin);
    }
    else if (r.type == gxc::InboxRecord::MODEL)
    {
      EntityDrawModel(r.model);
    }
    else if (r.type == gxc::InboxRecord::ENTITIES)
    {
      EntityDrawFrame(r.entities);
    }
    else if (r.type == gxc::InboxRecord::SEAT)
    {
      EntityDrawSeat(r.seat);
    }
    else if (r.type == gxc::InboxRecord::SKY)
    {
      u8 c[3];
      for (int k = 0; k < 3; k++)
      {
        const f32 v = r.sky[k] < 0.f ? 0.f : r.sky[k] > 1.f ? 1.f : r.sky[k];
        c[k] = static_cast<u8>(v * 255.f + 0.5f);
      }
      gSky.r = c[0], gSky.g = c[1], gSky.b = c[2];
    }
    else if (r.type == gxc::InboxRecord::HURT)
    {
      EntityDrawHurt(r.hurt);
    }
    else if (r.type == gxc::InboxRecord::TELEPORT)
    {
      const Planet* p = Find(r.teleport.planet);
      if (!p)
        return;
      const TVec3f* mario = MR::getPlayerPos();
      f32 m[3] = {mario->x, mario->y, mario->z};
      if (r.teleport.aimed)  // where the mod measured the ground, not above wherever Mario is now
        for (int k = 0; k < 3; k++)
          m[k] = p->center[k] + 100.f * r.teleport.dir[k];
      f32 to[3];
      // Onto the ground under him (a hill, something built), not into it.
      gxc::PlanetDrop(p->center, r.teleport.ground > 0.f ? r.teleport.ground : p->surface, DROP_ABOVE, m, to);
      MR::setPlayerPos(TVec3f(to[0], to[1], to[2]));
      BootTeleported();
    }
  }

  // id 0 drops every planet; GONE drops that one; otherwise it is new or changed (a chunk_count of
  // 0 keeps only its far view).
  void ApplyPlanet(const gxc::InboxPlanet& in)
  {
    if (in.id == 0 || (in.flags & gxc::PLANET_GONE))
    {
      if (in.id == 0)
        BootPlanetsDropped();
      for (u32 i = 0; i < MAX_PLANETS; i++)
        if (gPlanets[i].id && (in.id == 0 || gPlanets[i].id == in.id))
          Drop(gPlanets[i]);
      return;
    }
    const bool pulls = in.gravity_range > 1.f;
    Planet* p = Find(in.id);
    if (p && pulls && !p->gravity)  // drawn only until now, and it pulls from now on: into a slot that can
    {
      Drop(*p);
      p = 0;
    }
    // A planet that only draws goes past the gravity slots first, into one of them only if those are full.
    for (u32 i = pulls ? 0 : GRAVITY_SLOTS; !p && i < MAX_PLANETS; i++)
      if (!gPlanets[i].id)
        p = &gPlanets[i];
    for (u32 i = 0; !p && !pulls && i < GRAVITY_SLOTS; i++)
      if (!gPlanets[i].id)
        p = &gPlanets[i];
    if (pulls && p && !p->gravity)
      p = 0;
    if (!p)
    {
      gVoxelStats.alloc_failed++;
      return;
    }
    p->id = in.id;
    if (in.chunk_count != p->slot_count)
      NewSlots(*p, in.chunk_count);
    for (int k = 0; k < 3; k++)
      p->center[k] = in.center[k];
    p->surface = in.surface;
    p->occluder = in.occluder;
    p->mario_radius = in.mario_radius;
    if (p->gravity)
    {
      p->gravity->mLocalPos = TVec3f(p->center[0], p->center[1], p->center[2]);
      p->gravity->mRange = pulls ? in.gravity_range : 1.f;  // 1: off, as an unused entry's
      p->gravity->updateIdentityMtx();
    }
    mTranslation = TVec3f(p->center[0], p->center[1], p->center[2]);
  }

  void Drop(Planet& p)
  {
    NewSlots(p, 0);
    if (p.far)
    {
      for (u32 f = 0; f < p.far_count; f++)
        Bury(p.far[f].dl);
      Bury(p.far);
      p.far = 0;
      p.far_count = 0;
    }
    if (p.gravity)
    {
      p.gravity->mRange = 1.f;
      p.gravity->updateIdentityMtx();
    }
    if (mOutlinePlanet == p.id)
      mOutlineOn = false;
    if (mCrackPlanet == p.id)
      mCrackOn = false;
    p.id = 0;
  }

  bool MarioRadius(const f32 pos[3], f32* radius) const
  {
    for (u32 i = 0; i < MAX_PLANETS; i++)
    {
      const Planet& p = gPlanets[i];
      if (!p.id || p.mario_radius <= 0.f || !p.gravity || p.gravity->mRange <= 1.f)
        continue;
      const f32 d[3] = {pos[0] - p.center[0], pos[1] - p.center[1], pos[2] - p.center[2]};
      const f32 range = p.gravity->mRange;
      const f32 dist2 = d[0] * d[0] + d[1] * d[1] + d[2] * d[2];
      if (dist2 > range * range)
        continue;
      // The cells narrow toward the center (a cell at 3/4 of the radius is 3/4 as wide): so does he.
      const f32 dist = gxc::Sqrt(dist2);
      *radius = dist < p.surface ? p.mario_radius * dist / p.surface : p.mario_radius;
      return true;
    }
    return false;
  }

  // The outline's edges as a display list of lines (vertex format 6: f32 positions from the
  // planet's center). Two, used in turn: the GPU may still be reading last frame's. visible is the
  // planet's id (0: none).
  void SetOutline(const gxc::InboxOutline& o)
  {
    mOutlineOn = false;
    if (!o.visible || !o.count)
      return;
    u8*& dl = mOutlineDl[mOutlineNext];
    if (!dl)
      dl = Alloc32(gxc::OUTLINE_DL_BYTES);
    if (!dl)
      return;
    gxc::OutlineList(o, GX_VTXFMT6, dl);
    DCFlushRange(dl, gxc::OUTLINE_DL_BYTES);
    mOutlineDraw = dl;
    mOutlineNext ^= 1;
    mOutlinePlanet = o.visible;
    mOutlineOn = true;
  }

  // The cracks' six sides as a display list (CrackMesh, vertex format 6). Two, used in turn, as
  // the outline's.
  void SetCrack(const gxc::InboxCrack& c)
  {
    mCrackOn = false;
    if (!c.visible)
      return;
    u8*& dl = mCrackDl[mCrackNext];
    if (!dl)
      dl = Alloc32(gxc::CRACK_DL_BYTES);
    if (!dl)
      return;
    gxc::CrackMesh(c.corners, c.uv, GX_VTXFMT6, dl);
    DCFlushRange(dl, gxc::CRACK_DL_BYTES);
    mCrackDraw = dl;
    mCrackNext ^= 1;
    mCrackPlanet = c.visible;
    mCrackOn = true;
  }

  void ReplaceFar(Planet& p, const gxc::InboxChunk& c)
  {
    // A planet drawn only has its six faces (slots 0..5); tiles need them all. A slot past what
    // the planet has grows its parts to all, those it had kept.
    if (!p.far || c.slot >= p.far_count)
    {
      const u32 count = !p.far && c.slot < 6 ? 6 : gxc::FAR_VIEW_PARTS;
      FarPart* grown = reinterpret_cast<FarPart*>(Alloc32(count * sizeof(FarPart)));
      if (!grown)
      {
        gVoxelStats.alloc_failed++;
        return;
      }
      memset(grown, 0, count * sizeof(FarPart));
      if (p.far)
      {
        memcpy(grown, p.far, p.far_count * sizeof(FarPart));
        Bury(p.far);
      }
      p.far = grown;
      p.far_count = count;
    }
    FarPart& f = p.far[c.slot];
    if (c.version <= f.version)
      return;
    if (c.covered && c.dl_size == 0)  // its chunks are in: the part it has stays, for afar
    {
      f.version = c.version;
      f.covered = true;
      return;
    }
    u8* dl = c.dl_size ? Alloc32(c.dl_size) : 0;
    if (c.dl_size && !dl)
    {
      gVoxelStats.alloc_failed++;
      return;
    }
    Bury(f.dl);
    f.version = c.version;
    f.dl = dl;
    f.dl_size = c.dl_size;
    f.covered = c.covered;
    for (int k = 0; k < 4; k++)
      f.sphere[k] = c.sphere[k];
    if (dl)
    {
      memcpy(dl, c.dl, c.dl_size);
      DCFlushRange(dl, c.dl_size);
    }
  }

  void Replace(Planet& p, const gxc::InboxChunk& c)
  {
    Slot& s = p.slots[c.slot];
    gVoxelStats.last_slot = c.slot;
    gVoxelStats.last_version = c.version;
    if (c.version <= s.version)
      return;
    Free(p, s);
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
    s.solid_size = c.solid_size;
    s.drawn = p.drawn_count;
    p.drawn[p.drawn_count++] = c.slot;
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
      Identity(&m, p.center);
      s.parts = new CollisionParts();
      // init files the part under the zone being placed, which outside of a stage's placement is
      // stale (a zone with no collision: null); the planet belongs to the main zone.
      const long zone = getCurrentPlacementZoneId__2MRFv();
      setCurrentPlacementZoneId__2MRFl(0);
      s.parts->init(m, getSensor("body"), s.kcl, gPa, 0, false);
      setCurrentPlacementZoneId__2MRFl(zone);
      validateCollisionParts__2MRFP14CollisionParts(s.parts);
      gVoxelStats.parts_made++;
      p.parts++;
    }
    CountStats();
  }

  static void CountStats()
  {
    u32 chunks = 0, parts = 0;
    for (u32 i = 0; i < MAX_PLANETS; i++)
      if (gPlanets[i].id)
        chunks += gPlanets[i].drawn_count, parts += gPlanets[i].parts;
    gVoxelStats.chunks = chunks;
    gVoxelStats.parts_live = parts;
  }

  // The old collision part leaves every zone and stays allocated (a few hundred bytes); its KCL
  // and display list are freed a few frames later.
  void Free(Planet& p, Slot& s)
  {
    if (s.parts)
    {
      invalidateCollisionParts__2MRFP14CollisionParts(s.parts);
      p.parts--;
    }
    if (s.dl)
    {
      const u32 last = p.drawn[--p.drawn_count];
      p.drawn[s.drawn] = last;
      p.slots[last].drawn = s.drawn;
    }
    Bury(s.dl);
    BuryKcl(s.kcl);
    s.dl = s.kcl = 0;
    s.parts = 0;
    s.dl_size = 0;
    s.solid_size = 0;
  }

  void NewSlots(Planet& p, u32 count)
  {
    for (u32 i = 0; i < p.slot_count; i++)
      Free(p, p.slots[i]);
    FreeHeap(p.slots);
    FreeHeap(p.drawn);
    p.slots = 0;
    p.drawn = 0;
    p.slot_count = p.drawn_count = 0;
    CountStats();
    if (count == 0)
      return;
    p.slots = reinterpret_cast<Slot*>(Alloc32(count * sizeof(Slot)));
    p.drawn = reinterpret_cast<u32*>(Alloc32(count * sizeof(u32)));
    if (!p.slots || !p.drawn)
    {
      gVoxelStats.alloc_failed++;
      Bury(p.slots);
      Bury(p.drawn);
      p.slots = 0;
      p.drawn = 0;
      return;
    }
    memset(p.slots, 0, count * sizeof(Slot));
    p.slot_count = count;
  }

  virtual void draw() const
  {
    bool any = false;
    for (u32 i = 0; i < MAX_PLANETS; i++)
      any = any || gPlanets[i].id;
    if (!any)
      return;
    // The camera's projection: this draw type runs after screen passes (bloom, in the Starship)
    // that leave another projection loaded, and the planet would land off screen.
    MR::loadProjectionMtx();
    // That projection's sides: chunks wholly beside the view are not drawn.
    f32 proj[7];
    GXGetProjectionv(proj);
    if (!gAtlas.ready)
    {
      if (gHitboxOn)
        DrawHitbox();
      return;
    }
    // Water, lava, fire...: their live tiles take this frame's texels.
    if (gAtlas.anim.Count())
    {
      u32 lo, hi;
      gAtlas.anim.Tick(gAtlas.tex, gAtlas.frame++, &lo, &hi);
      if (hi > lo)
      {
        DCFlushRange(gAtlas.tex + lo, hi - lo);
        GXInvalidateTexAll();
      }
    }
    // Positions: s16 with 3 fraction bits from the chunk's center (PlanetMesher), color RGB565,
    // light RGBA8 (block light's color, sky light in alpha), texture coordinates u16 with 15
    // fraction bits (a texel of a 1024-wide atlas is 32). A far view's (format 5): whole units
    // from the planet's center, no light (PlanetLod).
    GXSetVtxAttrFmt(GX_VTXFMT7, GX_VA_POS, GX_POS_XYZ, GX_S16, 3);
    GXSetVtxAttrFmt(GX_VTXFMT7, GX_VA_CLR0, GX_CLR_RGB, GX_RGB565, 0);
    GXSetVtxAttrFmt(GX_VTXFMT7, GX_VA_CLR1, GX_CLR_RGBA, GX_RGBA8, 0);
    GXSetVtxAttrFmt(GX_VTXFMT7, GX_VA_TEX0, GX_TEX_ST, GX_U16, 15);
    GXSetVtxAttrFmt(GX_VTXFMT5, GX_VA_POS, GX_POS_XYZ, GX_S16, 0);
    GXSetVtxAttrFmt(GX_VTXFMT5, GX_VA_CLR0, GX_CLR_RGB, GX_RGB565, 0);
    GXSetVtxAttrFmt(GX_VTXFMT5, GX_VA_TEX0, GX_TEX_ST, GX_U16, 15);
    GXSetNumChans(1);
    GXSetChanCtrl(GX_COLOR0A0, GX_FALSE, GX_SRC_REG, GX_SRC_VTX, 0, GX_DF_NONE, GX_AF_NONE);
    GXSetNumTexGens(1);
    GXSetTexCoordGen2(GX_TEXCOORD0, GX_TG_MTX2x4, GX_TG_TEX0, GX_IDENTITY, GX_FALSE, GX_PTIDENTITY);
    GXTexObj tex;
    // Crisp texels up close (nearest), mipmaps blended far away: no shimmering grass in the distance.
    GXInitTexObj(&tex, gAtlas.tex, static_cast<u16>(gAtlas.width), static_cast<u16>(gAtlas.height), GX_TF_RGB5A3,
                 GX_CLAMP, GX_CLAMP, gAtlas.levels > 1 ? GX_TRUE : GX_FALSE);
    GXInitTexObjLOD(&tex, gAtlas.levels > 1 ? GX_NEAR_MIP_LIN : GX_NEAR, GX_NEAR, 0.f,
                    static_cast<f32>(gAtlas.levels - 1), 0.f, GX_FALSE, GX_TRUE, GX_ANISO_1);
    GXLoadTexObj(&tex, GX_TEXMAP0);
    GXSetNumIndStages(0);
    GXSetTevSwapModeTable(GX_TEV_SWAP0, GX_CH_RED, GX_CH_GREEN, GX_CH_BLUE, GX_CH_ALPHA);
    GXColor black = {0, 0, 0, 0};
    GXSetFog(GX_FOG_NONE, 0.f, 0.f, 0.f, 0.f, black);
    GXSetZMode(GX_TRUE, GX_LEQUAL, GX_TRUE);
    GXSetZCompLoc(GX_FALSE);
    GXSetBlendMode(GX_BM_NONE, GX_BL_ONE, GX_BL_ZERO, GX_LO_NOOP);
    GXSetAlphaCompare(GX_GEQUAL, 128, GX_AOP_AND, GX_ALWAYS, 0);
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
    u32 drawn = 0, far = 0;
    // Far views: their texture and color under the sky's light (seen from afar, all sky-lit).
    UseFarLight();
    for (u32 i = 0; i < MAX_PLANETS; i++)
      if (gPlanets[i].id)
        DrawPlanet(gPlanets[i], view, proj, &drawn, &far, PASS_FAR);
    // Chunks: each corner's own light (caves dark, torches warm), the sky's by the hour.
    UseCornerLight();
    for (u32 i = 0; i < MAX_PLANETS; i++)
      if (gPlanets[i].id)
        DrawPlanet(gPlanets[i], view, proj, &drawn, &far, PASS_SOLID);
    // Water, over everything opaque: blended by its texture's alpha, hiding nothing behind it (no
    // depth written), seen from both sides (from under the surface too).
    GXSetBlendMode(GX_BM_BLEND, GX_BL_SRCALPHA, GX_BL_INVSRCALPHA, GX_LO_NOOP);
    GXSetZMode(GX_TRUE, GX_LEQUAL, GX_FALSE);
    GXSetAlphaCompare(GX_ALWAYS, 0, GX_AOP_AND, GX_ALWAYS, 0);
    GXSetCullMode(GX_CULL_NONE);
    for (u32 i = 0; i < MAX_PLANETS; i++)
      if (gPlanets[i].id)
        DrawPlanet(gPlanets[i], view, proj, &drawn, &far, PASS_CLEAR);
    // The block being broken: its cracks over everything drawn (the atlas is still loaded).
    const Planet* cracked = mCrackOn ? Find(mCrackPlanet) : 0;
    if (cracked)
      DrawCrack(view, cracked->center);
    GXSetBlendMode(GX_BM_NONE, GX_BL_ONE, GX_BL_ZERO, GX_LO_NOOP);
    GXSetZMode(GX_TRUE, GX_LEQUAL, GX_TRUE);
    GXSetCullMode(GX_CULL_FRONT);
    gVoxelStats.drawn_last = drawn;
    gVoxelStats.far_drawn = far;
    GXSetZCompLoc(GX_TRUE);
    GXSetAlphaCompare(GX_ALWAYS, 0, GX_AOP_AND, GX_ALWAYS, 0);
    const Planet* outlined = mOutlineOn ? Find(mOutlinePlanet) : 0;
    if (outlined)
      DrawOutline(view, outlined->center);
    if (gHitboxOn)
      DrawHitbox();
  }

  // Its far view's tiles and its chunks: the mod sends each tile as one or the other (chunks within
  // its render distance of Mario), so whatever the planet has is drawn. From afar, its far view
  // alone: the covered parts stand in for the chunks.
  enum Pass
  {
    PASS_FAR,    // its far view's parts
    PASS_SOLID,  // its chunks' opaque faces
    PASS_CLEAR,  // its chunks' translucent faces (water)
  };

  // TEV: texture x vertex color x the sky's light (KONST). Far views have no light of their own.
  static void UseFarLight()
  {
    GXClearVtxDesc();
    GXSetVtxDesc(GX_VA_POS, GX_DIRECT);
    GXSetVtxDesc(GX_VA_CLR0, GX_DIRECT);
    GXSetVtxDesc(GX_VA_TEX0, GX_DIRECT);
    GXSetNumChans(1);
    GXSetChanCtrl(GX_COLOR0A0, GX_FALSE, GX_SRC_REG, GX_SRC_VTX, 0, GX_DF_NONE, GX_AF_NONE);
    GXSetNumTevStages(2);
    GXSetTevKColor(GX_KCOLOR0, gSky);
    Stage(GX_TEVSTAGE0, GX_TEXCOORD0, GX_TEXMAP0, GX_COLOR0A0, GX_CC_ZERO, GX_CC_TEXC, GX_CC_RASC, GX_CC_ZERO, GX_TEVPREV);
    GXSetTevAlphaIn(GX_TEVSTAGE0, GX_CA_ZERO, GX_CA_ZERO, GX_CA_ZERO, GX_CA_TEXA);
    GXSetTevKColorSel(GX_TEVSTAGE1, GX_TEV_KCSEL_K0);
    Stage(GX_TEVSTAGE1, GX_TEXCOORD_NULL, GX_TEXMAP_NULL, GX_COLOR_NULL, GX_CC_ZERO, GX_CC_CPREV, GX_CC_KONST, GX_CC_ZERO,
          GX_TEVPREV);
    GXSetTevAlphaIn(GX_TEVSTAGE1, GX_CA_ZERO, GX_CA_ZERO, GX_CA_ZERO, GX_CA_APREV);
  }

  // TEV: light = block light's color + sky light x the sky's light (into C0), then texture x
  // vertex color x light: Minecraft's lightmap, the hour's part done here.
  static void UseCornerLight()
  {
    GXClearVtxDesc();
    GXSetVtxDesc(GX_VA_POS, GX_DIRECT);
    GXSetVtxDesc(GX_VA_CLR0, GX_DIRECT);
    GXSetVtxDesc(GX_VA_CLR1, GX_DIRECT);
    GXSetVtxDesc(GX_VA_TEX0, GX_DIRECT);
    GXSetNumChans(2);
    GXSetChanCtrl(GX_COLOR0A0, GX_FALSE, GX_SRC_REG, GX_SRC_VTX, 0, GX_DF_NONE, GX_AF_NONE);
    GXSetChanCtrl(GX_COLOR1A1, GX_FALSE, GX_SRC_REG, GX_SRC_VTX, 0, GX_DF_NONE, GX_AF_NONE);
    GXSetNumTevStages(3);
    GXSetTevKColor(GX_KCOLOR0, gSky);
    GXSetTevKColorSel(GX_TEVSTAGE0, GX_TEV_KCSEL_K0);
    Stage(GX_TEVSTAGE0, GX_TEXCOORD_NULL, GX_TEXMAP_NULL, GX_COLOR1A1, GX_CC_ZERO, GX_CC_KONST, GX_CC_RASA, GX_CC_RASC,
          GX_TEVREG0);
    GXSetTevAlphaIn(GX_TEVSTAGE0, GX_CA_ZERO, GX_CA_ZERO, GX_CA_ZERO, GX_CA_ZERO);
    Stage(GX_TEVSTAGE1, GX_TEXCOORD0, GX_TEXMAP0, GX_COLOR0A0, GX_CC_ZERO, GX_CC_TEXC, GX_CC_RASC, GX_CC_ZERO, GX_TEVPREV);
    GXSetTevAlphaIn(GX_TEVSTAGE1, GX_CA_ZERO, GX_CA_ZERO, GX_CA_ZERO, GX_CA_TEXA);
    Stage(GX_TEVSTAGE2, GX_TEXCOORD_NULL, GX_TEXMAP_NULL, GX_COLOR_NULL, GX_CC_ZERO, GX_CC_CPREV, GX_CC_C0, GX_CC_ZERO,
          GX_TEVPREV);
    GXSetTevAlphaIn(GX_TEVSTAGE2, GX_CA_ZERO, GX_CA_ZERO, GX_CA_ZERO, GX_CA_APREV);
  }

  // A TEV stage computing d + (1 - c) a + c b (clamped) into out; its alpha (set by the caller) likewise.
  static void Stage(GXTevStageID st, GXTexCoordID coord, GXTexMapID map, GXChannelID chan, GXTevColorArg a, GXTevColorArg b,
                    GXTevColorArg c, GXTevColorArg d, GXTevRegID out)
  {
    GXSetTevDirect(st);
    GXSetTevSwapMode(st, GX_TEV_SWAP0, GX_TEV_SWAP0);
    GXSetTevOrder(st, coord, map, chan);
    GXSetTevColorIn(st, a, b, c, d);
    GXSetTevColorOp(st, GX_TEV_ADD, GX_TB_ZERO, GX_CS_SCALE_1, GX_TRUE, out);
    GXSetTevAlphaOp(st, GX_TEV_ADD, GX_TB_ZERO, GX_CS_SCALE_1, GX_TRUE, out);
  }

  static void DrawPlanet(const Planet& p, const f32 view[12], const f32 proj[7], u32* drawn, u32* far, Pass pass)
  {
    // The camera in the planet's frame: chunks behind it, past the horizon or beside the view are
    // skipped. The bedrock shell (unbreakable) is the ball that hides them.
    // From the view matrix this frame draws with (in first person GalaxyCraft's, not the game
    // camera's): position -Rᵀt, forward -(third row), as GX cameras look down -z.
    f32 eye[3], fwd[3];
    gxc::ViewEye(view, eye, fwd);
    eye[0] -= p.center[0], eye[1] -= p.center[1], eye[2] -= p.center[2];
    const f32 origin[3] = {0.f, 0.f, 0.f};
    // The planet's matrix once, each chunk's from it: chunk centers are whole units from the
    // planet's center, so neighbors' shared corners come out of the same math and leave no seams.
    f32 planet[12];
    gxc::ViewTranslate(view, p.center, planet);
    // The whole planet beside the view (its ground and the room above it): none of it is drawn.
    const f32 whole[3] = {planet[3], planet[7], planet[11]};
    if (gxc::SphereOutsideView(proj, whole, p.surface + 32.f * 80.f))
      return;
    const f32 above = gxc::Sqrt(eye[0] * eye[0] + eye[1] * eye[1] + eye[2] * eye[2]) - p.surface;
    const bool afar = p.far && above > (p.surface > FAR_VIEW_ABOVE ? p.surface : FAR_VIEW_ABOVE);
    const bool translucent = pass == PASS_CLEAR;
    if (p.far && pass == PASS_FAR)
    {
      // Past the camera's far plane the GPU would clip it away: drawn smaller and nearer by the
      // same factor, about the camera, it looks the same and stays in front of that plane.
      // GX's perspective: m22 = -n/(f-n), m23 = -fn/(f-n), so f = m23/m22.
      const f32 farZ = proj[5] != 0.f ? proj[6] / proj[5] : 0.f;
      const f32 dist = gxc::Sqrt(eye[0] * eye[0] + eye[1] * eye[1] + eye[2] * eye[2]);
      const f32 reach = dist + p.surface + 32.f * 80.f;
      f32 scale = 1.f;
      if (farZ > 0.f && reach > 0.9f * farZ)  // its far side kept under 0.99 f, nearer ones nearer
        scale = farZ * (0.9f + 0.09f * (1.f - 0.9f * farZ / reach)) / reach;
      if (scale < 1.f)
      {
        f32 at[3];
        for (int k = 0; k < 3; k++)
          at[k] = p.center[k] + eye[k] * (1.f - scale);  // camera + (center - camera) * scale
        gxc::ViewTranslate(view, at, planet);
        for (int r = 0; r < 3; r++)
          for (int c = 0; c < 3; c++)
            planet[4 * r + c] *= scale;
      }
      GXLoadPosMtxImm(reinterpret_cast<f32(*)[4]>(planet), GX_PNMTX0);
      for (u32 f = 0; f < p.far_count; f++)
      {
        const FarPart& part = p.far[f];
        if (!part.dl || (part.covered && !afar) || gxc::SphereHidden(eye, fwd, origin, p.occluder, part.sphere, part.sphere[3]))
          continue;
        f32 pos[12];
        gxc::ViewTranslate(planet, part.sphere, pos);
        const f32 at[3] = {pos[3], pos[7], pos[11]};
        if (gxc::SphereOutsideView(proj, at, part.sphere[3] * scale))
          continue;
        GXCallDisplayList(part.dl, part.dl_size);
        (*far)++;
      }
    }
    if (afar || pass == PASS_FAR)
      return;
    for (u32 i = 0; i < p.drawn_count; i++)
    {
      const Slot& s = p.slots[p.drawn[i]];
      const u32 size = translucent ? s.dl_size - s.solid_size : s.solid_size;
      if (size == 0)
        continue;
      if (gxc::SphereHidden(eye, fwd, origin, p.occluder, s.sphere, s.sphere[3]))
        continue;
      // Each chunk's vertices are relative to its own center.
      f32 pos[12];
      gxc::ViewTranslate(planet, s.sphere, pos);
      const f32 at[3] = {pos[3], pos[7], pos[11]};
      if (gxc::SphereOutsideView(proj, at, s.sphere[3]))
        continue;
      GXLoadPosMtxImm(reinterpret_cast<f32(*)[4]>(pos), GX_PNMTX0);
      GXCallDisplayList(translucent ? s.dl + s.solid_size : s.dl, size);
      if (!translucent)
        (*drawn)++;
    }
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
  void DrawOutline(const f32 view[12], const f32 center[3]) const
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
    gxc::ViewTranslate(view, center, pos);
    GXLoadPosMtxImm(reinterpret_cast<f32(*)[4]>(pos), GX_PNMTX0);
    GXCallDisplayList(mOutlineDraw, gxc::OUTLINE_DL_BYTES);
  }

  // Minecraft's crumbling: the crack tile multiplied into what is under it, twice (2 x texture x
  // screen: its mid gray changes nothing, its dark lines darken), where the tile is not a hole.
  void DrawCrack(const f32 view[12], const f32 center[3]) const
  {
    GXClearVtxDesc();
    GXSetVtxDesc(GX_VA_POS, GX_DIRECT);
    GXSetVtxDesc(GX_VA_TEX0, GX_DIRECT);
    GXSetVtxAttrFmt(GX_VTXFMT6, GX_VA_POS, GX_POS_XYZ, GX_F32, 0);
    GXSetVtxAttrFmt(GX_VTXFMT6, GX_VA_TEX0, GX_TEX_ST, GX_F32, 0);
    GXSetNumChans(1);
    GXSetChanCtrl(GX_COLOR0A0, GX_FALSE, GX_SRC_REG, GX_SRC_REG, 0, GX_DF_NONE, GX_AF_NONE);
    GXSetNumTexGens(1);
    GXSetTexCoordGen2(GX_TEXCOORD0, GX_TG_MTX2x4, GX_TG_TEX0, GX_IDENTITY, GX_FALSE, GX_PTIDENTITY);
    GXSetNumTevStages(1);
    Stage(GX_TEVSTAGE0, GX_TEXCOORD0, GX_TEXMAP0, GX_COLOR_NULL, GX_CC_ZERO, GX_CC_ZERO, GX_CC_ZERO, GX_CC_TEXC,
          GX_TEVPREV);
    GXSetTevAlphaIn(GX_TEVSTAGE0, GX_CA_ZERO, GX_CA_ZERO, GX_CA_ZERO, GX_CA_TEXA);
    GXSetBlendMode(GX_BM_BLEND, GX_BL_DSTCLR, GX_BL_SRCCLR, GX_LO_NOOP);
    GXSetAlphaCompare(GX_GREATER, 25, GX_AOP_AND, GX_ALWAYS, 0);  // Minecraft drops alpha under 0.1
    GXSetZMode(GX_TRUE, GX_LEQUAL, GX_FALSE);
    GXSetCullMode(GX_CULL_NONE);
    f32 pos[12];
    gxc::ViewTranslate(view, center, pos);
    GXLoadPosMtxImm(reinterpret_cast<f32(*)[4]>(pos), GX_PNMTX0);
    GXCallDisplayList(mCrackDraw, gxc::CRACK_DL_BYTES);
  }

  bool mOutlineOn;
  u32 mOutlinePlanet;
  u32 mOutlineNext;
  u8* mOutlineDl[2];
  u8* mOutlineDraw;
  bool mCrackOn;
  u32 mCrackPlanet;
  u32 mCrackNext;
  u8* mCrackDl[2];
  u8* mCrackDraw;
};
}  // namespace

VoxelStats gVoxelStats;

void VoxelPlanetCreate(uint32_t* inbox_addr, uint32_t* inbox_size)
{
  // A new scene: the old one's heap (chunks, parts, inbox) is gone, so forget it, don't free it.
  memset(gPlanets, 0, sizeof(gPlanets));
  gHitboxDl[0] = gHitboxDl[1] = 0;
  gHitboxOn = false;
  gGraves.Reset();
  gKclGraves.Reset();
  gVoxelStats.module_bytes = 0;
  gModHeap = 0;  // went with the old scene's heap
  gInbox = static_cast<u8*>(operator new(INBOX_BYTES));
  memset(&gAtlas, 0, sizeof(gAtlas));  // the mod sends it again to every scene
  memset(gInbox, 0, sizeof(GxcInboxHeader));
  gActor = new VoxelPlanetActor();
  gActor->initWithoutIter();
  *inbox_addr = reinterpret_cast<u32>(gInbox);
  *inbox_size = INBOX_BYTES;
}

void VoxelPlanetHitbox(const MarioHitbox* box)
{
  gHitboxOn = box != 0;
  if (box)
    gHitbox = *box;
}

uint8_t* VoxelPlanetAlloc32(uint32_t size)
{
  return Alloc32(size);
}

bool VoxelPlanetMarioRadius(const float pos[3], float* radius)
{
  return gActor && gActor->MarioRadius(pos, radius);
}

void VoxelPlanetMarioMoved()
{
  gMarioMoved = true;
}

void VoxelPlanetFrame(uint32_t scene_id, uint32_t* inbox_addr, uint32_t* inbox_size)
{
  *inbox_addr = reinterpret_cast<u32>(gInbox);
  *inbox_size = gInbox ? INBOX_BYTES : 0;
  if (!gActor || !gInbox)
    return;
  TickGraves();
  // Every frame, not only with a batch: what was freed after the last one (the graves) shows too.
  gVoxelStats.free_mem2 = gModHeap ? getFreeSize__7JKRHeapFv(gModHeap) : 0;
  gVoxelStats.free_mem1 = getFreeSize__7JKRHeapFv(getSceneHeapNapa__2MRFv());
  gVoxelStats.total_free_mem2 = getTotalFreeSize__7JKRHeapFv(getSceneHeapGDDR3__2MRFv());
  GxcInboxHeader* h = reinterpret_cast<GxcInboxHeader*>(gInbox);
  if (h->state != 1)
    return;
  gVoxelStats.batches++;
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
