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
  mem.PutU32(at + 100, static_cast<u32>(parts.size()));
  for (size_t i = 0; i < parts.size(); i++)
  {
    const u32 p = at + 104 + static_cast<u32>(i) * 60;
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
    std::vector<u8> kcl(300);
    for (size_t i = 0; i < kcl.size(); i++)
      kcl[i] = static_cast<u8>(i % 251);
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

  void ModReports(u64 frame, Vec3 pos)
  {
    shm->SetU64(offsetof(GxcHeader, mod_heartbeat_ms), now);
    WritePlayer(*shm, {0, frame, pos, {0, 0, 1}, {0, 1, 0}, 70.f, 162.f});
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
  std::vector<u8> got;
  for (auto& m : msgs)
  {
    if (m.type == GXC_MSG_PART_UPSERT)
      CHECK(PayloadU32(m, 0) == 7 && PayloadU32(m, 4) == 300);
    if (m.type == GXC_MSG_KCL_CHUNK)
      got.insert(got.end(), m.payload.begin() + sizeof(GxcKclChunk), m.payload.end());
  }
  CHECK(got.size() == 300);
  for (size_t i = 0; i < got.size(); i++)
    CHECK(got[i] == static_cast<u8>(i % 251));
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
  CHECK(w && (w->flags & GXC_WORLD_ANCHOR) == 0 && w->query_pos.z == 30.f);
  CHECK(f.mem.GetF32(MBX + 56) == 10.f && f.mem.GetF32(MBX + 64) == 30.f);
  CHECK(f.mem.GetF32(MBX + 92) == 70.f);
  CHECK((f.mem.GetU32(MBX + 52) & GXC_MBX_DRIVE) != 0);
  CHECK(f.bridge.Driving());
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
  CHECK(w && (w->flags & GXC_WORLD_ANCHOR));
  CHECK(!f.bridge.Driving());
}

TEST(mod_heartbeat_stale_drops_drive)
{
  Fixture f;
  f.Tick();
  f.ModReports(1, {10, 20, 30});
  f.Tick();
  CHECK(f.bridge.Driving());
  f.now += GXC_HEARTBEAT_TIMEOUT_MS + 1;
  f.Tick();
  CHECK(!f.bridge.Driving());
  CHECK((f.mem.GetU32(MBX + 52) & GXC_MBX_DRIVE) == 0);
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

TEST(no_mailbox_reports_unlinked)
{
  Fixture f;
  f.mem.PutBytes(MBX, "XXXXXXXX", 8);
  f.Tick();
  CHECK(!f.bridge.HasMailbox());
  CHECK((f.shm->GetU32(offsetof(GxcHeader, host_flags)) & 1) == 0);
  CHECK(f.shm->GetU64(offsetof(GxcHeader, host_heartbeat_ms)) == f.now);
}
