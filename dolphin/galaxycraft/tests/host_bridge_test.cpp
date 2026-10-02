#include <array>
#include <cstring>
#include <filesystem>
#include <unistd.h>

#include "FakeGuestMemory.h"
#include "HostBridge.h"
#include "Shm.h"
#include "TestRunner.h"
#include "galaxycraft_protocol.h"

using namespace gxc;

namespace
{
constexpr u32 MBX = 0x80401000u;
constexpr u32 KCL = 0x80500000u;
constexpr std::array<float, 12> IDENTITY = {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0};
// KCL header offsets (positions, normals, prisms, octree) of the fixture's 300-byte KCL.
constexpr std::array<u32, 4> KCL_HEADER = {0x40, 0x80, 0xC0, 300};

// The fixture's KCL bytes: a valid header of offsets followed by a recognizable pattern.
std::vector<u8> KclBytes()
{
  std::vector<u8> kcl(300);
  for (size_t i = 0; i < kcl.size(); i++)
    kcl[i] = static_cast<u8>(i % 251);
  for (int k = 0; k < 4; k++)
    for (int b = 0; b < 4; b++)
      kcl[4 * k + b] = static_cast<u8>(KCL_HEADER[k] >> (24 - 8 * b));
  return kcl;
}

std::vector<u8> SentKcl(const std::vector<Msg>& msgs)
{
  std::vector<u8> got;
  for (auto& m : msgs)
    if (m.type == GXC_MSG_KCL_CHUNK)
      got.insert(got.end(), m.payload.begin() + sizeof(GxcKclChunk), m.payload.end());
  return got;
}

struct Part
{
  u32 id, addr, size;
  std::array<float, 12> mtx = IDENTITY;
};

void WriteMailbox(FakeGuestMemory& mem, u32 at, u32 scene, const std::vector<Part>& parts)
{
  mem.PutBytes(at, GXC_MBX_MAGIC, 8);
  mem.PutU32(at + 8, GXC_MBX_VERSION);
  mem.PutU32(at + 20, scene);
  mem.PutF32(at + 24, 0), mem.PutF32(at + 28, -1), mem.PutF32(at + 32, 0);  // gravity
  mem.PutF32(at + 36, 1), mem.PutF32(at + 40, 2), mem.PutF32(at + 44, 3);   // anchor (Mario)
  mem.PutU32(at + offsetof(GxcMailbox, part_count), static_cast<u32>(parts.size()));
  for (size_t i = 0; i < parts.size(); i++)
  {
    const u32 p = at + offsetof(GxcMailbox, parts) + static_cast<u32>(i) * 60;
    mem.PutU32(p, parts[i].id);
    mem.PutU32(p + 4, parts[i].addr);
    mem.PutU32(p + 8, parts[i].size);
    for (int k = 0; k < 12; k++)
      mem.PutF32(p + 12 + 4 * k, parts[i].mtx[k]);
  }
}

struct Fixture
{
  std::string path = "/tmp/gxc_host_test_" + std::to_string(getpid());
  std::unique_ptr<Shm> shm = Shm::Create(path);
  u64 now = 10'000;
  HostBridge bridge{*shm, [this] { return now; }};
  FakeGuestMemory mem;

  Fixture()
  {
    const std::vector<u8> kcl = KclBytes();
    mem.PutBytes(KCL, kcl.data(), 300);
    WriteMailbox(mem, MBX, 3, {{7, KCL, 300}});
  }
  ~Fixture() { std::filesystem::remove(path); }

  void Tick() { bridge.Tick(mem); }

  std::vector<Msg> Drain()
  {
    Ring ring(*shm, GXC_OFF_RING_S2M);
    std::vector<Msg> out;
    while (auto m = ring.Pop())
      out.push_back(*m);
    return out;
  }

  void ModReports(u64 frame, Vec3 pos, Vec3 cam_offset = {}, u32 view = GXC_VIEW_FIRST)
  {
    shm->SetU64(offsetof(GxcHeader, mod_heartbeat_ms), now);
    WritePlayer(*shm, {0, frame, pos, {0, 0, 1}, {0, 1, 0}, 70.f, 162.f, cam_offset, view});
  }

