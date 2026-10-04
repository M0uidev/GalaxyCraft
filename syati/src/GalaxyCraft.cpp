// GalaxyCraft module for SMG2 (SB4E), loaded by Syati's loader as CustomCode_SB4E.bin.
// Publishes the GXCRMBX1 mailbox that Dolphin's host bridge mirrors to the Minecraft mod, and
// while the host sets GXC_MBX_FOLLOW hides Mario and puts the camera in his eyes, looking where
// Minecraft looks. Mario still moves by his own physics, played through the emulated Wii Remote.
#include "syati.h"

#include "CodePatch.h"
#include "HeldItem.h"
#include "Kcl.h"
#include "Parts.h"
#include "ViewMath.h"
#include "VoxelPlanet.h"
#include "galaxycraft_protocol.h"

// Originals, by their mangled names in symbols/SB4E.txt.
extern "C" void* getCollisionDirector__2MRFv();
extern "C" void init__10MarioActorFRC12JMapInfoIter(void* self, const void* iter);
extern "C" void movement__10MarioActorFv(void* self);
extern "C" void movement__14CameraDirectorFv(void* self);
extern "C" void draw__10MarioActorCFv(const void* self);
extern "C" void draw__17StarPointerLayoutCFv(const void* self);
extern "C" void draw__15StarPointerBlurCFv(const void* self);
extern "C" void movement__13ClippingJudgeFv(void* self);
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
  u32 voxel_stats;         // &gVoxelStats (VoxelPlanet.h)
  u32 radius_patch[3];     // Mario on a voxel planet: radius patches on, 100 * radius, 100 * binder
  u32 mario_state[4];      // Mario::getCurrentStatus, then the words at Mario + 8, + 0xC, + 0x10
  // Mario's last frames (history_next is the next slot): position before and after his movement,
  // his velocity (Mario + 0x1D8), status, flags (Mario + 0xC, + 0x10) and ground triangle.
  u32 history_next;
  struct
  {
    f32 before[3], after[3], velocity[3];
    u32 status, flags_c, flags_10, ground;
  } history[6];
  u32 clip_rescued;  // frustum tests our camera clipped and the game's camera kept (see ClipFrustum)
  u32 held_kind;     // GXC_HELD_* in Steve's hand (0 also if it could not be built)
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
// Mario's movement is sized for SMG2's levels, not for 80-unit blocks, with radii loaded from the
// small-data constants: walls stay 80 units away (Mario::checkAllWall, on foot: not swimming,
// Yoshi or the special modes), the ground is felt by three probes 50 units around him
// (Mario::checkGround) and balls of 50 and 40 around his feet push him out of the map
// (Mario::checkBaseTransBall, Mario::createAtField, one of 50 in Mario::checkStep). So a 1-block hole holds him up and a 1-block tunnel keeps him out.
// On a voxel planet those loads are pointed at a value of ours (GxcPlanet.mario_radius) so he fits
// where Steve does; off it, the code is the game's again. Each is patched only if the word there
// is the one expected; none of them is followed by a read of r12 before the next call. His binder (the sphere that pushes him out
// of the map, radius 60 around a centre 60 above his feet) shrinks to the same radius.
struct LoadPatch
{
  u32 site;
  u32 original;
  u32 stub[3];
  f32 value;
  bool on;
};
LoadPatch gRadiusPatches[] = {
    {0x80390dfc, 0xC3C20DE0},  // checkAllWall: lfs f30, 80
    {0x80391730, 0xC0220DDC},  // checkGround: lfs f1, 50 (the probes' distance)
    {0x8038e714, 0xC0220DDC},  // checkBaseTransBall: lfs f1, 50 (three ball radii)
    {0x8038e730, 0xC0220DDC},
    {0x8038e7e0, 0xC0220DDC},
    {0x8038e7fc, 0xC0220DDC},
    {0x8038e87c, 0xC0220D3C},  //   lfs f1, 40
    {0x8038e898, 0xC0220D3C},
    {0x803aaeac, 0xC0220DDC},  // checkStep: lfs f1, 50 (the ball ahead of his feet)
    {0x803aaec4, 0xC0220DDC},
    {0x8038e95c, 0xC3620DDC},  // createAtField: lfs f27, 50 (the ball that pushes him off walls,
    {0x8038e99c, 0xC3620D3C},  //   centred as high as it is wide), and 40 in one of his states
    {0x8038e9b0, 0xC0020D3C},  //   lfs f0, 40: at least that wide when pushed (tryPushToVelocity)
    {0x80388a48, 0xC0220D38},  // update: lfs f1, 150, that ball's width in one of his states
    {0x803a6164, 0xC2020DE0},  // checkVerticalPress: lfs f16, 80, the ball that tells he is crushed
};
const u32 RADIUS_PATCHES = sizeof(gRadiusPatches) / sizeof(gRadiusPatches[0]);
f32 gOwnBinderRadius = 0.f;  // while shrunk; 0: his own is on

