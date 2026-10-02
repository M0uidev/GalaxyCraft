// GalaxyCraft module for SMG2 (SB4E), loaded by Syati's loader as CustomCode_SB4E.bin.
// Publishes the GXCRMBX1 mailbox that Dolphin's host bridge mirrors to the Minecraft mod, and
// while the host sets GXC_MBX_FOLLOW hides Mario and puts the camera in his eyes, looking where
// Minecraft looks. Mario still moves by his own physics, played through the emulated Wii Remote.
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
extern "C" void draw__10MarioActorCFv(const void* self);
// Syati's StarPointerUtil.h declares it without the port argument the game takes.
extern "C" bool isStarPointerValid__2MRFl(long port);

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
  u32 following;
  u32 mario_height_x100;  // 200 * |center - feet|: Mario's height in units, times 100
  u32 demo;
  u32 head_height_x100;  // 100 * height of the model's "Head" joint above the feet
  u32 game_near_z_x100;  // 100 * the game's camera near plane, before the override
  u32 star_pointer_valid;  // MR::isStarPointerValid(0): the game still takes the pointer
  u32 galaxy_view;         // the host asked for the game's own camera
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
// Frames without a new host_seq after which Mario and the camera are given back (bridge gone).
const u32 HOST_TIMEOUT_FRAMES = 60;

gxc::PartCandidate gCandidates[MAX_CANDIDATES];
bool gFollowing = false;
bool gDemo = false;  // a cutscene owns Mario and the camera this frame
// Mario (Steve) is not drawn (first person, outside cutscenes). MR::hidePlayer is no use: Mario then
// ignores the stick. Skipping MarioActor::draw leaves him playable, and his shadow stays.
bool gHidden = false;
// In Mario's eyes the game's near plane (made for a camera metres behind him) clips whatever is
// close; while following the camera draws from almost at the eye. 10 units = 1/8 block.
const f32 EYE_NEAR_Z = 10.f;
f32 gGameNearZ = 0.f;
bool gNearOverridden = false;
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

bool GalaxyView()
{
  return (gOut.mbx.host_flags & GXC_MBX_GALAXY_VIEW) != 0;
}

bool HostFollows()
{
  // A stale FOLLOW (Dolphin's bridge stopped writing) must not leave Mario hidden forever.
  if (gOut.mbx.host_seq != gLastHostSeq)
  {
    gLastHostSeq = gOut.mbx.host_seq;
    gFramesSinceHost = 0;
  }
  else if (gFramesSinceHost < HOST_TIMEOUT_FRAMES)
  {
    gFramesSinceHost++;
  }
  return (gOut.mbx.host_flags & GXC_MBX_FOLLOW) != 0 && gFramesSinceHost < HOST_TIMEOUT_FRAMES;
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
}

void MarioMovement(void* self)
{
  movement__10MarioActorFv(self);

  gFollowing = HostFollows();
  gOut.dbg.following = gFollowing;
  gDemo = MR::isDemoActive();
  gOut.dbg.demo = gDemo;
  // Steve (Mario's model) is hidden only in first person; cutscenes always show him.
  gHidden = !gxc::MarioVisible(gFollowing, gDemo, (gOut.mbx.host_flags & GXC_MBX_THIRD_PERSON) != 0);
  gOut.dbg.star_pointer_valid = isStarPointerValid__2MRFl(0);

  const TVec3f* mario = MR::getPlayerPos();
  const TVec3f* center = MR::getPlayerCenterPos();
  const f32 half[3] = {center->x - mario->x, center->y - mario->y, center->z - mario->z};
  MtxPtr head = MR::getJointMtx(static_cast<const LiveActor*>(self), "Head");
  if (IsRam(reinterpret_cast<u32>(head)))
  {
    const f32 up[3] = {-gOut.mbx.gravity[0], -gOut.mbx.gravity[1], -gOut.mbx.gravity[2]};
    const f32 h = (head[0][3] - mario->x) * up[0] + (head[1][3] - mario->y) * up[1] +
                  (head[2][3] - mario->z) * up[2];
    gOut.dbg.head_height_x100 = static_cast<u32>(h * 100.f);
  }
  gOut.dbg.mario_height_x100 =
      static_cast<u32>(200.f * gxc::Sqrt(half[0] * half[0] + half[1] * half[1] + half[2] * half[2]));
  gOut.mbx.anchor_pos[0] = mario->x, gOut.mbx.anchor_pos[1] = mario->y, gOut.mbx.anchor_pos[2] = mario->z;
  f32 query[3];
  Copy3(query, gOut.mbx.anchor_pos);

  TVec3f gravity(0.f, 0.f, 0.f);
  if (!MR::calcGravityVector(static_cast<const NameObj*>(self), TVec3f(query[0], query[1], query[2]),
                             &gravity, 0, 0))
    gravity = TVec3f(0.f, 0.f, 0.f);
  gOut.mbx.gravity[0] = gravity.x, gOut.mbx.gravity[1] = gravity.y, gOut.mbx.gravity[2] = gravity.z;

  TVec3f front(0.f, 0.f, 0.f);
  MR::getPlayerFrontVec(&front);
  gOut.mbx.mario_front[0] = front.x, gOut.mbx.mario_front[1] = front.y, gOut.mbx.mario_front[2] = front.z;

  PublishParts(query);
  gOut.mbx.game_flags = (gFollowing ? GXC_MBX_GAME_FOLLOWING : 0u) | (gDemo ? GXC_MBX_GAME_DEMO : 0u);
  gOut.mbx.game_seq++;  // last: the host reads a consistent frame when this moves
}