  // The game's camera at (10, 20, 30) looking -z, FOV 45; Mario faces +x.
  void GameCameraInMailbox()
  {
    const float vals[] = {10, 20, 30, 0, 0, -1, 0, 1, 0, 45, 1, 0, 0};
    for (int i = 0; i < 13; i++)
      mem.PutF32(MBX + offsetof(GxcMailbox, cam_pos) + 4 * i, vals[i]);
  }
};

size_t Count(const std::vector<Msg>& msgs, u16 type)
{
  size_t n = 0;
  for (auto& m : msgs)
    n += m.type == type;
  return n;
}

u32 PayloadU32(const Msg& m, size_t off)
{
  u32 v;
  std::memcpy(&v, m.payload.data() + off, 4);
  return v;
}
}  // namespace

TEST(finds_mailbox_and_publishes_scene)
{
  Fixture f;
  f.Tick();
  CHECK(f.bridge.HasMailbox());
  CHECK(f.shm->GetU32(offsetof(GxcHeader, magic)) == GXC_MAGIC);
  CHECK((f.shm->GetU32(offsetof(GxcHeader, host_flags)) & 1) == 1);
  auto msgs = f.Drain();
  CHECK(!msgs.empty() && msgs[0].type == GXC_MSG_SCENE_CHANGE && PayloadU32(msgs[0], 0) == 3);
  CHECK(Count(msgs, GXC_MSG_PART_UPSERT) == 1);
  for (auto& m : msgs)
    if (m.type == GXC_MSG_PART_UPSERT)
      CHECK(PayloadU32(m, 0) == 7 && PayloadU32(m, 4) == 300);
  CHECK(SentKcl(msgs) == KclBytes());
}

TEST(kcl_header_pointers_sent_as_offsets)
{
  // In RAM, KCollisionServer::setData has turned the header offsets into absolute pointers.
  Fixture f;
  for (int k = 0; k < 4; k++)
    f.mem.PutU32(KCL + 4 * k, KCL + KCL_HEADER[k]);
  f.Tick();
  CHECK(SentKcl(f.Drain()) == KclBytes());
}

TEST(kcl_header_out_of_range_ignored)
{
  Fixture f;
  f.mem.PutU32(KCL + 12, KCL + 301);  // octree pointer past the reported size
  f.Tick();
  auto msgs = f.Drain();
  CHECK(Count(msgs, GXC_MSG_PART_UPSERT) == 0 && Count(msgs, GXC_MSG_KCL_CHUNK) == 0);
  f.mem.PutU32(KCL + 12, 0x80001000u);  // pointer somewhere else entirely
  f.Tick();
  CHECK(Count(f.Drain(), GXC_MSG_PART_UPSERT) == 0);
  f.mem.PutU32(KCL + 12, KCL + 300);  // fixed: the part goes out
  f.Tick();
  CHECK(Count(f.Drain(), GXC_MSG_PART_UPSERT) == 1);
}

TEST(anchor_until_fresh_player)
{
  Fixture f;
  f.Tick();
  auto w = ReadWorld(*f.shm);
  CHECK(w && (w->flags & GXC_WORLD_ANCHOR) && w->query_pos.y == 2.f && w->gravity.y == -1.f);
  f.ModReports(1, {10, 20, 30});
  f.Tick();
  w = ReadWorld(*f.shm);
  CHECK(w && (w->flags & GXC_WORLD_ANCHOR) == 0);
}

