#include "HostBridge.h"

#include <algorithm>
#include <bit>
#include <cmath>
#include <cstddef>
#include <chrono>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <thread>
#include <unistd.h>

#include "galaxycraft_protocol.h"

namespace gxc
{
namespace
{
constexpr int SCAN_INTERVAL_TICKS = 60;
constexpr float MATRIX_EPSILON = 1e-4f;
constexpr size_t MBX_SIZE = sizeof(GxcMailbox);
constexpr int IN_GAME_TICKS = 30;
constexpr const char* TITLE_STAGE = "FileSelect";  // SMG2's title screen and file select
// Mario's skin: scans of guest RAM for his texture after a new skin or scene, a second apart (his
// model may load a little after the scene's first frames).
constexpr int SKIN_TRIES = 5;
constexpr int SKIN_RETRY_TICKS = 60;

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
  u32 game_seq;
  u32 scene_id;
  Vec3 gravity;
  Vec3 anchor;
  u32 game_flags;
  Vec3 cam_pos, cam_dir, cam_up;
  float cam_fov;
  Vec3 mario_front;
  std::vector<std::pair<u32, PartState>> parts;
  u32 inbox_addr = 0;
  u32 inbox_size = 0;
  std::array<char, 32> stage_name{};

  static Mailbox Parse(const u8* b)
  {
    Mailbox m{BE32(b + offsetof(GxcMailbox, game_seq)),
              BE32(b + offsetof(GxcMailbox, scene_id)),
              BEVec(b + offsetof(GxcMailbox, gravity)),
              BEVec(b + offsetof(GxcMailbox, anchor_pos)),
              BE32(b + offsetof(GxcMailbox, game_flags)),
              BEVec(b + offsetof(GxcMailbox, cam_pos)),
              BEVec(b + offsetof(GxcMailbox, cam_dir)),
              BEVec(b + offsetof(GxcMailbox, cam_up)),
              BEF(b + offsetof(GxcMailbox, cam_fov)),
              BEVec(b + offsetof(GxcMailbox, mario_front)),
              {}};
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
    m.inbox_addr = BE32(b + offsetof(GxcMailbox, inbox_addr));
    m.inbox_size = BE32(b + offsetof(GxcMailbox, inbox_size));
    std::memcpy(m.stage_name.data(), b + offsetof(GxcMailbox, stage_name), m.stage_name.size());
    m.stage_name.back() = 0;
    return m;
  }
};

HostBridge::HostBridge(Shm& shm, std::function<u64()> clock_ms, std::function<void(u32)> sleep_ms)
    : m_shm(shm), m_clock(std::move(clock_ms)), m_sleep(std::move(sleep_ms)),
      m_s2m(shm, GXC_OFF_RING_S2M), m_m2s(shm, GXC_OFF_RING_M2S)
{
  if (!m_sleep)
    m_sleep = [](u32 ms) { std::this_thread::sleep_for(std::chrono::milliseconds(ms)); };
  if (const char* no_wait = std::getenv("GALAXYCRAFT_NO_WAIT"); no_wait && *no_wait == '1')
    m_sleep = {};
  m_shm.SetU32(offsetof(GxcHeader, magic), GXC_MAGIC);
  m_shm.SetU32(offsetof(GxcHeader, version), GXC_VERSION);
  m_shm.SetU32(offsetof(GxcHeader, host_pid), static_cast<u32>(getpid()));
  if (auto p = ReadPlayer(m_shm))
    m_seen_player_frame = p->frame_id;  // stale pose from a previous session is not fresh
}

void HostBridge::SetLinkOnSave(bool on)
{
  m_link_on_save = on;
  m_on_title = true;
  if (on)
    m_minecraft_mode = false;
}

void HostBridge::Tick(GuestMemory& mem)
{
  const u64 now = m_clock();
  m_frame++;
  m_shm.SetU64(offsetof(GxcHeader, host_heartbeat_ms), now);
  WaitForMod();

  bool republish = PopMessages(INBOX_BACKLOG);

  std::array<u8, MBX_SIZE> raw;
  if (m_mailbox && (!mem.Read(*m_mailbox, raw.data(), MBX_SIZE) ||
                    std::memcmp(raw.data(), GXC_MBX_MAGIC, 8) != 0))
  {
    m_mailbox.reset();  // module unloaded or moved: never write to the old address again
    m_scan_cooldown = 0;
    m_scene.reset();
    m_parts.clear();
    m_game_seq.reset();
  }
  m_following = false;
  m_galaxy_view = false;
  if (!m_mailbox && (m_scan_cooldown-- > 0 || !FindMailbox(mem) ||
                     !mem.Read(*m_mailbox, raw.data(), MBX_SIZE)))
  {
    m_shm.SetU32(offsetof(GxcHeader, host_flags), 0);
    m_in_game = false;
    m_cutscene = false;
    return;
  }

  Mailbox mbx = Mailbox::Parse(raw.data());
  if (m_state_loaded)
  {
    m_state_loaded = false;
    mbx.scene_id = std::max(mbx.scene_id, m_last_scene.value_or(0)) + 1;
    std::array<u8, 4> be;
    PutBE32(be.data(), mbx.scene_id);
    mem.Write(*m_mailbox + offsetof(GxcMailbox, scene_id), be.data(), 4);
    m_game_seq.reset();
  }
  m_last_scene = m_last_scene ? std::max(*m_last_scene, mbx.scene_id) : mbx.scene_id;
  if (m_inbox_scene && *m_inbox_scene != mbx.scene_id)
  {
    // Meant for the old scene, and so is all the ring holds (the mod hears of the new one below):
    // it resends everything when it does.
    m_inbox.clear();
    m_inbox_bytes = 0;
    republish |= PopMessages(SIZE_MAX);
    m_inbox.clear();
    m_inbox_bytes = 0;
  }
  m_inbox_scene = mbx.scene_id;
  FlushInbox(mem, mbx);
  if (m_game_seq != mbx.game_seq)
    m_ticks_since_game_frame = 0;
  else
    m_ticks_since_game_frame++;
  m_game_seq = mbx.game_seq;
  const bool no_gravity = mbx.gravity.x == 0 && mbx.gravity.y == 0 && mbx.gravity.z == 0;
  const bool demo = (mbx.game_flags & GXC_MBX_GAME_DEMO) != 0;
  m_in_game = m_ticks_since_game_frame <= IN_GAME_TICKS && !no_gravity && !demo;
  m_cutscene = m_ticks_since_game_frame <= IN_GAME_TICKS && demo;

  {
    const u64 hb = m_shm.GetU64(offsetof(GxcHeader, mod_heartbeat_ms));
    const u32 flags = m_shm.GetU32(offsetof(GxcHeader, mod_flags));
    m_in_world = hb != 0 && now - hb < IN_WORLD_TIMEOUT_MS && (flags & GXC_MOD_IN_WORLD) != 0;
    m_entering = m_in_world && (flags & GXC_MOD_ENTERING) != 0;
  }
  if (m_boot_space)
    m_minecraft_mode = m_in_world;

  // The stage name is set when a Mario spawns: empty while booting, FileSelect on the title.
  if (m_link_on_save && mbx.stage_name[0])
  {
    const bool title = std::strcmp(mbx.stage_name.data(), TITLE_STAGE) == 0;
    if (title != m_on_title)
    {
      m_on_title = title;
      m_minecraft_mode = !title;
    }
  }

  // Booting by ourselves: Minecraft's title screen says when SMG2 is ready for a world.
  const u32 ready = m_boot_space && std::strcmp(mbx.stage_name.data(), GXC_SPACE_STAGE) == 0 ?
                        GXC_HOST_SPACE_READY :
                        0u;
  if (!m_minecraft_mode)
  {
    m_shm.SetU32(offsetof(GxcHeader, host_flags), ready);
    m_relink = true;
    WriteFollow(mem, nullptr);
    return;
  }
  m_shm.SetU32(offsetof(GxcHeader, host_flags), 1u | ready);

  // Why the scene goes to the mod again, counted for the dev harness (ctl status): HELLO, a new
  // scene, or Minecraft back in its world after its menus.
  if (republish)
    m_republished[0]++;
  else if (!m_scene || *m_scene != mbx.scene_id)
    m_republished[1]++;
  else if (m_relink)
    m_republished[2]++;
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
  // A new scene may load Mario's model afresh: the skin goes on it again.
  if (republish && m_skin.Has())
    m_skin_tries = SKIN_TRIES, m_skin_cooldown = 0;
  if (m_skin_tries > 0 && m_skin_cooldown-- <= 0)
  {
    m_skin_cooldown = SKIN_RETRY_TICKS;
    m_skin_writes = m_skin.Apply(mem);
    m_skin_tries = m_skin_writes > 0 ? 0 : m_skin_tries - 1;
  }

  // Only a pose anchored in this scene counts: right after a scene change (Mario died, a new
  // stage) the mod may still report one from the old scene before it hears of the new one.
  if (auto p = ReadPlayer(m_shm); p && p->frame_id != m_seen_player_frame && p->scene_id == mbx.scene_id)
  {
    m_seen_player_frame = p->frame_id;
    m_anchored = false;
    m_player = p;
  }
  const u64 mod_hb = m_shm.GetU64(offsetof(GxcHeader, mod_heartbeat_ms));
  const bool mod_alive = mod_hb != 0 && now - mod_hb < GXC_HEARTBEAT_TIMEOUT_MS;

  // SMG2 owns the movement: the player follows Mario, so the query is always where Mario is.
  WorldState w{mbx.scene_id, m_frame, mbx.gravity, mbx.anchor,
               GXC_WORLD_FOLLOW | (m_anchored ? GXC_WORLD_ANCHOR : 0u)};
  WriteWorld(m_shm, w);
  // The game's camera, with Mario from the same frame: the mod's Galaxy view follows it.
  const bool camera = m_ticks_since_game_frame <= IN_GAME_TICKS && mbx.cam_fov > 0;
  WriteGameCamera(m_shm, {(camera ? GXC_GAMECAM_VALID : 0u) |
                              ((mbx.game_flags & GXC_MBX_GAME_DEMO) ? GXC_GAMECAM_DEMO : 0u),
                          m_frame, mbx.cam_pos, mbx.cam_dir, mbx.cam_up, mbx.cam_fov, mbx.anchor,
                          mbx.mario_front});

  if (m_dev_follow)
  {
    PlayerState pose = *m_dev_follow;
    if (pose.up.x == 0 && pose.up.y == 0 && pose.up.z == 0)
      pose.up = no_gravity ? Vec3{0, 1, 0} : Vec3{-mbx.gravity.x, -mbx.gravity.y, -mbx.gravity.z};
    pose.pos = mbx.anchor;
    m_following = true;
    WriteFollow(mem, &pose);
    return;
  }
  m_following = mod_alive;
  m_galaxy_view = m_following && m_player && m_player->view == GXC_VIEW_GALAXY;
  WriteFollow(mem, m_following && m_player ? &*m_player : nullptr);
}

// Takes the mod's messages while fewer than limit bytes wait for the module's inbox; true if one
// was HELLO (the mod (re)started: republish the scene).
bool HostBridge::PopMessages(size_t limit)
{
  bool hello = false;
  while (m_inbox_bytes < limit)
  {
    auto msg = m_m2s.Pop();
    if (!msg)
      break;
    if (msg->type == GXC_MSG_HELLO)
      hello = true;
    else if (msg->type == GXC_MSG_MARIO_SKIN)
    {
      if (m_skin.Set(msg->payload))
        m_skin_tries = SKIN_TRIES, m_skin_cooldown = 0;
    }
    else
      QueueInbox(*msg);
  }
  return hello;
}

// The game and Minecraft run side by side: Minecraft decides, tick by tick, which of a planet's
// chunks around Mario collide. When its tick stalls (a big planet being made, a garbage
// collection) Mario would walk on past the ground it last gave him and fall through the planet.
// So while he plays, the game waits for a stalled Minecraft, as if the emulator lagged; never more
// than MOD_WAIT_MAX_MS a stall, and not at all for one that is gone (closed, or a heartbeat past
// the protocol's timeout).
void HostBridge::WaitForMod()
{
  if (!m_sleep || !m_minecraft_mode || !m_following || !m_in_game || m_dev_follow)
    return;
  u64 waited = 0;
  for (;;)
  {
    const u64 now = m_clock();
    const u64 hb = m_shm.GetU64(offsetof(GxcHeader, mod_heartbeat_ms));
    const u64 age = hb != 0 && now > hb ? now - hb : 0;
    if (age < MOD_STALL_MS)
    {
      m_waited_out = false;
      return;
    }
    if (m_waited_out || age >= GXC_HEARTBEAT_TIMEOUT_MS)
      return;
    if (waited >= MOD_WAIT_MAX_MS)
    {
      m_waited_out = true;
      return;
    }
    m_sleep(1);
    waited++;
    m_mod_wait_ms++;
  }
}

// Voxel planet messages from the mod become big-endian inbox records: the header and the fixed
// fields swapped, the display list and KCL copied as they are (built big-endian by the mod).
void HostBridge::QueueInbox(const Msg& msg)
{
  if (msg.type != GXC_MSG_PLANET && msg.type != GXC_MSG_CHUNK && msg.type != GXC_MSG_PLANET_TP &&
      msg.type != GXC_MSG_OUTLINE && msg.type != GXC_MSG_HELD && msg.type != GXC_MSG_ATLAS &&
      msg.type != GXC_MSG_SKIN && msg.type != GXC_MSG_MODEL && msg.type != GXC_MSG_ENTITIES &&
      msg.type != GXC_MSG_HURT && msg.type != GXC_MSG_SEAT && msg.type != GXC_MSG_SKY &&
      msg.type != GXC_MSG_CRACK)
    return;
  // Entity frames say where everything is now: an older one still waiting is stale.
  if (msg.type == GXC_MSG_ENTITIES)
    std::erase_if(m_inbox, [this](const std::vector<u8>& r) {
      const bool stale = (BE32(r.data()) >> 16) == GXC_MSG_ENTITIES;
      if (stale)
        m_inbox_bytes -= r.size();
      return stale;
    });
  // The held item's sprite and the atlas' texels are GX textures already: only the words before
  // them are swapped. Entity messages come big-endian whole (fixed 0). An outline is words only, as
  // many as its edges.
  const u32 outline = std::min<u32>(static_cast<u32>(msg.payload.size()), sizeof(GxcOutline)) & ~3u;
  const u32 fixed = msg.type == GXC_MSG_PLANET  ? sizeof(GxcPlanet) :
                    msg.type == GXC_MSG_CHUNK   ? sizeof(GxcChunk) :
                    msg.type == GXC_MSG_OUTLINE ? (outline < 8 ? 8 : outline) :
                    msg.type == GXC_MSG_CRACK   ? sizeof(GxcCrack) :
                    msg.type == GXC_MSG_HELD    ? offsetof(GxcHeld, sprite) :
                    msg.type == GXC_MSG_ATLAS   ? sizeof(GxcAtlas) :
                                                  0;
  if (msg.payload.size() < fixed)
    return;
  const u32 len = static_cast<u32>(msg.payload.size());
  std::vector<u8> rec(8 + ((len + 3) & ~3u), 0);
  PutBE32(rec.data(), static_cast<u32>(msg.type) << 16);
  PutBE32(rec.data() + 4, len);
  std::memcpy(rec.data() + 8, msg.payload.data(), len);
  for (u32 k = 0; k < fixed; k += 4)
  {
    u32 v;
    std::memcpy(&v, msg.payload.data() + k, 4);
    PutBE32(rec.data() + 8 + k, v);
  }
  m_inbox_bytes += rec.size();
  m_inbox.push_back(std::move(rec));
}

// The module empties its inbox every frame it runs: fill it again only once it is (state 0).
void HostBridge::FlushInbox(GuestMemory& mem, const Mailbox& mbx)
{
  constexpr u32 HEADER = sizeof(GxcInboxHeader);
  if (m_inbox.empty() || mbx.inbox_addr == 0 || mbx.inbox_size <= HEADER)
    return;
  u8 state[4];
  if (!mem.Read(mbx.inbox_addr, state, 4) || BE32(state) != 0)
    return;
  const u32 room = mbx.inbox_size - HEADER;
  std::vector<u8> out;
  u32 count = 0;
  while (!m_inbox.empty())
  {
    const std::vector<u8>& rec = m_inbox.front();
    if (rec.size() > room)
    {
      m_inbox_bytes -= rec.size();
      m_inbox.pop_front();  // can never fit
      continue;
    }
    if (out.size() + rec.size() > room)
      break;
    out.insert(out.end(), rec.begin(), rec.end());
    count++;
    m_inbox_bytes -= rec.size();
    m_inbox.pop_front();
  }
  if (count == 0)
    return;
  u8 header[HEADER];
  PutBE32(header, 1);
  PutBE32(header + 4, count);
  PutBE32(header + 8, static_cast<u32>(out.size()));
  PutBE32(header + 12, mbx.scene_id);
  mem.Write(mbx.inbox_addr + HEADER, out.data(), static_cast<u32>(out.size()));
  mem.Write(mbx.inbox_addr + 4, header + 4, HEADER - 4);
  mem.Write(mbx.inbox_addr, header, 4);  // last: the module only reads a full inbox
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
    // scene_id, then the stage's name (the mod keeps a planet per galaxy).
    std::array<u8, 4 + 32> scene{};
    std::memcpy(scene.data(), &mbx.scene_id, 4);
    std::memcpy(scene.data() + 4, mbx.stage_name.data(), 32);
    if (m_s2m.Free() < Ring::Cost(scene.size()))
      return;
    m_s2m.Push(GXC_MSG_SCENE_CHANGE, scene.data(), scene.size());
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

void HostBridge::WriteFollow(GuestMemory& mem, const PlayerState* player)
{
  // host_flags .. cam_offset is one contiguous block; host_seq is written last.
  constexpr size_t FIRST = offsetof(GxcMailbox, host_flags);
  constexpr size_t LAST = offsetof(GxcMailbox, cam_offset) + 12;
  std::array<u8, LAST - FIRST> b{};
  const bool galaxy = player && player->view == GXC_VIEW_GALAXY;
  // Mario is drawn outside first person, and while the player flies off on its own (/fly); never
  // with Minecraft movement, where Steve is drawn as one of the planet's entities instead.
  const bool third = player && !(player->flags & GXC_PLAYER_WALKING) &&
                     (player->view != GXC_VIEW_FIRST || (player->flags & GXC_PLAYER_FLYING));
  // Playing in Minecraft's view the IR sits under its crosshair, which stands in for the pointer.
  const bool hide_pointer = player && !galaxy && m_in_game;
  const bool hitboxes = player && (player->flags & GXC_PLAYER_HITBOXES);
  const bool mc_feel = player && (player->flags & GXC_PLAYER_MC_FEEL);
  const u32 boot = m_boot_space ? GXC_MBX_BOOT_SPACE | (InMenu() ? GXC_MBX_HOLD : 0u) : 0u;
  PutBE32(b.data(), boot | (player ? GXC_MBX_FOLLOW | (galaxy ? GXC_MBX_GALAXY_VIEW : 0u) |
                                 (third ? GXC_MBX_THIRD_PERSON : 0u) |
                                 (hide_pointer ? GXC_MBX_HIDE_POINTER : 0u) |
                                 (hitboxes ? GXC_MBX_HITBOXES : 0u) |
                                 (mc_feel ? GXC_MBX_MC_FEEL | m_gait : 0u) :
                             0u));
  if (player)
  {
    Vec3 offset = player->cam_offset;
    if (offset.x == 0 && offset.y == 0 && offset.z == 0)
      offset = {player->up.x * player->eye_height, player->up.y * player->eye_height,
                player->up.z * player->eye_height};
    const Vec3* vecs[] = {&player->pos, &player->look, &player->up, &offset};
    const size_t at_of[] = {offsetof(GxcMailbox, player_pos), offsetof(GxcMailbox, look),
                            offsetof(GxcMailbox, up), offsetof(GxcMailbox, cam_offset)};
    for (int v = 0; v < 4; v++)
    {
      u8* at = b.data() + at_of[v] - FIRST;
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
