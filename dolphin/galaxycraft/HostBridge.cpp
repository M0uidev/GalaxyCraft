#include "HostBridge.h"

#include <algorithm>
#include <bit>
#include <cmath>
#include <cstring>
#include <unistd.h>

#include "galaxycraft_protocol.h"

namespace gxc
{
namespace
{
constexpr int SCAN_INTERVAL_TICKS = 60;
constexpr float MATRIX_EPSILON = 1e-4f;
constexpr size_t MBX_SIZE = sizeof(GxcMailbox);

u32 BE32(const u8* p)
{
  u32 v;
  std::memcpy(&v, p, 4);
  return __builtin_bswap32(v);
}
float BEF(const u8* p)
{
  return std::bit_cast<float>(BE32(p));
}
void PutBE32(u8* p, u32 v)
{
  v = __builtin_bswap32(v);
  std::memcpy(p, &v, 4);
}
void PutBEF(u8* p, float f)
{
  PutBE32(p, std::bit_cast<u32>(f));
}
Vec3 BEVec(const u8* p)
{
  return {BEF(p), BEF(p + 4), BEF(p + 8)};
}
}  // namespace

struct HostBridge::Mailbox
{
  u32 scene_id;
  Vec3 gravity;
  Vec3 anchor;
  std::vector<std::pair<u32, PartState>> parts;

