// The voxel planet inside SMG2 (docs/superpowers/specs/2026-10-02-galaxycraft-voxel-planets-design.md).
// One actor per scene owns an inbox the host fills with the planet, its chunks (display list +
// KCL, built by the Minecraft mod) and teleports. Each chunk is copied to the heap, drawn with the
// block atlas and given its own collision part; a point gravity pulls toward the center.
#include "syati.h"

#include "Game/Gravity/PointGravity.h"
#include "Game/Map/CollisionParts.h"
#include "Inbox.h"
#include "VoxelPlanet.h"
#include "atlas.h"
#include "galaxycraft_protocol.h"

extern "C" void validateCollisionParts__2MRFP14CollisionParts(CollisionParts*);
extern "C" void invalidateCollisionParts__2MRFP14CollisionParts(CollisionParts*);

namespace
{
const u32 INBOX_BYTES = 256 * 1024;
// Mario is put this far above the surface by a teleport (half a block).
const f32 DROP_ABOVE = 40.f;

struct Slot
{
  u32 version;
  u8* dl_raw;  // allocation; dl is it rounded up to 32
  u8* dl;
  u32 dl_size;
  u8* kcl;
  CollisionParts* parts;
};

// .pa with no fields and one entry: every triangle gets attribute 0 (plain ground).
__attribute__((aligned(32))) u8 gPa[20] = {0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 16, 0, 0, 0, 4, 0, 0, 0, 0};

Slot gSlots[GXC_PLANET_MAX_CHUNKS];

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
class VoxelPlanetActor;
VoxelPlanetActor* gActor = 0;
u8* gInbox = 0;
// The atlas copied to 32-byte aligned memory: the GPU drops an address's low 5 bits, and Kamek
// does not keep the array's own alignment.
u8* gAtlas = 0;

u8* Aligned32(u32 size, u8** raw)
{
  *raw = static_cast<u8*>(operator new(size + 32));
  return reinterpret_cast<u8*>((reinterpret_cast<u32>(*raw) + 31) & ~31u);
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
  VoxelPlanetActor() : LiveActor("GxcVoxelPlanet"), mGravity(0), mPlanet(0), mChunks(0)
  {
    mCenter[0] = mCenter[1] = mCenter[2] = 0.f;
    mSurface = 0.f;
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
      if (r.planet.id != mPlanet)
        DropChunks();
      mPlanet = r.planet.id;
      for (int k = 0; k < 3; k++)
        mCenter[k] = r.planet.center[k];
      mSurface = r.planet.surface;
      mGravity->mLocalPos = TVec3f(mCenter[0], mCenter[1], mCenter[2]);
      mGravity->mRange = mPlanet ? r.planet.gravity_range : 1.f;
      mGravity->updateIdentityMtx();
      mTranslation = mGravity->mLocalPos;
    }
    else if (r.type == gxc::InboxRecord::CHUNK && mPlanet)
    {
      Replace(r.chunk);
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

  void Replace(const gxc::InboxChunk& c)
  {
    Slot& s = gSlots[c.slot];
    gVoxelStats.last_slot = c.slot;
    gVoxelStats.last_version = c.version;
    if (s.dl && c.version <= s.version)
      return;
    Free(s);
    s.version = c.version;
    if (c.dl_size == 0)
      return;
    s.dl = Aligned32(c.dl_size, &s.dl_raw);
    memcpy(s.dl, c.dl, c.dl_size);
    DCFlushRange(s.dl, c.dl_size);
    s.dl_size = c.dl_size;
    s.kcl = static_cast<u8*>(operator new(c.kcl_size));
    memcpy(s.kcl, c.kcl, c.kcl_size);
    TPos3f m;
    Identity(&m, mCenter);
    s.parts = new CollisionParts();
    s.parts->init(m, getSensor("body"), s.kcl, gPa, 0, false);
    validateCollisionParts__2MRFP14CollisionParts(s.parts);
    mChunks++;
    gVoxelStats.parts_made++;
    gVoxelStats.chunks = mChunks;
  }

  // The old collision part leaves every zone and stays allocated (a few hundred bytes); its KCL
  // and display list are freed a few frames later.
  void Free(Slot& s)
  {
    if (s.parts)
      invalidateCollisionParts__2MRFP14CollisionParts(s.parts);
    Bury(s.dl_raw);
    Bury(s.kcl);
    if (s.dl)
      mChunks--;
    s.dl_raw = s.dl = s.kcl = 0;
    s.parts = 0;
    s.dl_size = 0;
  }

  void DropChunks()
  {
    for (u32 i = 0; i < GXC_PLANET_MAX_CHUNKS; i++)
    {
      Free(gSlots[i]);
      gSlots[i].version = 0;
    }
  }

  virtual void draw() const
  {
    if (!mPlanet || mChunks == 0)
      return;
    GXClearVtxDesc();
    GXSetVtxDesc(GX_VA_POS, GX_DIRECT);
    GXSetVtxDesc(GX_VA_CLR0, GX_DIRECT);
    GXSetVtxDesc(GX_VA_TEX0, GX_DIRECT);
    GXSetVtxAttrFmt(GX_VTXFMT7, GX_VA_POS, GX_POS_XYZ, GX_F32, 0);
    GXSetVtxAttrFmt(GX_VTXFMT7, GX_VA_CLR0, GX_CLR_RGBA, GX_RGBA8, 0);
    GXSetVtxAttrFmt(GX_VTXFMT7, GX_VA_TEX0, GX_TEX_ST, GX_U16, 10);
    GXSetNumChans(1);
    GXSetChanCtrl(GX_COLOR0A0, GX_FALSE, GX_SRC_REG, GX_SRC_VTX, 0, GX_DF_NONE, GX_AF_NONE);
    GXSetNumTexGens(1);
    GXSetTexCoordGen2(GX_TEXCOORD0, GX_TG_MTX2x4, GX_TG_TEX0, GX_IDENTITY, GX_FALSE, GX_PTIDENTITY);
    GXTexObj tex;
    GXInitTexObj(&tex, gAtlas, GXC_ATLAS_SIZE, GXC_ATLAS_SIZE, GX_TF_RGB565, GX_CLAMP, GX_CLAMP, GX_FALSE);
    GXInitTexObjLOD(&tex, GX_NEAR, GX_NEAR, 0.f, 0.f, 0.f, GX_FALSE, GX_FALSE, GX_ANISO_1);
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

    f32 view[12], pos[12];
    const MtxPtr cam = MR::getCameraViewMtx();
    for (int r = 0; r < 3; r++)
      for (int c = 0; c < 4; c++)
        view[4 * r + c] = cam[r][c];
    gxc::ViewTranslate(view, mCenter, pos);
    f32 m[3][4];
    for (int r = 0; r < 3; r++)
      for (int c = 0; c < 4; c++)
        m[r][c] = pos[4 * r + c];
    GXLoadPosMtxImm(m, GX_PNMTX0);
    GXSetCurrentMtx(GX_PNMTX0);
    for (u32 i = 0; i < GXC_PLANET_MAX_CHUNKS; i++)
      if (gSlots[i].dl)
        GXCallDisplayList(gSlots[i].dl, gSlots[i].dl_size);
  }

  PointGravity* mGravity;
  u32 mPlanet;
  u32 mChunks;
  f32 mCenter[3];
  f32 mSurface;
};
}  // namespace

VoxelStats gVoxelStats = {0, 0, 0, 0, 0, 0};

void VoxelPlanetCreate()
{
  // A new scene: the old one's heap (chunks, parts, inbox) is gone, so forget it, don't free it.
  memset(gSlots, 0, sizeof(gSlots));
  memset(gGraves, 0, sizeof(gGraves));
  gInbox = static_cast<u8*>(operator new(INBOX_BYTES));
  u8* raw;
  gAtlas = Aligned32(sizeof(gVoxelAtlas), &raw);
  memcpy(gAtlas, gVoxelAtlas, sizeof(gVoxelAtlas));
  DCFlushRange(gAtlas, sizeof(gVoxelAtlas));
  memset(gInbox, 0, sizeof(GxcInboxHeader));
  gActor = new VoxelPlanetActor();
  gActor->initWithoutIter();
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
