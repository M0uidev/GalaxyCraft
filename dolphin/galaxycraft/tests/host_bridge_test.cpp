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
  u64 slept = 0;
  std::function<void()> on_sleep;  // what happens meanwhile (the mod ticks)
  HostBridge bridge{*shm, [this] { return now; }, [this](u32 ms) {
                      now += ms, slept += ms;
                      if (on_sleep)
                        on_sleep();
                    }};
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

  void ModReports(u64 frame, Vec3 pos, Vec3 cam_offset = {}, u32 view = GXC_VIEW_FIRST, u32 scene = 3)
  {
    shm->SetU64(offsetof(GxcHeader, mod_heartbeat_ms), now);
    WritePlayer(*shm, {0, frame, pos, {0, 0, 1}, {0, 1, 0}, 70.f, 162.f, cam_offset, view, scene});
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
  CHECK(f.mem.GetU32(MBX + 52) == (GXC_MBX_FOLLOW | GXC_MBX_HIDE_POINTER));
  CHECK(f.mem.GetF32(MBX + 56) == 10.f && f.mem.GetF32(MBX + 64) == 30.f);  // echo
  CHECK(f.mem.GetF32(MBX + 68) == 0.f && f.mem.GetF32(MBX + 76) == 1.f);   // look
  CHECK(f.mem.GetF32(MBX + 84) == 1.f);                                     // up
  CHECK(f.mem.GetF32(MBX + 92) == 70.f && f.mem.GetF32(MBX + 96) == 162.f);
  // Minecraft follows Mario: the query is Mario's position, never the player's.
  auto w = ReadWorld(*f.shm);
  CHECK(w && (w->flags & GXC_WORLD_FOLLOW) && (w->flags & GXC_WORLD_ANCHOR) == 0);
  CHECK(w->query_pos.x == 1.f && w->query_pos.y == 2.f && w->query_pos.z == 3.f);
}

TEST(pose_from_the_old_scene_does_not_anchor)
{
  // Mario dies: the stage reloads (new scene) while the mod, not yet told, still reports a pose
  // anchored in the old one. That pose must not count, or the mod waits for an anchor forever.
  Fixture f;
  f.Tick();
  f.ModReports(1, {10, 20, 30});
  f.Tick();
  f.mem.PutU32(MBX + offsetof(GxcMailbox, scene_id), 4);
  f.Tick();
  f.ModReports(2, {10, 20, 30}, {}, GXC_VIEW_FIRST, 3);
  f.Tick();
  auto w = ReadWorld(*f.shm);
  CHECK(w && w->scene_id == 4 && (w->flags & GXC_WORLD_ANCHOR));
  f.ModReports(3, {10, 20, 30}, {}, GXC_VIEW_FIRST, 4);
  f.Tick();
  w = ReadWorld(*f.shm);
  CHECK(w && (w->flags & GXC_WORLD_ANCHOR) == 0);
}

