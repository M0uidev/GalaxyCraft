// GalaxyCraft module for SMG2 (SB4E), loaded by Syati's loader as CustomCode_SB4E.bin.
// Publishes the GXCRMBX1 mailbox that Dolphin's host bridge mirrors to the Minecraft mod, and
// while the host sets GXC_MBX_DRIVE turns Mario into a hidden puppet and takes over the camera.
#include "syati.h"

#include "Kcl.h"
#include "Parts.h"
#include "ViewMath.h"
#include "galaxycraft_protocol.h"

// Originals, by their mangled names in symbols/SB4E.txt.
extern "C" void* getCollisionDirector__2MRFv();
extern "C" void init__10MarioActorFRC12JMapInfoIter(void* self, const void* iter);
extern "C" void movement__10MarioActorFv(void* self);
extern "C" void movement__14CameraDirectorFv(void* self);

namespace
{
// Development counters right after the mailbox (peek at=+sizeof(GxcMailbox), see docs/PHASE3.md).
struct Debug
{
  u32 collision_director;
  u32 map_keeper;
  u32 zone_count;
  u32 candidates;
  u32 rejected_kcl;
  u32 first_part;
  u32 first_kcl;
  u32 driven;
};

struct Published
{
  GxcMailbox mbx;
  Debug dbg;
};

// Constant-initialized only: Kamek binaries get no static constructors.
// The magic lives only in this initializer, so the host's RAM scan finds the mailbox itself
// and not a stray copy of the string.
Published gOut = {{{'G', 'X', 'C', 'R', 'M', 'B', 'X', '1'}, GXC_MBX_VERSION}};

// Parts farther than this from the query point (to their bounding sphere) are not published.
const f32 PART_MAX_DIST = 3000.f;
// Candidates scanned per frame; the map keeper of a big galaxy stays well under this.
const int MAX_CANDIDATES = 256;
// Frames without a new host_seq after which the puppet is released (Dolphin bridge gone).
const u32 HOST_TIMEOUT_FRAMES = 60;

gxc::PartCandidate gCandidates[MAX_CANDIDATES];
bool gDriven = false;
u32 gLastHostSeq = 0;
u32 gFramesSinceHost = 0;

// Collision layout, checked live on SB4E (SMG2's NameObj is 0x14 bytes, so keeper fields sit
// 8 bytes after Petari's/Syati's): CollisionDirector +0x14 points to the keeper array,
// keeper zone pointers at +0x20 (count +0xA0), zone part pointers at +0x4 (count +0x804),
// part base matrix at +0x34, KCollisionServer* at +0xC4, bounding radius at +0xD8;
// KCollisionServer's first word is the KCL header it was set up with.
const u32 DIRECTOR_KEEPERS = 0x14;
const u32 KEEPER_ZONES = 0x20;
const u32 KEEPER_ZONE_COUNT = 0xA0;
const u32 MAX_ZONES = 32;
const u32 ZONE_PARTS = 0x4;
const u32 ZONE_PART_COUNT = 0x804;
const u32 MAX_ZONE_PARTS = 512;
const u32 PART_BASE_MTX = 0x34;
const u32 PART_SERVER = 0xC4;
const u32 PART_RADIUS = 0xD8;

bool IsRam(u32 addr)
{
  return (addr >= 0x80000000u && addr < 0x81800000u) || (addr >= 0x90000000u && addr < 0x94000000u);
}

u32 Word(u32 addr)
{
  return *reinterpret_cast<const u32*>(addr);
}

void Copy3(f32* dst, const f32* src)
{
  dst[0] = src[0], dst[1] = src[1], dst[2] = src[2];
}

bool HostDrives()
{
  // A stale DRIVE (Dolphin's bridge stopped writing) must not leave Mario a puppet forever.
  if (gOut.mbx.host_seq != gLastHostSeq)
  {
    gLastHostSeq = gOut.mbx.host_seq;
    gFramesSinceHost = 0;
  }
  else if (gFramesSinceHost < HOST_TIMEOUT_FRAMES)
  {
    gFramesSinceHost++;
  }
  return (gOut.mbx.host_flags & GXC_MBX_DRIVE) != 0 && gFramesSinceHost < HOST_TIMEOUT_FRAMES;
}

int GatherCandidates()
{
  Debug& dbg = gOut.dbg;
  const u32 director = reinterpret_cast<u32>(getCollisionDirector__2MRFv());
  dbg.collision_director = director;
  if (!IsRam(director))
    return 0;
  const u32 keepers = Word(director + DIRECTOR_KEEPERS);
  if (!IsRam(keepers))
    return 0;
  const u32 keeper = Word(keepers);  // category 0: Map
  dbg.map_keeper = keeper;
  if (!IsRam(keeper))
    return 0;
  u32 zones = Word(keeper + KEEPER_ZONE_COUNT);
  dbg.zone_count = zones;
  if (zones > MAX_ZONES)
    zones = MAX_ZONES;

  int n = 0;
  u32 rejected = 0;
  for (u32 z = 0; z < zones; z++)
  {
    const u32 zone = Word(keeper + KEEPER_ZONES + 4 * z);
    if (!IsRam(zone))
      continue;
    u32 count = Word(zone + ZONE_PART_COUNT);
    if (count > MAX_ZONE_PARTS)
      count = MAX_ZONE_PARTS;
    for (u32 i = 0; i < count && n < MAX_CANDIDATES; i++)
    {
      const u32 part = Word(zone + ZONE_PARTS + 4 * i);
      if (!IsRam(part))
        continue;
      const u32 server = Word(part + PART_SERVER);
      const u32 kcl = IsRam(server) ? Word(server) : 0;
      const u32 size = IsRam(kcl) ? gxc::KclSizeFromHeader(reinterpret_cast<const u32*>(kcl), kcl) : 0;
      if (size == 0)
      {
        rejected++;
        continue;
      }
      gxc::PartCandidate& c = gCandidates[n++];
      c.id = part;
      c.kcl = kcl;
      c.size = size;
      const f32* mtx = reinterpret_cast<const f32*>(part + PART_BASE_MTX);
      for (int k = 0; k < 12; k++)
        c.mtx[k] = mtx[k];
      c.radius = *reinterpret_cast<const f32*>(part + PART_RADIUS);
      if (n == 1)
      {
        dbg.first_part = part;
        dbg.first_kcl = kcl;
      }
    }
  }
  dbg.candidates = n;
  dbg.rejected_kcl = rejected;
  return n;
}

void PublishParts(const f32* query)
{
  const int n = GatherCandidates();
  int picked[GXC_MBX_MAX_PARTS];
  const int count = gxc::SelectParts(gCandidates, n, query, PART_MAX_DIST, picked, GXC_MBX_MAX_PARTS);
  for (int i = 0; i < count; i++)
  {
    const gxc::PartCandidate& c = gCandidates[picked[i]];
    GxcMbxPart& out = gOut.mbx.parts[i];
    out.part_id = c.id;
    out.kcl_addr = c.kcl;
    out.kcl_size = c.size;
    for (int k = 0; k < 12; k++)
      out.mtx[k] = c.mtx[k];
  }
  gOut.mbx.part_count = count;
}
}  // namespace