  static Mailbox Parse(const u8* b)
  {
    Mailbox m{BE32(b + offsetof(GxcMailbox, scene_id)), BEVec(b + offsetof(GxcMailbox, gravity)),
              BEVec(b + offsetof(GxcMailbox, anchor_pos)), {}};
    const u32 count =
        std::min<u32>(BE32(b + offsetof(GxcMailbox, part_count)), GXC_MBX_MAX_PARTS);
    for (u32 i = 0; i < count; i++)
    {
      const u8* p = b + offsetof(GxcMailbox, parts) + i * sizeof(GxcMbxPart);
      PartState s{BE32(p + 4), BE32(p + 8), {}};
      for (int k = 0; k < 12; k++)
        s.mtx[k] = BEF(p + offsetof(GxcMbxPart, mtx) + 4 * k);
      m.parts.emplace_back(BE32(p), s);
    }
    return m;
  }
};

HostBridge::HostBridge(Shm& shm, std::function<u64()> clock_ms)
    : m_shm(shm), m_clock(std::move(clock_ms)), m_s2m(shm, GXC_OFF_RING_S2M),
      m_m2s(shm, GXC_OFF_RING_M2S)
{
  m_shm.SetU32(offsetof(GxcHeader, magic), GXC_MAGIC);
  m_shm.SetU32(offsetof(GxcHeader, version), GXC_VERSION);
  m_shm.SetU32(offsetof(GxcHeader, host_pid), static_cast<u32>(getpid()));
  if (auto p = ReadPlayer(m_shm))
    m_seen_player_frame = p->frame_id;  // stale pose from a previous session is not fresh
}

void HostBridge::Tick(GuestMemory& mem)
{
  const u64 now = m_clock();
  m_frame++;
  m_shm.SetU64(offsetof(GxcHeader, host_heartbeat_ms), now);

  bool republish = false;
  while (auto msg = m_m2s.Pop())
  {
    if (msg->type == GXC_MSG_HELLO)
      republish = true;
  }

  std::array<u8, MBX_SIZE> raw;
  if (m_mailbox && (!mem.Read(*m_mailbox, raw.data(), MBX_SIZE) ||
                    std::memcmp(raw.data(), GXC_MBX_MAGIC, 8) != 0))
  {
    m_mailbox.reset();  // module unloaded or moved: never write to the old address again
    m_scan_cooldown = 0;
    m_scene.reset();
    m_parts.clear();
    m_driving = false;
  }
  if (!m_mailbox && (m_scan_cooldown-- > 0 || !FindMailbox(mem) ||
                     !mem.Read(*m_mailbox, raw.data(), MBX_SIZE)))
  {
    m_shm.SetU32(offsetof(GxcHeader, host_flags), 0);
    return;
  }
  if (!m_link_enabled)
  {
    m_shm.SetU32(offsetof(GxcHeader, host_flags), 0);
    m_driving = false;
    m_relink = true;
    WriteDrive(mem, nullptr);
    return;
  }
  m_shm.SetU32(offsetof(GxcHeader, host_flags), 1);

  const Mailbox mbx = Mailbox::Parse(raw.data());
  if (!m_scene || *m_scene != mbx.scene_id || m_relink)
    republish = true;
  m_relink = false;
  if (republish)
  {
    m_anchored = true;
    if (auto p = ReadPlayer(m_shm))
      m_seen_player_frame = p->frame_id;
  }
  PublishParts(mem, mbx, republish);

  if (auto p = ReadPlayer(m_shm); p && p->frame_id != m_seen_player_frame)
  {
    m_seen_player_frame = p->frame_id;
    m_anchored = false;
    m_player = p;
  }
  const u64 mod_hb = m_shm.GetU64(offsetof(GxcHeader, mod_heartbeat_ms));
  const bool mod_alive = mod_hb != 0 && now - mod_hb < GXC_HEARTBEAT_TIMEOUT_MS;

  WorldState w{mbx.scene_id, m_frame, mbx.gravity,
               m_anchored || !m_player ? mbx.anchor : m_player->pos,
               m_anchored ? GXC_WORLD_ANCHOR : 0u};
  WriteWorld(m_shm, w);

  if (m_dev_drive)
  {
    PlayerState pose = *m_dev_drive;
    if (pose.up.x == 0 && pose.up.y == 0 && pose.up.z == 0)
    {
      const bool no_gravity = mbx.gravity.x == 0 && mbx.gravity.y == 0 && mbx.gravity.z == 0;
      pose.up = no_gravity ? Vec3{0, 1, 0} : Vec3{-mbx.gravity.x, -mbx.gravity.y, -mbx.gravity.z};
    }
    m_driving = true;
    WriteDrive(mem, &pose);
    return;
  }
  m_driving = mod_alive && !m_anchored && m_player.has_value();
  WriteDrive(mem, m_driving ? &*m_player : nullptr);
}

bool HostBridge::FindMailbox(GuestMemory& mem)
{
  m_scan_cooldown = SCAN_INTERVAL_TICKS;
  constexpr u32 CHUNK = 1u << 20;
  std::vector<u8> buf(CHUNK + 8);
  for (auto [base, size] : mem.Regions())
  {
    for (u32 off = 0; off < size; off += CHUNK)
    {
      const u32 n = std::min<u32>(CHUNK + 8, size - off);
      if (!mem.Read(base + off, buf.data(), n))
        continue;
      for (u32 i = 0; i + 8 <= n; i += 4)
      {
        if (std::memcmp(buf.data() + i, GXC_MBX_MAGIC, 8) == 0)
        {
          m_mailbox = base + off + i;
          return true;
        }
      }
    }
  }
  return false;
}

void HostBridge::PublishParts(GuestMemory& mem, const Mailbox& mbx, bool republish)
{
  if (republish)
  {
    if (m_s2m.Free() < Ring::Cost(4))
      return;
    m_s2m.Push(GXC_MSG_SCENE_CHANGE, &mbx.scene_id, 4);
    m_scene = mbx.scene_id;
    m_parts.clear();
  }

  for (const auto& [id, part] : mbx.parts)
  {
    auto known = m_parts.find(id);
    const bool new_kcl = known == m_parts.end() || known->second.kcl_addr != part.kcl_addr ||
                         known->second.kcl_size != part.kcl_size;
    bool moved = false;
    if (!new_kcl)
    {
      for (int k = 0; k < 12; k++)
        moved |= std::fabs(known->second.mtx[k] - part.mtx[k]) > MATRIX_EPSILON;
    }
    if ((new_kcl || moved) && SendPart(mem, id, part, new_kcl))
      m_parts[id] = part;
  }

  for (auto it = m_parts.begin(); it != m_parts.end();)
  {
    const bool present = std::any_of(mbx.parts.begin(), mbx.parts.end(),
                                     [&](const auto& p) { return p.first == it->first; });
    if (!present && m_s2m.Free() >= Ring::Cost(4))
    {
      m_s2m.Push(GXC_MSG_PART_REMOVE, &it->first, 4);
      it = m_parts.erase(it);
    }
    else
    {
      ++it;
    }
  }
}

bool HostBridge::SendPart(GuestMemory& mem, u32 id, const PartState& p, bool with_kcl)
{
  std::vector<u8> kcl;
  if (with_kcl)
  {
    kcl.resize(p.kcl_size);
    if (p.kcl_size == 0 || !mem.Read(p.kcl_addr, kcl.data(), p.kcl_size))
      return false;  // bad address: ignore the part until the game reports something readable
    // KCollisionServer::setData turns the header's four offsets into absolute pointers; the mod
    // wants offsets. Anything that does not land inside the KCL means we misread the part.
    if (kcl.size() < 16)
      return false;
    for (u32 k = 0; k < 4; k++)
    {
      u32 v = BE32(kcl.data() + 4 * k);
      if (v >= p.kcl_addr)
        v -= p.kcl_addr;
      if (v > p.kcl_size)
        return false;
      PutBE32(kcl.data() + 4 * k, v);
    }
  }
  // Only send if everything fits, so the mod never sees half a part.
  u64 need = Ring::Cost(sizeof(GxcPartUpsert));
  for (u32 off = 0; off < kcl.size(); off += GXC_KCL_CHUNK_MAX)
    need += Ring::Cost(sizeof(GxcKclChunk) + std::min<u32>(GXC_KCL_CHUNK_MAX, kcl.size() - off)) + 8;
  if (need > m_s2m.Free())
    return false;

  GxcPartUpsert up{id, p.kcl_size, {}};
  std::memcpy(up.mtx, p.mtx.data(), sizeof(up.mtx));
  m_s2m.Push(GXC_MSG_PART_UPSERT, &up, sizeof(up));
  std::vector<u8> chunk;
  for (u32 off = 0; off < kcl.size(); off += GXC_KCL_CHUNK_MAX)
  {
    const u32 n = std::min<u32>(GXC_KCL_CHUNK_MAX, kcl.size() - off);
    const GxcKclChunk h{id, off, p.kcl_size};
    chunk.resize(sizeof(h) + n);
    std::memcpy(chunk.data(), &h, sizeof(h));
    std::memcpy(chunk.data() + sizeof(h), kcl.data() + off, n);
    m_s2m.Push(GXC_MSG_KCL_CHUNK, chunk.data(), static_cast<u32>(chunk.size()));
  }
  return true;
}

void HostBridge::WriteDrive(GuestMemory& mem, const PlayerState* player)
{
  // host_flags .. eye_height is one contiguous block; host_seq is written last.
  constexpr size_t FIRST = offsetof(GxcMailbox, host_flags);
  constexpr size_t LAST = offsetof(GxcMailbox, eye_height) + 4;
  std::array<u8, LAST - FIRST> b{};
  PutBE32(b.data(), player ? GXC_MBX_DRIVE : 0u);
  if (player)
  {
    const Vec3* vecs[] = {&player->pos, &player->look, &player->up};
    for (int v = 0; v < 3; v++)
    {
      u8* at = b.data() + offsetof(GxcMailbox, player_pos) - FIRST + 12 * v;
      PutBEF(at, vecs[v]->x), PutBEF(at + 4, vecs[v]->y), PutBEF(at + 8, vecs[v]->z);
    }
    PutBEF(b.data() + offsetof(GxcMailbox, fov_y) - FIRST, player->fov_y);
    PutBEF(b.data() + offsetof(GxcMailbox, eye_height) - FIRST, player->eye_height);
  }
  mem.Write(*m_mailbox + FIRST, b.data(), static_cast<u32>(b.size()));
  u8 seq[4];
  PutBE32(seq, ++m_host_seq);
  mem.Write(*m_mailbox + offsetof(GxcMailbox, host_seq), seq, 4);
}
}  // namespace gxc