TEST(follow_writes_flags_and_mario_position)
{
  Fixture f;
  f.Tick();
  f.ModReports(1, {10, 20, 30});
  f.Tick();
  CHECK(f.bridge.Following());
  CHECK(f.mem.GetU32(MBX + 52) == GXC_MBX_FOLLOW);
  CHECK(f.mem.GetF32(MBX + 56) == 10.f && f.mem.GetF32(MBX + 64) == 30.f);  // echo
  CHECK(f.mem.GetF32(MBX + 68) == 0.f && f.mem.GetF32(MBX + 76) == 1.f);   // look
  CHECK(f.mem.GetF32(MBX + 84) == 1.f);                                     // up
  CHECK(f.mem.GetF32(MBX + 92) == 70.f && f.mem.GetF32(MBX + 96) == 162.f);
  // Minecraft follows Mario: the query is Mario's position, never the player's.
  auto w = ReadWorld(*f.shm);
  CHECK(w && (w->flags & GXC_WORLD_FOLLOW) && (w->flags & GXC_WORLD_ANCHOR) == 0);
  CHECK(w->query_pos.x == 1.f && w->query_pos.y == 2.f && w->query_pos.z == 3.f);
}

TEST(follow_writes_camera_offset)
{
  Fixture f;
  f.Tick();
  f.ModReports(1, {10, 20, 30}, {0, 130, -320}, GXC_VIEW_BACK);
  f.Tick();
  CHECK(f.mem.GetU32(MBX + 52) == GXC_MBX_FOLLOW);
  CHECK(f.mem.GetF32(MBX + offsetof(GxcMailbox, cam_offset) + 4) == 130.f);
  CHECK(f.mem.GetF32(MBX + offsetof(GxcMailbox, cam_offset) + 8) == -320.f);
  CHECK(!f.bridge.GalaxyView());
}

TEST(no_camera_offset_means_eye_height_up)
{
  // An old-style pose (or a dev follow) without an offset still puts the camera in the eyes.
  Fixture f;
  f.Tick();
  f.ModReports(1, {10, 20, 30});
  f.Tick();
  CHECK(f.mem.GetF32(MBX + offsetof(GxcMailbox, cam_offset)) == 0.f);
  CHECK(f.mem.GetF32(MBX + offsetof(GxcMailbox, cam_offset) + 4) == 162.f);
}

TEST(galaxy_view_flag)
{
  Fixture f;
  f.Tick();
  f.ModReports(1, {10, 20, 30}, {0, 162, 0}, GXC_VIEW_GALAXY);
  f.Tick();
  CHECK(f.mem.GetU32(MBX + 52) == (GXC_MBX_FOLLOW | GXC_MBX_GALAXY_VIEW));
  CHECK(f.bridge.GalaxyView());
  f.bridge.SetMinecraftMode(false);
  f.Tick();
  CHECK(!f.bridge.GalaxyView() && f.mem.GetU32(MBX + 52) == 0);
}

TEST(publishes_game_camera)
{
  Fixture f;
  f.GameCameraInMailbox();
  f.mem.PutU32(MBX + offsetof(GxcMailbox, game_seq), 1);
  f.Tick();
  auto c = ReadGameCamera(*f.shm);
  CHECK(c && (c->flags & GXC_GAMECAM_VALID) && (c->flags & GXC_GAMECAM_DEMO) == 0);
  CHECK(c->cam_pos.x == 10.f && c->cam_dir.z == -1.f && c->cam_up.y == 1.f && c->fov_y == 45.f);
  CHECK(c->mario_pos.y == 2.f && c->mario_front.x == 1.f);
  f.mem.PutU32(MBX + offsetof(GxcMailbox, game_flags), GXC_MBX_GAME_DEMO);
  f.mem.PutU32(MBX + offsetof(GxcMailbox, game_seq), 2);
  f.Tick();
  c = ReadGameCamera(*f.shm);
  CHECK(c && (c->flags & GXC_GAMECAM_DEMO));
}

TEST(game_camera_invalid_without_fov)
{
  // A module that never filled the camera (or a menu without one) is not a camera to follow.
  Fixture f;
  f.Tick();
  auto c = ReadGameCamera(*f.shm);
  CHECK(c && (c->flags & GXC_GAMECAM_VALID) == 0);
}

TEST(matrix_only_change_sends_upsert_without_chunks)
{
  Fixture f;
  f.Tick();
  f.Drain();
  Part moved{7, KCL, 300};
  moved.mtx[3] = 50;
  WriteMailbox(f.mem, MBX, 3, {moved});
  f.Tick();
  auto msgs = f.Drain();
  CHECK(Count(msgs, GXC_MSG_PART_UPSERT) == 1);
  CHECK(Count(msgs, GXC_MSG_KCL_CHUNK) == 0);
  CHECK(Count(msgs, GXC_MSG_SCENE_CHANGE) == 0);
}