TEST(follow_writes_camera_offset)
{
  Fixture f;
  f.Tick();
  f.ModReports(1, {10, 20, 30}, {0, 130, -320}, GXC_VIEW_BACK);
  f.Tick();
  CHECK(f.mem.GetU32(MBX + 52) == (GXC_MBX_FOLLOW | GXC_MBX_THIRD_PERSON | GXC_MBX_HIDE_POINTER));
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
  CHECK(f.mem.GetU32(MBX + 52) == (GXC_MBX_FOLLOW | GXC_MBX_GALAXY_VIEW | GXC_MBX_THIRD_PERSON));
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
  CHECK(f.mem.GetU32(MBX + 52) == (GXC_MBX_FOLLOW | GXC_MBX_HIDE_POINTER));
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

TEST(link_on_save_waits_for_a_stage_past_the_title)
{
  Fixture f;
  auto stage = [&](const char* name) {
    char buf[32] = {};
    std::strncpy(buf, name, sizeof(buf) - 1);
    f.mem.PutBytes(MBX + offsetof(GxcMailbox, stage_name), buf, sizeof(buf));
    f.Tick();
  };
  f.bridge.SetLinkOnSave(true);
  CHECK(!f.bridge.MinecraftMode());
  stage("");  // booting: no Mario yet
  CHECK(!f.bridge.MinecraftMode());
  stage("FileSelect");  // title and file select
  CHECK(!f.bridge.MinecraftMode());
  stage("PeachCastleGalaxy");  // a save was picked
  CHECK(f.bridge.MinecraftMode());
  f.bridge.SetMinecraftMode(false);  // Ctrl+G is the player's from here on
  stage("PeachCastleGalaxy");
  CHECK(!f.bridge.MinecraftMode());
  f.bridge.SetMinecraftMode(true);
  stage("FileSelect");  // back to the title: the Wii Remote again
  CHECK(!f.bridge.MinecraftMode());
  stage("MarioFaceShipGalaxy");
  CHECK(f.bridge.MinecraftMode());
}

TEST(boot_space_holds_mario_while_minecraft_is_in_its_menus)
{
  Fixture f;
  f.bridge.SetBootSpace(true);
  f.Tick();  // Minecraft not running yet: a menu (its title is what will show)
  CHECK(f.bridge.InMenu() && !f.bridge.Following());
  CHECK(f.mem.GetU32(MBX + 52) == (GXC_MBX_BOOT_SPACE | GXC_MBX_HOLD));
  f.ModReports(1, {10, 20, 30});  // alive, at its title (not in a world)
  f.Tick();
  CHECK(f.bridge.InMenu() && !f.bridge.Following());
  CHECK(f.mem.GetU32(MBX + 52) == (GXC_MBX_BOOT_SPACE | GXC_MBX_HOLD));
  f.shm->SetU32(offsetof(GxcHeader, mod_flags), GXC_MOD_IN_WORLD);  // a world was entered
  f.ModReports(2, {10, 20, 30});
  f.Tick();
  CHECK(!f.bridge.InMenu() && f.bridge.Following());
  f.ModReports(3, {10, 20, 30});  // its first pose after the switch (the one before re-anchors it)
  f.Tick();
  CHECK(f.mem.GetU32(MBX + 52) == (GXC_MBX_BOOT_SPACE | GXC_MBX_FOLLOW | GXC_MBX_HIDE_POINTER));
  f.shm->SetU32(offsetof(GxcHeader, mod_flags), 0);  // back to the title
  f.ModReports(4, {10, 20, 30});
  f.Tick();
  CHECK(f.bridge.InMenu() && f.mem.GetU32(MBX + 52) == (GXC_MBX_BOOT_SPACE | GXC_MBX_HOLD));
}

TEST(without_boot_space_there_is_no_menu)
{
  Fixture f;
  f.Tick();
  f.ModReports(1, {10, 20, 30});
  f.Tick();
  CHECK(!f.bridge.InMenu() && f.bridge.Following());
  CHECK((f.mem.GetU32(MBX + 52) & (GXC_MBX_BOOT_SPACE | GXC_MBX_HOLD)) == 0);
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

TEST(star_pointer_hidden_only_while_playing_in_the_minecraft_view)
{
  // The IR is centred under Minecraft's crosshair exactly then; elsewhere the pointer is the mouse.
  Fixture f;
  u32 seq = 0;
  auto frame = [&] { f.mem.PutU32(MBX + 12, ++seq); f.Tick(); };
  auto hidden = [&] { return (f.mem.GetU32(MBX + 52) & GXC_MBX_HIDE_POINTER) != 0; };
  frame();
  f.ModReports(1, {10, 20, 30});
  frame();
  CHECK(hidden());
  f.mem.PutU32(MBX + 48, GXC_MBX_GAME_DEMO);  // cutscene
  frame();
  CHECK(!hidden());
  f.mem.PutU32(MBX + 48, 0);
  frame();
  CHECK(hidden());
  for (int i = 0; i < 31; i++)  // paused: no game frames, a menu
    f.Tick();
  CHECK(!hidden());
  f.ModReports(2, {10, 20, 30}, {0, 162, 0}, GXC_VIEW_GALAXY);
  frame();
  CHECK(!hidden());
}

TEST(cutscene_rule)
{
  Fixture f;
  u32 seq = 0;
  auto frame = [&] { f.mem.PutU32(MBX + 12, ++seq); f.Tick(); };
  frame();
  CHECK(!f.bridge.Cutscene());
  f.mem.PutU32(MBX + 48, GXC_MBX_GAME_DEMO);
  frame();
  CHECK(f.bridge.Cutscene());
  for (int i = 0; i < 31; i++)  // no game frames: whatever this is, it is not the cutscene
    f.Tick();
  CHECK(!f.bridge.Cutscene());
  frame();
  CHECK(f.bridge.Cutscene());
  f.mem.PutU32(MBX + 48, 0);
  frame();
  CHECK(!f.bridge.Cutscene());
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

namespace
{
constexpr u32 INBOX = 0x80600000u;

void InboxInMailbox(Fixture& f, u32 size)
{
  f.mem.PutU32(MBX + offsetof(GxcMailbox, inbox_addr), INBOX);
  f.mem.PutU32(MBX + offsetof(GxcMailbox, inbox_size), size);
  f.mem.PutU32(INBOX, 0);
}

void SendPlanet(Fixture& f, u32 id)
{
  GxcPlanet p{id, {1.f, 2.f, 3.f}, 1280.f, 4480.f, 162, 640.f, 28.f};
  Ring(*f.shm, GXC_OFF_RING_M2S).Push(GXC_MSG_PLANET, &p, sizeof(p));
}
}  // namespace

TEST(inbox_gets_planet_and_chunk_big_endian)
{
  Fixture f;
  InboxInMailbox(f, 1024);
  SendPlanet(f, 7);
  std::vector<u8> chunk(sizeof(GxcChunk) + 32 + 8);
  GxcChunk c{5, 2, 32, 8, {1.f, 2.f, 3.f, 4.f}};
  std::memcpy(chunk.data(), &c, sizeof(c));
  for (int k = 0; k < 40; k++)
    chunk[sizeof(c) + k] = static_cast<u8>(0xA0 + k);
  Ring(*f.shm, GXC_OFF_RING_M2S).Push(GXC_MSG_CHUNK, chunk.data(), static_cast<u32>(chunk.size()));
  f.Tick();
  CHECK(f.mem.GetU32(INBOX) == 1);
  CHECK(f.mem.GetU32(INBOX + 4) == 2);
  CHECK(f.mem.GetU32(INBOX + 8) == 8 + 36 + 8 + 72);
  CHECK(f.mem.GetU32(INBOX + 12) == 3);
  const u32 r = INBOX + sizeof(GxcInboxHeader);
  CHECK(f.mem.GetU32(r) == (u32(GXC_MSG_PLANET) << 16) && f.mem.GetU32(r + 4) == 36);
  CHECK(f.mem.GetU32(r + 8) == 7 && f.mem.GetF32(r + 12) == 1.f && f.mem.GetF32(r + 24) == 1280.f);
  const u32 r2 = r + 8 + 36;
  CHECK(f.mem.GetU32(r2) == (u32(GXC_MSG_CHUNK) << 16) && f.mem.GetU32(r2 + 4) == 72);
  CHECK(f.mem.GetU32(r2 + 8) == 5 && f.mem.GetU32(r2 + 12) == 2 && f.mem.GetU32(r2 + 16) == 32);
  CHECK(f.mem.GetF32(r2 + 8 + 16 + 12) == 4.f);  // sphere swapped too
  CHECK(f.mem.GetU32(r2 + 8 + 32) == 0xA0A1A2A3u);  // display list bytes untouched
  CHECK(f.bridge.PendingInbox() == 0);
}

TEST(inbox_gets_the_outline_big_endian)
{
  Fixture f;
  InboxInMailbox(f, 1024);
  GxcOutline o{1, {}};
  o.corners[7][2] = 5.5f;
  Ring(*f.shm, GXC_OFF_RING_M2S).Push(GXC_MSG_OUTLINE, &o, sizeof(o));
  f.Tick();
  const u32 r = INBOX + sizeof(GxcInboxHeader);
  CHECK(f.mem.GetU32(r) == (u32(GXC_MSG_OUTLINE) << 16) && f.mem.GetU32(r + 4) == 100);
  CHECK(f.mem.GetU32(r + 8) == 1 && f.mem.GetF32(r + 8 + 4 + 23 * 4) == 5.5f);
}

TEST(inbox_gets_the_held_item_with_its_sprite_untouched)
{
  Fixture f;
  InboxInMailbox(f, 4096);
  GxcHeld h{GXC_HELD_BLOCK, {0, 0, 2}, {}};
  h.sprite[0] = 0x80, h.sprite[1] = 0x1F;
  Ring(*f.shm, GXC_OFF_RING_M2S).Push(GXC_MSG_HELD, &h, sizeof(h));
  f.Tick();
  const u32 r = INBOX + sizeof(GxcInboxHeader);
  CHECK(f.mem.GetU32(r) == (u32(GXC_MSG_HELD) << 16) && f.mem.GetU32(r + 4) == sizeof(GxcHeld));
  CHECK(f.mem.GetU32(r + 8) == GXC_HELD_BLOCK && f.mem.GetU32(r + 8 + 12) == 2);
  CHECK(f.mem.GetU32(r + 8 + 16) >> 16 == 0x801F);
}

TEST(inbox_gets_atlas_pieces_with_their_texels_untouched)
{
  Fixture f;
  InboxInMailbox(f, 1024);
  struct
  {
    GxcAtlas a;
    u8 data[8];
  } piece{{7, 512, 256, 4, 1000, 64}, {0x12, 0x34, 0x56, 0x78, 0x9A, 0xBC, 0xDE, 0xF0}};
  Ring(*f.shm, GXC_OFF_RING_M2S).Push(GXC_MSG_ATLAS, &piece, sizeof(piece));
  f.Tick();
  const u32 r = INBOX + sizeof(GxcInboxHeader);
  CHECK(f.mem.GetU32(r) == (u32(GXC_MSG_ATLAS) << 16) && f.mem.GetU32(r + 4) == sizeof(piece));
  CHECK(f.mem.GetU32(r + 8) == 7 && f.mem.GetU32(r + 8 + 4) == 512 && f.mem.GetU32(r + 8 + 20) == 64);
  CHECK(f.mem.GetU32(r + 8 + 24) == 0x12345678u);
}

TEST(inbox_waits_for_the_module_and_splits_batches)
{
  Fixture f;
  InboxInMailbox(f, sizeof(GxcInboxHeader) + 48);  // room for one planet record (44 bytes)
  f.mem.PutU32(INBOX, 1);                           // the module has not emptied it yet
  SendPlanet(f, 1);
  SendPlanet(f, 2);
  f.Tick();
  CHECK(f.bridge.PendingInbox() == 2);
  f.mem.PutU32(INBOX, 0);
  f.Tick();
  CHECK(f.mem.GetU32(INBOX + 4) == 1 && f.mem.GetU32(INBOX + 16 + 8) == 1);
  CHECK(f.bridge.PendingInbox() == 1);
  f.mem.PutU32(INBOX, 0);
  f.Tick();
  CHECK(f.mem.GetU32(INBOX + 16 + 8) == 2 && f.bridge.PendingInbox() == 0);
}

TEST(inbox_dropped_on_scene_change)
{
  Fixture f;
  InboxInMailbox(f, 1024);
  f.mem.PutU32(INBOX, 1);
  f.Tick();
  SendPlanet(f, 1);
  f.Tick();
  CHECK(f.bridge.PendingInbox() == 1);
  f.mem.PutU32(MBX + offsetof(GxcMailbox, scene_id), 4);
  f.Tick();
  CHECK(f.bridge.PendingInbox() == 0);
}

// Minecraft's chunks wait in the ring while the module is behind: the mod sees its backlog and
// holds back the bulk of a planet, instead of it all queuing here in front of Mario's collision.
TEST(inbox_takes_from_the_ring_only_what_the_game_can_soon_use)
{
  Fixture f;
  InboxInMailbox(f, 1024);
  f.mem.PutU32(INBOX, 1);  // the module is busy
  Ring ring(*f.shm, GXC_OFF_RING_M2S);
  std::vector<u8> chunk(sizeof(GxcChunk) + 64 * 1024);
  GxcChunk c{5, 2, 64 * 1024, 0, {1.f, 2.f, 3.f, 4.f}};
  std::memcpy(chunk.data(), &c, sizeof(c));
  int pushed = 0;
  while (ring.Push(GXC_MSG_CHUNK, chunk.data(), static_cast<u32>(chunk.size())))
    pushed++;
  f.Tick();
  CHECK(f.bridge.PendingInboxBytes() >= HostBridge::INBOX_BACKLOG);
  CHECK(f.bridge.PendingInboxBytes() < HostBridge::INBOX_BACKLOG + chunk.size() + 16);
  CHECK(static_cast<int>(f.bridge.PendingInbox()) < pushed);
  while (ring.Push(GXC_MSG_CHUNK, chunk.data(), static_cast<u32>(chunk.size())))
    ;  // the mod fills the room again
  f.Tick();
  CHECK(f.bridge.PendingInboxBytes() < HostBridge::INBOX_BACKLOG + chunk.size() + 16);  // no more taken
  CHECK(!ring.Push(GXC_MSG_CHUNK, chunk.data(), static_cast<u32>(chunk.size())));  // the mod sees the game is behind
}

TEST(a_scene_change_drops_what_the_ring_held_for_the_old_one)
{
  Fixture f;
  InboxInMailbox(f, 1024);
  f.mem.PutU32(INBOX, 1);
  f.Tick();
  Ring ring(*f.shm, GXC_OFF_RING_M2S);
  std::vector<u8> chunk(sizeof(GxcChunk) + 64 * 1024);
  while (ring.Push(GXC_MSG_CHUNK, chunk.data(), static_cast<u32>(chunk.size())))
    ;
  f.Tick();
  CHECK(f.bridge.PendingInbox() > 0);
  f.mem.PutU32(MBX + offsetof(GxcMailbox, scene_id), 4);
  f.Tick();
  CHECK(f.bridge.PendingInbox() == 0 && f.bridge.PendingInboxBytes() == 0);
  CHECK(!ring.Pop());  // the ring is empty for the new scene's records
}

namespace
{
// In a level, Minecraft mode, the mod alive and following.
void Playing(Fixture& f)
{
  f.ModReports(1, {1, 2, 3});
  for (int i = 0; i < 2; i++)
    f.Tick();
  CHECK(f.bridge.InGame() && f.bridge.Following());
}
}  // namespace

TEST(the_game_waits_for_a_stalled_minecraft)
{
  Fixture f;
  Playing(f);
  f.now += 100;  // a tick late: nothing to wait for
  f.Tick();
  CHECK(f.slept == 0);
  f.now += 300;  // stalled: the game waits, up to the most a stall may cost it
  f.Tick();
  CHECK(f.slept == HostBridge::MOD_WAIT_MAX_MS);
  f.Tick();  // still stalled: it does not wait again for the same stall
  CHECK(f.slept == HostBridge::MOD_WAIT_MAX_MS);
  f.ModReports(2, {1, 2, 3});  // back
  f.Tick();
  f.now += 300;  // and stalled again: a new wait
  f.Tick();
  CHECK(f.slept == 2 * HostBridge::MOD_WAIT_MAX_MS);
  CHECK(f.bridge.ModWaitMs() == f.slept);
}

TEST(the_wait_ends_as_soon_as_minecraft_ticks)
{
  Fixture f;
  Playing(f);
  f.now += 300;
  const u64 back = f.now + 40;  // Minecraft's next tick, 40 ms into the wait
  f.on_sleep = [&f, back] {
    if (f.now >= back)
      f.shm->SetU64(offsetof(GxcHeader, mod_heartbeat_ms), f.now);
  };
  f.Tick();
  CHECK(f.slept == 40);
}

TEST(no_wait_for_a_minecraft_that_is_gone_or_in_menus)
{
  Fixture f;
  Playing(f);
  f.now += GXC_HEARTBEAT_TIMEOUT_MS;  // gone
  f.Tick();
  CHECK(f.slept == 0);
  Fixture g;
  g.ModReports(1, {1, 2, 3});
  g.bridge.SetMinecraftMode(false);  // the Wii Remote plays
  for (int i = 0; i < 2; i++)
    g.Tick();
  g.now += 300;
  g.Tick();
  CHECK(g.slept == 0);
}

// A savestate rolls the game's RAM back (planet, atlas and all) but not the mod: the game gets a
// scene id neither the mod nor the bridge has seen, so everything is sent again.
TEST(state_load_starts_a_new_scene)
{
  Fixture f;
  f.Tick();
  f.mem.PutU32(MBX + offsetof(GxcMailbox, scene_id), 5);
  f.Tick();
  f.Drain();
  f.mem.PutU32(MBX + offsetof(GxcMailbox, scene_id), 4);  // the state was saved in scene 4
  f.bridge.OnStateLoaded();
  f.Tick();
  CHECK(f.mem.GetU32(MBX + offsetof(GxcMailbox, scene_id)) == 6);
  const std::vector<Msg> msgs = f.Drain();
  CHECK(!msgs.empty() && msgs[0].type == GXC_MSG_SCENE_CHANGE && PayloadU32(msgs[0], 0) == 6);
  f.Tick();
  CHECK(f.mem.GetU32(MBX + offsetof(GxcMailbox, scene_id)) == 6);  // only once
}

TEST(state_load_waits_for_the_mailbox)
{
  Fixture f;
  f.mem.PutBytes(MBX, "xxxxxxxx", 8);  // saved before the module was loaded
  f.bridge.OnStateLoaded();
  f.Tick();
  WriteMailbox(f.mem, MBX, 3, {});
  for (int i = 0; i < 61; i++)  // the next scan
    f.Tick();
  CHECK(f.mem.GetU32(MBX + offsetof(GxcMailbox, scene_id)) == 4);
}

TEST(scene_change_carries_the_stage_name)
{
  Fixture f;
  f.mem.PutBytes(MBX + offsetof(GxcMailbox, stage_name), "SkyStationGalaxy", 17);
  f.Tick();
  const std::vector<Msg> msgs = f.Drain();
  CHECK(!msgs.empty() && msgs[0].type == GXC_MSG_SCENE_CHANGE && msgs[0].payload.size() == 36);
  CHECK(std::string(reinterpret_cast<const char*>(msgs[0].payload.data() + 4)) == "SkyStationGalaxy");
}

TEST(open_screen_and_item_flags_from_the_mod)
{
  Fixture f;
  f.Tick();
  f.shm->SetU64(offsetof(GxcHeader, mod_heartbeat_ms), f.now);
  WritePlayer(*f.shm, {GXC_PLAYER_SCREEN, 1, {10, 20, 30}, {0, 0, 1}, {0, 1, 0}, 70.f, 162.f, {}, GXC_VIEW_FIRST, 3});
  f.Tick();
  CHECK(f.bridge.ScreenOpen() && !f.bridge.ItemActive());
  WritePlayer(*f.shm, {GXC_PLAYER_ITEM_ACTIVE, 2, {10, 20, 30}, {0, 0, 1}, {0, 1, 0}, 70.f, 162.f, {}, GXC_VIEW_FIRST, 3});
  f.Tick();
  CHECK(!f.bridge.ScreenOpen() && f.bridge.ItemActive());
}

TEST(flying_draws_mario)
{
  Fixture f;
  f.Tick();
  f.shm->SetU64(offsetof(GxcHeader, mod_heartbeat_ms), f.now);
  WritePlayer(*f.shm, {GXC_PLAYER_FLYING, 1, {10, 20, 30}, {0, 0, 1}, {0, 1, 0}, 70.f, 162.f, {0, 900, 0}, GXC_VIEW_FIRST, 3});
  f.Tick();
  CHECK(f.bridge.Flying());
  CHECK((f.mem.GetU32(MBX + 52) & GXC_MBX_THIRD_PERSON) != 0);
  CHECK(f.mem.GetF32(MBX + offsetof(GxcMailbox, cam_offset) + 4) == 900.f);  // the camera, far off
}

TEST(f3_b_draws_marios_hitbox)
{
  Fixture f;
  f.Tick();
  f.shm->SetU64(offsetof(GxcHeader, mod_heartbeat_ms), f.now);
  WritePlayer(*f.shm, {0, 1, {10, 20, 30}, {0, 0, 1}, {0, 1, 0}, 70.f, 162.f, {}, GXC_VIEW_FIRST, 3});
  f.Tick();
  CHECK((f.mem.GetU32(MBX + 52) & GXC_MBX_HITBOXES) == 0);
  WritePlayer(*f.shm, {GXC_PLAYER_HITBOXES, 2, {10, 20, 30}, {0, 0, 1}, {0, 1, 0}, 70.f, 162.f, {}, GXC_VIEW_FIRST, 3});
  f.Tick();
  CHECK((f.mem.GetU32(MBX + 52) & GXC_MBX_HITBOXES) != 0);
}

TEST(mc_feel_reaches_the_game)
{
  Fixture f;
  f.Tick();
  f.shm->SetU64(offsetof(GxcHeader, mod_heartbeat_ms), f.now);
  WritePlayer(*f.shm, {0, 1, {10, 20, 30}, {0, 0, 1}, {0, 1, 0}, 70.f, 162.f, {}, GXC_VIEW_FIRST, 3});
  f.Tick();
  CHECK((f.mem.GetU32(MBX + 52) & GXC_MBX_MC_FEEL) == 0);
  CHECK(!f.bridge.McFeel());
  WritePlayer(*f.shm, {GXC_PLAYER_MC_FEEL, 2, {10, 20, 30}, {0, 0, 1}, {0, 1, 0}, 70.f, 162.f, {}, GXC_VIEW_FIRST, 3});
  f.Tick();
  CHECK((f.mem.GetU32(MBX + 52) & GXC_MBX_MC_FEEL) != 0);
  CHECK(f.bridge.McFeel() && !f.bridge.Walking());
  f.bridge.SetGait(true, false, true);
  f.Tick();
  CHECK((f.mem.GetU32(MBX + 52) & (GXC_MBX_MC_SPRINT | GXC_MBX_MC_SNEAK | GXC_MBX_MC_WALK)) ==
        (GXC_MBX_MC_SPRINT | GXC_MBX_MC_WALK));
  f.bridge.SetGait(false, false, false);  // keys let go: no walk bit, Mario stops
  f.Tick();
  CHECK((f.mem.GetU32(MBX + 52) & (GXC_MBX_MC_SPRINT | GXC_MBX_MC_WALK)) == 0);
  // Without Minecraft's feel the gait is not the game's business.
  WritePlayer(*f.shm, {0, 3, {10, 20, 30}, {0, 0, 1}, {0, 1, 0}, 70.f, 162.f, {}, GXC_VIEW_FIRST, 3});
  f.Tick();
  CHECK((f.mem.GetU32(MBX + 52) & (GXC_MBX_MC_FEEL | GXC_MBX_MC_SPRINT)) == 0);
}

TEST(walking_hides_mario_and_takes_his_keys)
{
  Fixture f;
  f.Tick();
  f.shm->SetU64(offsetof(GxcHeader, mod_heartbeat_ms), f.now);
  WritePlayer(*f.shm, {GXC_PLAYER_WALKING, 1, {10, 20, 30}, {0, 0, 1}, {0, 1, 0}, 70.f, 162.f, {}, GXC_VIEW_BACK, 3});
  f.Tick();
  CHECK(f.bridge.Walking() && !f.bridge.PlusHeld());
  // Steve is one of the planet's entities: Mario's own model stays hidden, even from behind.
  CHECK((f.mem.GetU32(MBX + 52) & GXC_MBX_THIRD_PERSON) == 0);
  WritePlayer(*f.shm, {GXC_PLAYER_PLUS, 2, {10, 20, 30}, {0, 0, 1}, {0, 1, 0}, 70.f, 162.f, {}, GXC_VIEW_BACK, 3});
  f.Tick();
  CHECK(!f.bridge.Walking() && f.bridge.PlusHeld());
  CHECK((f.mem.GetU32(MBX + 52) & GXC_MBX_THIRD_PERSON) != 0);
}

TEST(mario_skin_goes_onto_his_texture_and_again_in_a_new_scene)
{
  Fixture f;
  // Mario.bdl's TEX1 as tools/steve/build.py makes it: one 64x64 RGB5A3 texture, "steve".
  constexpr u32 TEX1 = 0x80600000u, DATA = TEX1 + 0x60;
  const u8 section[] = {'T', 'E', 'X', '1', 0, 0, 0x20, 0x60, 0, 1, 0xFF, 0xFF, 0, 0, 0, 0x20, 0, 0, 0, 0x40};
  f.mem.PutBytes(TEX1, section, sizeof(section));
  const u8 header[] = {5, 0, 0, 64, 0, 64};
  f.mem.PutBytes(TEX1 + 0x20, header, sizeof(header));
  f.mem.PutU32(TEX1 + 0x20 + 0x1C, 0x40);  // the image, from its header
  const u8 names[] = {0, 1, 0xFF, 0xFF, 0x12, 0x34, 0, 8, 's', 't', 'e', 'v', 'e', 0};
  f.mem.PutBytes(TEX1 + 0x40, names, sizeof(names));

  std::vector<u8> skin(12 + MarioSkin::BYTES, 0xAB);
  const u8 dims[] = {0, 0, 0, 0, 0, 0, 0, 64, 0, 0, 0, 64};
  std::memcpy(skin.data(), dims, sizeof(dims));
  Ring(*f.shm, GXC_OFF_RING_M2S).Push(GXC_MSG_MARIO_SKIN, skin.data(), static_cast<u32>(skin.size()));
  f.Tick();
  CHECK(f.bridge.MarioSkinWrites() == 1);
  CHECK(f.mem.GetU32(DATA) == 0xABABABABu && f.mem.GetU32(DATA + MarioSkin::BYTES - 4) == 0xABABABABu);
  CHECK(f.mem.GetU32(DATA + MarioSkin::BYTES) == 0);  // not a byte past it

  // A new scene loads Mario afresh: the skin is written once more.
  f.mem.PutU32(DATA, 0);
  f.mem.PutU32(MBX + offsetof(GxcMailbox, scene_id), 4);
  f.Tick();
  CHECK(f.mem.GetU32(DATA) == 0xABABABABu);
}