void FlushCode(void* p, u32 size)
{
  DCFlushRange(p, size);
  ICInvalidateRange(p, size);
}

void SetLoadPatch(LoadPatch& p, bool on, f32 value)
{
  u32* site = reinterpret_cast<u32*>(p.site);
  p.value = value;
  if (on == p.on)
    return;
  if (on)
  {
    if (*site != p.original || !gxc::IsLfsR2(p.original))
      return;
    gxc::BuildLoadStub(p.original, p.site, reinterpret_cast<u32>(&p.value), reinterpret_cast<u32>(p.stub), p.stub);
    FlushCode(p.stub, sizeof(p.stub));
    *site = gxc::EncodeBranch(p.site, reinterpret_cast<u32>(p.stub));
  }
  else
  {
    *site = p.original;
  }
  FlushCode(site, 4);
  p.on = on;
}
bool gDemo = false;  // a cutscene owns Mario and the camera this frame
// Mario (Steve) is not drawn (first person, outside cutscenes). MR::hidePlayer is no use: Mario then
// ignores the stick. Skipping MarioActor::draw leaves him playable, and his shadow stays.
bool gHidden = false;
// In Mario's eyes the game's near plane (made for a camera metres behind him) clips whatever is
// close; while following the camera draws from almost at the eye. 10 units = 1/8 block.
const f32 EYE_NEAR_Z = 10.f;
f32 gGameNearZ = 0.f;
bool gNearOverridden = false;
// SMG2 clips (freezes, and turns off the hit sensors and often the collision of) every actor
// outside the camera's viewing volume, which starts 500 units ahead of the camera. That suits its
// camera metres behind Mario, but from his eyes it clips what is at his feet, beside or behind him,
// so he walks through it. While our camera is on, an actor is clipped only if it is outside both
// volumes: ours (what is drawn) and the game's camera's (what it would have kept).
// ClippingJudge (read live on SB4E): 9 viewing volumes from +0x14, each 6 planes (normal,
// distance) the actor's ball must not be wholly outside of; far clip level 0 uses the first,
// level n > 0 the (n + 1)th (the second is never built).
const u32 CLIP_VOLUMES = 9;
const u32 CLIP_VOLUME_FLOATS = 6 * 4;
const u32 JUDGE_VOLUMES = 0x14;
f32 gGameView[12];  // the game's camera this frame, before our override
f32 gGameFovy = 0.f;
f32 gGameVolumes[CLIP_VOLUMES * CLIP_VOLUME_FLOATS];
bool gGameVolumesOn = false;
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