TEST(unchanged_parts_send_nothing)
{
  Fixture f;
  f.Tick();
  f.Drain();
  f.Tick();
  CHECK(f.Drain().empty());
}

TEST(removed_part_sends_remove)
{
  Fixture f;
  f.Tick();
  f.Drain();
  WriteMailbox(f.mem, MBX, 3, {});
  f.Tick();
  auto msgs = f.Drain();
  CHECK(msgs.size() == 1 && msgs[0].type == GXC_MSG_PART_REMOVE && PayloadU32(msgs[0], 0) == 7);
}

TEST(hello_republishes_and_reanchors)
{
  Fixture f;
  f.Tick();
  f.ModReports(1, {10, 20, 30});
  f.Tick();
  f.Drain();
  const u32 hello = 1;
  Ring(*f.shm, GXC_OFF_RING_M2S).Push(GXC_MSG_HELLO, &hello, 4);
  f.Tick();
  auto msgs = f.Drain();
  CHECK(Count(msgs, GXC_MSG_SCENE_CHANGE) == 1 && Count(msgs, GXC_MSG_PART_UPSERT) == 1);
  CHECK(Count(msgs, GXC_MSG_KCL_CHUNK) == 1);
  auto w = ReadWorld(*f.shm);
  CHECK(w && (w->flags & GXC_WORLD_ANCHOR) && (w->flags & GXC_WORLD_FOLLOW));
}

TEST(stale_mod_stops_following)
{
  Fixture f;
  f.Tick();
  f.ModReports(1, {10, 20, 30});
  f.Tick();
  CHECK(f.bridge.Following());
  f.now += 2500;
  f.Tick();
  CHECK(!f.bridge.Following());
  CHECK(f.mem.GetU32(MBX + 52) == 0);
}

TEST(mailbox_moved_is_rescanned)
{
  Fixture f;
  f.Tick();
  CHECK(f.bridge.HasMailbox());
  f.mem.PutBytes(MBX, "XXXXXXXX", 8);  // module reloaded elsewhere
  constexpr u32 MBX2 = 0x90000100u;
  WriteMailbox(f.mem, MBX2, 4, {{7, KCL, 300}});
  f.mem.writes.clear();
  for (int i = 0; i < 61 && !f.bridge.HasMailbox(); i++)
    f.Tick();
  for (int i = 0; i < 61 && f.bridge.HasMailbox() && f.mem.GetU32(MBX2 + 16) == 0; i++)
    f.Tick();
  CHECK(f.bridge.HasMailbox());
  for (u32 a : f.mem.writes)
    CHECK(a < MBX || a >= MBX + sizeof(GxcMailbox));
  CHECK(f.mem.GetU32(MBX2 + 16) != 0);  // host_seq: host writes the new mailbox
}

TEST(bad_kcl_address_ignored)
{
  Fixture f;
  WriteMailbox(f.mem, MBX, 3, {{9, 0x12345678u, 1000}, {7, KCL, 300}});
  f.Tick();
  auto msgs = f.Drain();
  CHECK(Count(msgs, GXC_MSG_PART_UPSERT) == 1);
  for (auto& m : msgs)
    if (m.type == GXC_MSG_PART_UPSERT)
      CHECK(PayloadU32(m, 0) == 7);
}

TEST(dev_follow_overrides_mod)
{
  Fixture f;
  f.Tick();
  CHECK(!f.bridge.Following());  // no mod
  f.bridge.SetDevFollow(PlayerState{0, 0, {}, {0, 0, -1}, {0, 0, 0}, 70.f, 162.f});
  f.Tick();
  CHECK(f.bridge.Following());
  CHECK(f.mem.GetU32(MBX + 52) == GXC_MBX_FOLLOW);
  CHECK(f.mem.GetF32(MBX + 72) == 0.f && f.mem.GetF32(MBX + 76) == -1.f);
  // No up given: opposite of the mailbox gravity (0, -1, 0).
  CHECK(f.mem.GetF32(MBX + 80) == 0.f && f.mem.GetF32(MBX + 84) == 1.f);
  CHECK(f.mem.GetF32(MBX + 92) == 70.f && f.mem.GetF32(MBX + 96) == 162.f);
  f.bridge.SetDevFollow(std::nullopt);
  f.Tick();
  CHECK(!f.bridge.Following() && f.mem.GetU32(MBX + 52) == 0);
}