void MarioDraw(const void* self)
{
  if (!gHidden)
    draw__10MarioActorCFv(self);
}

// The game's camera this frame, before any override: the mod's Galaxy view follows it.
void PublishGameCamera()
{
  const TVec3f pos = MR::getCamPos();
  // GX cameras look down their -z axis.
  const TVec3f z = MR::getCamZdir();
  const TVec3f y = MR::getCamYdir();
  gOut.mbx.cam_pos[0] = pos.x, gOut.mbx.cam_pos[1] = pos.y, gOut.mbx.cam_pos[2] = pos.z;
  gOut.mbx.cam_dir[0] = -z.x, gOut.mbx.cam_dir[1] = -z.y, gOut.mbx.cam_dir[2] = -z.z;
  gOut.mbx.cam_up[0] = y.x, gOut.mbx.cam_up[1] = y.y, gOut.mbx.cam_up[2] = y.z;
  gOut.mbx.cam_fov = MR::getFovy();
}

void CameraMovement(void* self)
{
  movement__14CameraDirectorFv(self);
  PublishGameCamera();
  const bool galaxy = GalaxyView();
  gOut.dbg.galaxy_view = galaxy;
  // Cutscenes and the Galaxy view keep the game's camera (Mario stays hidden in the latter).
  if (!gFollowing || gDemo || galaxy)
  {
    if (gNearOverridden)
    {
      MR::setNearZ(gGameNearZ);
      gNearOverridden = false;
    }
    return;
  }
  if (!gNearOverridden)
  {
    gGameNearZ = MR::getNearZ();
    gOut.dbg.game_near_z_x100 = static_cast<u32>(gGameNearZ * 100.f);
    gNearOverridden = true;
  }
  // From Mario's feet this frame (not the host's echo, a frame late) so the view keeps 60 fps;
  // the offset is where Minecraft's camera sits from the player (eyes, or behind/in front).
  f32 eye[3];
  gxc::CameraEye(gOut.mbx.anchor_pos, gOut.mbx.cam_offset, eye);
  f32 view[12];
  gxc::LookAtView(eye, gOut.mbx.look, gOut.mbx.up, view);
  TPos3f mtx;
  for (int r = 0; r < 3; r++)
    for (int c = 0; c < 4; c++)
      mtx.mMtx[r][c] = view[4 * r + c];
  MR::setCameraViewMtx(mtx, false, false, TVec3f(0.f, 0.f, 0.f));
  MR::setFovy(gOut.mbx.fov_y);
  MR::setNearZ(EYE_NEAR_Z);
}
}  // namespace

// Vtable slots (symbols/SB4E.txt): __vt__10MarioActor + 0xC init, + 0x14 movement, + 0x18 draw;
// __vt__14CameraDirector + 0x14 movement.
kmWritePointer(0x806C7448 + 0xC, MarioInit);
kmWritePointer(0x806C7448 + 0x14, MarioMovement);
kmWritePointer(0x806C7448 + 0x18, MarioDraw);
kmWritePointer(0x8067C4D0 + 0x14, CameraMovement);