// MEM2 ends at 64 MiB on a Wii; GalaxyCraft's Dolphin gives it more (voxel planets live there).
// The OS keeps the end of MEM2 at 0x80003120.
bool IsRam(u32 addr)
{
  const u32 mem2_end = *reinterpret_cast<const u32*>(0x80003120);
  return (addr >= 0x80000000u && addr < 0x81800000u) ||
         (addr >= 0x90000000u && addr < (mem2_end > 0x94000000u ? mem2_end : 0x94000000u));
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
  gOwnBinderRadius = 0.f;
  VoxelPlanetCreate(&gOut.mbx.inbox_addr, &gOut.mbx.inbox_size);
  HeldItemCreate();
  // The stage's name, so the mod keeps one planet per galaxy.
  const char* stage = MR::getCurrentStageName();
  for (u32 i = 0; i < sizeof(gOut.mbx.stage_name); i++)
    gOut.mbx.stage_name[i] = 0;
  for (u32 i = 0; stage && stage[i] && i + 1 < sizeof(gOut.mbx.stage_name); i++)
    gOut.mbx.stage_name[i] = stage[i];
  gOut.dbg.voxel_stats = reinterpret_cast<u32>(&gVoxelStats);
}

void MarioMovement(void* self)
{
  const TVec3f* at = MR::getPlayerPos();
  const f32 before[3] = {at->x, at->y, at->z};
  movement__10MarioActorFv(self);
  {
    const u8* mb = reinterpret_cast<const u8*>(reinterpret_cast<const MarioActor*>(self)->mMario);
    if (IsRam(reinterpret_cast<u32>(mb)))
    {
      u32 n = gOut.dbg.history_next % 6;
      gOut.dbg.history_next = n + 1;
      at = MR::getPlayerPos();
      for (int k = 0; k < 3; k++)
      {
        gOut.dbg.history[n].before[k] = before[k];
        gOut.dbg.history[n].after[k] = (&at->x)[k];
        gOut.dbg.history[n].velocity[k] = reinterpret_cast<const f32*>(mb + 0x1D8)[k];
      }
      gOut.dbg.history[n].status = reinterpret_cast<const Mario*>(mb)->getCurrentStatus();
      gOut.dbg.history[n].flags_c = *reinterpret_cast<const u32*>(mb + 0xC);
      gOut.dbg.history[n].flags_10 = *reinterpret_cast<const u32*>(mb + 0x10);
      gOut.dbg.history[n].ground = *reinterpret_cast<const u32*>(mb + 0x4C4);
    }
  }

  gFollowing = HostFollows();
  gOut.dbg.following = gFollowing;
  gDemo = MR::isDemoActive();
  gOut.dbg.demo = gDemo;
  // Steve (Mario's model) is hidden only in first person; cutscenes always show him.
  gHidden = !gxc::MarioVisible(gFollowing, gDemo, (gOut.mbx.host_flags & GXC_MBX_THIRD_PERSON) != 0);
  // What the player holds in Minecraft, in Steve's hand while the mod plays him.
  HeldItemFrame(static_cast<const LiveActor*>(self), !gHidden && gFollowing);
  gOut.dbg.held_kind = HeldItemKind();
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
  // On a voxel planet Mario's collision shrinks to fit 1-block holes and tunnels (see
  // gRadiusPatches). Patched after his movement: it counts from the next frame.
  {
    const f32 feet[3] = {mario->x, mario->y, mario->z};
    f32 radius = 0.f;
    const bool small = VoxelPlanetMarioRadius(feet, &radius);
    u32 on = 0;
    for (u32 i = 0; i < RADIUS_PATCHES; i++)
    {
      SetLoadPatch(gRadiusPatches[i], small, radius);
      on += gRadiusPatches[i].on;
    }
    LiveActor* actor = static_cast<LiveActor*>(self);
    if (small && IsRam(reinterpret_cast<u32>(actor->mBinder)))
    {
      if (gOwnBinderRadius == 0.f)
        gOwnBinderRadius = MR::getBinderRadius(actor);
      MR::setBinderRadius(actor, radius);
    }
    else if (gOwnBinderRadius != 0.f)
    {
      MR::setBinderRadius(actor, gOwnBinderRadius);
      gOwnBinderRadius = 0.f;
    }
    gOut.dbg.radius_patch[0] = on;
    gOut.dbg.radius_patch[1] = static_cast<u32>(radius * 100.f);
    const Mario* m = reinterpret_cast<const MarioActor*>(self)->mMario;
    if (IsRam(reinterpret_cast<u32>(m)))
    {
      gOut.dbg.mario_state[0] = m->getCurrentStatus();
      for (int k = 0; k < 3; k++)
        gOut.dbg.mario_state[1 + k] = reinterpret_cast<const u32*>(m)[2 + k];
    }
    if (IsRam(reinterpret_cast<u32>(actor->mBinder)))
      gOut.dbg.radius_patch[2] = static_cast<u32>(MR::getBinderRadius(actor) * 100.f);
    // F3+B in Minecraft: his collision drawn as his movement sees it (the radius counts from the
    // next frame, as the patches do).
    if ((gOut.mbx.host_flags & GXC_MBX_HITBOXES) && small && IsRam(reinterpret_cast<u32>(m)))
    {
      // checkBaseTransBall's balls: from Mario's position plus his vector at +0x160, one 50 along
      // the one at +0x1F0, one 40 against it and one right there (offsets not patched).
      MarioHitbox box;
      TVec3f front(0.f, 0.f, 0.f);
      MR::getPlayerFrontVec(&front);
      const u8* mb = reinterpret_cast<const u8*>(m);
      const f32* base = reinterpret_cast<const f32*>(mb + 0x160);
      const f32* along = reinterpret_cast<const f32*>(mb + 0x1F0);
      const f32 offsets[3] = {50.f, -40.f, 0.f};
      for (int k = 0; k < 3; k++)
      {
        box.feet[k] = feet[k];
        box.up[k] = -gOut.mbx.gravity[k];
        for (int b = 0; b < 3; b++)
          box.balls[b][k] = feet[k] + base[k] + offsets[b] * along[k];
      }
      box.front[0] = front.x, box.front[1] = front.y, box.front[2] = front.z;
      box.radius = radius;
      VoxelPlanetHitbox(&box);
    }
    else
    {
      VoxelPlanetHitbox(0);
    }
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
  VoxelPlanetFrame(gOut.mbx.scene_id, &gOut.mbx.inbox_addr, &gOut.mbx.inbox_size);
  gOut.mbx.game_seq++;  // last: the host reads a consistent frame when this moves
}

void MarioDraw(const void* self)
{
  if (!gHidden)
    draw__10MarioActorCFv(self);
}

// Playing in Minecraft's view the IR sits under its crosshair, which stands in for the star
// pointer: the pointer and its trail are not drawn, but still aim. The host clears the flag in
// menus, cutscenes and the Galaxy view, and goes quiet if the bridge stops (checked here, as
// Mario's movement, which runs the watchdog, stops while the game is paused).
bool PointerHidden()
{
  return (gOut.mbx.host_flags & GXC_MBX_HIDE_POINTER) != 0 && gFramesSinceHost < HOST_TIMEOUT_FRAMES;
}

void StarPointerLayoutDraw(const void* self)
{
  if (!PointerHidden())
    draw__17StarPointerLayoutCFv(self);
}

void StarPointerBlurDraw(const void* self)
{
  if (!PointerHidden())
    draw__15StarPointerBlurCFv(self);
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
  const MtxPtr game = MR::getCameraViewMtx();
  for (int r = 0; r < 3; r++)
    for (int c = 0; c < 4; c++)
      gGameView[4 * r + c] = game[r][c];
  gGameFovy = MR::getFovy();
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

// The volumes of the camera in place (ours while overridden), then, while our camera is on, the
// game camera's: built by the game's own code with its view put back for the call.
void ClippingJudgeMovement(void* self)
{
  movement__13ClippingJudgeFv(self);
  gGameVolumesOn = false;
  if (!gNearOverridden || gGameFovy <= 0.f)
    return;
  f32* volumes = reinterpret_cast<f32*>(reinterpret_cast<u8*>(self) + JUDGE_VOLUMES);
  f32 ours[CLIP_VOLUMES * CLIP_VOLUME_FLOATS];
  for (u32 i = 0; i < CLIP_VOLUMES * CLIP_VOLUME_FLOATS; i++)
    ours[i] = volumes[i];
  const MtxPtr view = MR::getCameraViewMtx();
  f32 kept[12];
  for (int r = 0; r < 3; r++)
    for (int c = 0; c < 4; c++)
    {
      kept[4 * r + c] = view[r][c];
      view[r][c] = gGameView[4 * r + c];
    }
  const f32 fovy = MR::getFovy();
  MR::setFovy(gGameFovy);
  movement__13ClippingJudgeFv(self);
  MR::setFovy(fovy);
  for (int r = 0; r < 3; r++)
    for (int c = 0; c < 4; c++)
      view[r][c] = kept[4 * r + c];
  for (u32 i = 0; i < CLIP_VOLUMES * CLIP_VOLUME_FLOATS; i++)
  {
    gGameVolumes[i] = volumes[i];
    volumes[i] = ours[i];
  }
  gGameVolumesOn = true;
}

// JGeometry::THexahedron3<f>::mayIntersectBall3: no plane has the ball wholly outside.
bool MayIntersectBall(const f32* planes, const TVec3f& pos, f32 radius)
{
  for (u32 i = 0; i < 6; i++, planes += 4)
    if (planes[0] * pos.x + planes[1] * pos.y + planes[2] * pos.z - planes[3] < -radius)
      return false;
  return true;
}

// Replaces ClippingJudge::isJudgedToClipFrustum(pos, radius, level) (the 2-argument one is level 0).
bool ClipFrustum(const void* self, const TVec3f& pos, f32 radius, s32 level)
{
  const u32 at = (level == 0 ? 0 : level + 1) * CLIP_VOLUME_FLOATS;
  const f32* ours = reinterpret_cast<const f32*>(reinterpret_cast<const u8*>(self) + JUDGE_VOLUMES);
  if (MayIntersectBall(ours + at, pos, radius))
    return false;
  if (gGameVolumesOn && MayIntersectBall(gGameVolumes + at, pos, radius))
  {
    gOut.dbg.clip_rescued++;
    return false;
  }
  return true;
}

bool ClipFrustumLevel0(const void* self, const TVec3f& pos, f32 radius)
{
  return ClipFrustum(self, pos, radius, 0);
}
}  // namespace

// Vtable slots (symbols/SB4E.txt): __vt__10MarioActor + 0xC init, + 0x14 movement, + 0x18 draw;
// __vt__14CameraDirector + 0x14 movement; __vt__17StarPointerLayout and __vt__15StarPointerBlur
// + 0x18 draw; __vt__13ClippingJudge + 0x14 movement (read live on SB4E).
kmWritePointer(0x806C7448 + 0xC, MarioInit);
kmWritePointer(0x806C7448 + 0x14, MarioMovement);
kmWritePointer(0x806C7448 + 0x18, MarioDraw);
kmWritePointer(0x8067C4D0 + 0x14, CameraMovement);
kmWritePointer(0x806F88A0 + 0x18, StarPointerLayoutDraw);
kmWritePointer(0x806F8300 + 0x18, StarPointerBlurDraw);
kmWritePointer(0x80694EC8 + 0x14, ClippingJudgeMovement);
// ClippingJudge::isJudgedToClipFrustum(pos, radius) and (pos, radius, level).
kmBranch(0x80231100, ClipFrustumLevel0);
kmBranch(0x802311C0, ClipFrustum);