TEST(wiimote_mode_clears_follow)
{
  Fixture f;
  f.Tick();
  f.ModReports(1, {10, 20, 30});
  f.Tick();
  CHECK(f.bridge.MinecraftMode() && f.bridge.Following());
  f.bridge.SetMinecraftMode(false);
  f.ModReports(2, {11, 20, 30});
  f.Tick();
  CHECK(!f.bridge.MinecraftMode() && !f.bridge.Following());
  CHECK(f.mem.GetU32(MBX + 52) == 0);
  CHECK(f.shm->GetU32(offsetof(GxcHeader, host_flags)) == 0);
  // A dev follow does not get through either.
  f.bridge.SetDevFollow(PlayerState{0, 0, {}, {0, 0, -1}, {0, 1, 0}, 70.f, 162.f});
  f.Tick();
  CHECK(!f.bridge.Following() && f.mem.GetU32(MBX + 52) == 0);
}

TEST(minecraft_mode_on_republishes_and_reanchors)
{
  Fixture f;
  f.Tick();
  f.ModReports(1, {10, 20, 30});
  f.Tick();
  f.bridge.SetMinecraftMode(false);
  f.Tick();
  f.Drain();
  f.bridge.SetMinecraftMode(true);
  f.Tick();
  auto msgs = f.Drain();
  CHECK(!msgs.empty() && msgs[0].type == GXC_MSG_SCENE_CHANGE);
  CHECK(Count(msgs, GXC_MSG_PART_UPSERT) == 1);
  auto w = ReadWorld(*f.shm);
  CHECK(w && (w->flags & GXC_WORLD_ANCHOR) != 0);
  CHECK(f.shm->GetU32(offsetof(GxcHeader, host_flags)) == 1);
  f.ModReports(2, {10, 20, 30});
  f.Tick();
  w = ReadWorld(*f.shm);
  CHECK(w && (w->flags & GXC_WORLD_ANCHOR) == 0 && f.bridge.Following());
}

TEST(in_game_rule)
{
  Fixture f;
  u32 seq = 0;
  auto frame = [&] { f.mem.PutU32(MBX + 12, ++seq); f.Tick(); };
  frame();
  frame();
  CHECK(f.bridge.InGame());  // game_seq advancing, gravity (0,-1,0), no DEMO
  for (int i = 0; i < 30; i++)
    f.Tick();
  CHECK(f.bridge.InGame());  // advanced within the last 30 ticks
  f.Tick();
  CHECK(!f.bridge.InGame());  // 31 ticks without a game frame: no Mario
  frame();
  CHECK(f.bridge.InGame());
  f.mem.PutF32(MBX + 28, 0);  // title screen: no gravity
  frame();
  CHECK(!f.bridge.InGame());
  f.mem.PutF32(MBX + 28, -1);
  f.mem.PutU32(MBX + 48, GXC_MBX_GAME_DEMO);  // cutscene
  frame();
  CHECK(!f.bridge.InGame());
  f.mem.PutU32(MBX + 48, 0);
  frame();
  CHECK(f.bridge.InGame());
}

TEST(no_mailbox_reports_unlinked)
{
  Fixture f;
  f.mem.PutBytes(MBX, "XXXXXXXX", 8);
  f.Tick();
  CHECK(!f.bridge.HasMailbox());
  CHECK((f.shm->GetU32(offsetof(GxcHeader, host_flags)) & 1) == 0);
  CHECK(f.shm->GetU64(offsetof(GxcHeader, host_heartbeat_ms)) == f.now);
}
