#include "Shm.h"

#include <atomic>
#include <cstring>
#include <fcntl.h>
#include <sys/mman.h>
#include <unistd.h>

#include "galaxycraft_protocol.h"

namespace gxc
{
std::unique_ptr<Shm> Shm::Create(const std::string& path)
{
  const int fd = open(path.c_str(), O_RDWR | O_CREAT, 0600);
  if (fd < 0)
    return nullptr;
  if (ftruncate(fd, GXC_TOTAL_SIZE) != 0)
  {
    close(fd);
    return nullptr;
  }
  void* p = mmap(nullptr, GXC_TOTAL_SIZE, PROT_READ | PROT_WRITE, MAP_SHARED, fd, 0);
  close(fd);
  if (p == MAP_FAILED)
    return nullptr;
  auto shm = std::unique_ptr<Shm>(new Shm(static_cast<u8*>(p), GXC_TOTAL_SIZE));
  std::memset(shm->m_data, 0, GXC_OFF_RING_S2M);  // header and slots: drop stale state
  std::memset(shm->m_data + GXC_OFF_RING_S2M, 0, sizeof(GxcRingHeader));
  std::memset(shm->m_data + GXC_OFF_RING_M2S, 0, sizeof(GxcRingHeader));
  shm->SetU32(GXC_OFF_RING_S2M + offsetof(GxcRingHeader, capacity), GXC_RING_S2M_CAP);
  shm->SetU32(GXC_OFF_RING_M2S + offsetof(GxcRingHeader, capacity), GXC_RING_M2S_CAP);
  shm->SetU32(GXC_OFF_OVERLAY + offsetof(GxcOverlayHeader, latest), 0xFFFFFFFFu);
  return shm;
}

Shm::~Shm()
{
  munmap(m_data, m_size);
}

// x86-64 and aarch64 Linux are little-endian, matching the protocol, so plain copies suffice.
u32 Shm::GetU32(size_t off) const
{
  u32 v;
  std::memcpy(&v, m_data + off, 4);
  return v;
}
void Shm::SetU32(size_t off, u32 v)
{
  std::memcpy(m_data + off, &v, 4);
}
u64 Shm::GetU64(size_t off) const
{
  u64 v;
  std::memcpy(&v, m_data + off, 8);
  return v;
}
void Shm::SetU64(size_t off, u64 v)
{
  std::memcpy(m_data + off, &v, 8);
}
float Shm::GetF32(size_t off) const
{
  float v;
  std::memcpy(&v, m_data + off, 4);
  return v;
}
void Shm::SetF32(size_t off, float v)
{
  std::memcpy(m_data + off, &v, 4);
}
u32 Shm::LoadAcquire(size_t off) const
{
  return std::atomic_ref<u32>(*reinterpret_cast<u32*>(m_data + off)).load(std::memory_order_acquire);
}
void Shm::StoreRelease(size_t off, u32 v)
{
  std::atomic_ref<u32>(*reinterpret_cast<u32*>(m_data + off)).store(v, std::memory_order_release);
}

Ring::Ring(Shm& shm, size_t offset)
    : m_shm(shm), m_off(offset), m_data(offset + sizeof(GxcRingHeader)),
      m_cap(shm.GetU32(offset + offsetof(GxcRingHeader, capacity)))
{
}

u32 Ring::Free() const
{
  const u32 head = m_shm.LoadAcquire(m_off);
  const u32 tail = m_shm.LoadAcquire(m_off + 4);
  return m_cap - (head - tail);
}

bool Ring::Push(u16 type, const void* data, u32 n)
{
  const u32 need = Cost(n);
  const u32 head = m_shm.LoadAcquire(m_off);
  const u32 tail = m_shm.LoadAcquire(m_off + 4);
  const u32 free = m_cap - (head - tail);
  u32 pos = head % m_cap;
  const u32 rem = m_cap - pos;
  const u32 skip = need > rem ? rem : 0;
  if (static_cast<u64>(skip) + need > free)
    return false;
  if (skip)
  {
    if (rem >= 8)
    {
      const GxcMsgHeader pad{GXC_MSG_PAD, 0, rem - 8};
      std::memcpy(m_shm.Data() + m_data + pos, &pad, sizeof(pad));
    }
    pos = 0;
  }
  const GxcMsgHeader h{type, 0, n};
  std::memcpy(m_shm.Data() + m_data + pos, &h, sizeof(h));
  if (n)
    std::memcpy(m_shm.Data() + m_data + pos + 8, data, n);
  m_shm.StoreRelease(m_off, head + skip + need);
  return true;
}

std::optional<Msg> Ring::Pop()
{
  while (true)
  {
    const u32 head = m_shm.LoadAcquire(m_off);
    const u32 tail = m_shm.LoadAcquire(m_off + 4);
    if (head == tail)
      return std::nullopt;
    const u32 pos = tail % m_cap;
    const u32 rem = m_cap - pos;
    if (rem < 8)
    {
      m_shm.StoreRelease(m_off + 4, tail + rem);
      continue;
    }
    GxcMsgHeader h;
    std::memcpy(&h, m_shm.Data() + m_data + pos, sizeof(h));
    if (h.type == GXC_MSG_PAD)
    {
      m_shm.StoreRelease(m_off + 4, tail + rem);
      continue;
    }
    Msg m{h.type, std::vector<u8>(m_shm.Data() + m_data + pos + 8,
                                  m_shm.Data() + m_data + pos + 8 + h.length)};
    m_shm.StoreRelease(m_off + 4, tail + Cost(h.length));
    return m;
  }
}

namespace
{
template <typename T>
void SeqWrite(Shm& shm, size_t off, const T& slot)
{
  const u32 seq = shm.GetU32(off);
  shm.StoreRelease(off, seq + 1);
  std::atomic_thread_fence(std::memory_order_release);
  std::memcpy(shm.Data() + off + 4, reinterpret_cast<const u8*>(&slot) + 4, sizeof(T) - 4);
  shm.StoreRelease(off, seq + 2);
}

template <typename T>
std::optional<T> SeqRead(const Shm& shm, size_t off)
{
  for (int attempt = 0; attempt < 100; attempt++)
  {
    const u32 s1 = shm.LoadAcquire(off);
    if (s1 == 0)
      return std::nullopt;
    if (s1 & 1)
      continue;
    T slot;
    std::memcpy(&slot, shm.Data() + off, sizeof(T));
    std::atomic_thread_fence(std::memory_order_acquire);
    if (shm.LoadAcquire(off) == s1)
      return slot;
  }
  return std::nullopt;
}
}  // namespace

void WriteWorld(Shm& shm, const WorldState& w)
{
  GxcWorldState s{};
  s.scene_id = w.scene_id;
  s.frame_id = w.frame_id;
  std::memcpy(s.gravity, &w.gravity, 12);
  std::memcpy(s.query_pos, &w.query_pos, 12);
  s.flags = w.flags;
  SeqWrite(shm, GXC_OFF_WORLD, s);
}

std::optional<WorldState> ReadWorld(const Shm& shm)
{
  auto s = SeqRead<GxcWorldState>(shm, GXC_OFF_WORLD);
  if (!s)
    return std::nullopt;
  WorldState w{s->scene_id, s->frame_id, {}, {}, s->flags};
  std::memcpy(&w.gravity, s->gravity, 12);
  std::memcpy(&w.query_pos, s->query_pos, 12);
  return w;
}

void WritePlayer(Shm& shm, const PlayerState& p)
{
  GxcPlayerState s{};
  s.flags = p.flags;
  s.frame_id = p.frame_id;
  std::memcpy(s.pos, &p.pos, 12);
  std::memcpy(s.look, &p.look, 12);
  std::memcpy(s.up, &p.up, 12);
  s.fov_y = p.fov_y;
  s.eye_height = p.eye_height;
  std::memcpy(s.cam_offset, &p.cam_offset, 12);
  s.view = p.view;
  s.scene_id = p.scene_id;
  SeqWrite(shm, GXC_OFF_PLAYER, s);
}

std::optional<PlayerState> ReadPlayer(const Shm& shm)
{
  auto s = SeqRead<GxcPlayerState>(shm, GXC_OFF_PLAYER);
  if (!s)
    return std::nullopt;
  PlayerState p{s->flags, s->frame_id, {}, {}, {}, s->fov_y, s->eye_height};
  std::memcpy(&p.pos, s->pos, 12);
  std::memcpy(&p.look, s->look, 12);
  std::memcpy(&p.up, s->up, 12);
  std::memcpy(&p.cam_offset, s->cam_offset, 12);
  p.view = s->view;
  p.scene_id = s->scene_id;
  return p;
}

void WriteGameCamera(Shm& shm, const GameCamera& c)
{
  GxcGameCamera s{};
  s.flags = c.flags;
  s.frame_id = c.frame_id;
  std::memcpy(s.cam_pos, &c.cam_pos, 12);
  std::memcpy(s.cam_dir, &c.cam_dir, 12);
  std::memcpy(s.cam_up, &c.cam_up, 12);
  s.fov_y = c.fov_y;
  std::memcpy(s.mario_pos, &c.mario_pos, 12);
  std::memcpy(s.mario_front, &c.mario_front, 12);
  SeqWrite(shm, GXC_OFF_GAMECAM, s);
}

std::optional<GameCamera> ReadGameCamera(const Shm& shm)
{
  auto s = SeqRead<GxcGameCamera>(shm, GXC_OFF_GAMECAM);
  if (!s)
    return std::nullopt;
  GameCamera c{s->flags, s->frame_id, {}, {}, {}, s->fov_y, {}, {}};
  std::memcpy(&c.cam_pos, s->cam_pos, 12);
  std::memcpy(&c.cam_dir, s->cam_dir, 12);
  std::memcpy(&c.cam_up, s->cam_up, 12);
  std::memcpy(&c.mario_pos, s->mario_pos, 12);
  std::memcpy(&c.mario_front, s->mario_front, 12);
  return c;
}
}  // namespace gxc