namespace
{
void MarioInit(void* self, const void* iter)
{
  init__10MarioActorFRC12JMapInfoIter(self, iter);
  gOut.mbx.scene_id++;  // a new Mario means a new stage: the host republishes everything
  // The new Mario starts visible and controllable: if still driven, the next movement must
  // take him over again.
  gDriven = false;
}

void MarioMovement(void* self)
{
  movement__10MarioActorFv(self);

  const bool drive = HostDrives();
  if (drive && !gDriven)
  {
    MR::offPlayerControl();
    MR::hidePlayer();
  }
  else if (!drive && gDriven)
  {
    MR::onPlayerControl(true);
    MR::showPlayer();
  }
  gDriven = drive;
  gOut.dbg.driven = drive;
  if (drive)
    MR::setPlayerPos(TVec3f(gOut.mbx.player_pos[0], gOut.mbx.player_pos[1], gOut.mbx.player_pos[2]));

  const TVec3f* mario = MR::getPlayerPos();
  gOut.mbx.anchor_pos[0] = mario->x, gOut.mbx.anchor_pos[1] = mario->y, gOut.mbx.anchor_pos[2] = mario->z;
  f32 query[3];
  Copy3(query, drive ? gOut.mbx.player_pos : gOut.mbx.anchor_pos);

  TVec3f gravity(0.f, 0.f, 0.f);
  if (!MR::calcGravityVector(static_cast<const NameObj*>(self), TVec3f(query[0], query[1], query[2]),
                             &gravity, 0, 0))
    gravity = TVec3f(0.f, 0.f, 0.f);
  gOut.mbx.gravity[0] = gravity.x, gOut.mbx.gravity[1] = gravity.y, gOut.mbx.gravity[2] = gravity.z;

  PublishParts(query);
  gOut.mbx.game_flags = drive ? 1u : 0u;
  gOut.mbx.game_seq++;  // last: the host reads a consistent frame when this moves
}

void CameraMovement(void* self)
{
  movement__14CameraDirectorFv(self);
  if (!gDriven)
    return;
  const f32* up = gOut.mbx.up;
  const f32 eye[3] = {gOut.mbx.player_pos[0] + up[0] * gOut.mbx.eye_height,
                      gOut.mbx.player_pos[1] + up[1] * gOut.mbx.eye_height,
                      gOut.mbx.player_pos[2] + up[2] * gOut.mbx.eye_height};
  f32 view[12];
  gxc::LookAtView(eye, gOut.mbx.look, up, view);
  TPos3f mtx;
  for (int r = 0; r < 3; r++)
    for (int c = 0; c < 4; c++)
      mtx.mMtx[r][c] = view[4 * r + c];
  MR::setCameraViewMtx(mtx, false, false, TVec3f(0.f, 0.f, 0.f));
  MR::setFovy(gOut.mbx.fov_y);
}
}  // namespace

// Vtable slots (symbols/SB4E.txt): __vt__10MarioActor + 0xC init, + 0x14 movement;
// __vt__14CameraDirector + 0x14 movement.
kmWritePointer(0x806C7448 + 0xC, MarioInit);
kmWritePointer(0x806C7448 + 0x14, MarioMovement);
kmWritePointer(0x8067C4D0 + 0x14, CameraMovement);
