#include "Shm.h"

#include <atomic>
#include <cstdlib>
#include <cstring>
#ifdef _WIN32
#ifndef NOMINMAX
#define NOMINMAX
#endif
#ifndef WIN32_LEAN_AND_MEAN
#define WIN32_LEAN_AND_MEAN
#endif
#include <windows.h>
#else
#include <fcntl.h>
#include <sys/mman.h>
#include <unistd.h>
#endif

#include "galaxycraft_protocol.h"

namespace gxc
{
namespace
{
#ifdef _WIN32
std::wstring Widen(const std::string& s)
{
  const int n = MultiByteToWideChar(CP_UTF8, 0, s.data(), static_cast<int>(s.size()), nullptr, 0);
  std::wstring w(n, L'\0');
  MultiByteToWideChar(CP_UTF8, 0, s.data(), static_cast<int>(s.size()), w.data(), n);
  return w;
}

std::string Narrow(const std::wstring& w)
{
  const int n = WideCharToMultiByte(CP_UTF8, 0, w.data(), static_cast<int>(w.size()), nullptr, 0,
                                    nullptr, nullptr);
  std::string s(n, '\0');
  WideCharToMultiByte(CP_UTF8, 0, w.data(), static_cast<int>(w.size()), s.data(), n, nullptr,
                      nullptr);
  return s;
}
#endif

// The platform's mapping of a file opened for read and write, sized to GXC_TOTAL_SIZE.
u8* MapFile(const std::string& path)
{
#ifdef _WIN32
  // Shared both ways with Minecraft (Java opens it for read and write too). TEMPORARY keeps it in
  // the cache instead of written to disk. The mapping grows the file to its size; a file mapped
  // by Minecraft cannot be resized (SetEndOfFile), so it is never truncated.
  const HANDLE file = CreateFileW(Widen(path).c_str(), GENERIC_READ | GENERIC_WRITE,
                                  FILE_SHARE_READ | FILE_SHARE_WRITE | FILE_SHARE_DELETE, nullptr,
                                  OPEN_ALWAYS, FILE_ATTRIBUTE_TEMPORARY, nullptr);
  if (file == INVALID_HANDLE_VALUE)
    return nullptr;
  const u64 size = GXC_TOTAL_SIZE;
  const HANDLE mapping = CreateFileMappingW(file, nullptr, PAGE_READWRITE, static_cast<DWORD>(size >> 32),
                                            static_cast<DWORD>(size), nullptr);
  CloseHandle(file);
  if (!mapping)
    return nullptr;
  void* p = MapViewOfFile(mapping, FILE_MAP_ALL_ACCESS, 0, 0, GXC_TOTAL_SIZE);
  CloseHandle(mapping);  // the view keeps the mapping alive
  return static_cast<u8*>(p);
#else
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
  return p == MAP_FAILED ? nullptr : static_cast<u8*>(p);
#endif
}
}  // namespace

std::string ShmDir()
{
  std::string dir;
#ifdef _WIN32
  if (const wchar_t* env = _wgetenv(L"GXC_SHM_DIR"); env && *env)
  {
    dir = Narrow(env);
  }
  else
  {
    wchar_t tmp[MAX_PATH + 1];
    const DWORD n = GetTempPathW(MAX_PATH + 1, tmp);
    dir = n ? Narrow(std::wstring(tmp, n)) : std::string(".");
  }
  while (dir.size() > 1 && (dir.back() == '\\' || dir.back() == '/'))
    dir.pop_back();
#else
  if (const char* env = std::getenv("GXC_SHM_DIR"); env && *env)
    dir = env;
  else
    dir = "/dev/shm";
  while (dir.size() > 1 && dir.back() == '/')
    dir.pop_back();
#endif
  return dir;
}

std::string ShmFile(const std::string& name)
{
#ifdef _WIN32
  return ShmDir() + "\\" + name;
#else
  return ShmDir() + "/" + name;
#endif
}

std::unique_ptr<Shm> Shm::Create(const std::string& path)
{
  u8* p = MapFile(path);
  if (!p)
    return nullptr;
  auto shm = std::unique_ptr<Shm>(new Shm(p, GXC_TOTAL_SIZE));
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
#ifdef _WIN32
  UnmapViewOfFile(m_data);
#else
  munmap(m_data, m_size);
#endif
}

// x86-64 and aarch64 (Linux and Windows) are little-endian, matching the protocol, so plain copies suffice.
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
  s.origin_epoch = w.origin_epoch;
  SeqWrite(shm, GXC_OFF_WORLD, s);
}

std::optional<WorldState> ReadWorld(const Shm& shm)
{
  auto s = SeqRead<GxcWorldState>(shm, GXC_OFF_WORLD);
  if (!s)
    return std::nullopt;
  WorldState w{s->scene_id, s->frame_id, {}, {}, s->flags, s->origin_epoch};
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
  s.origin_epoch = c.origin_epoch;
  SeqWrite(shm, GXC_OFF_GAMECAM, s);
}

std::optional<GameCamera> ReadGameCamera(const Shm& shm)
{
  auto s = SeqRead<GxcGameCamera>(shm, GXC_OFF_GAMECAM);
  if (!s)
    return std::nullopt;
  GameCamera c{s->flags, s->frame_id, {}, {}, {}, s->fov_y, {}, {}, s->origin_epoch};
  std::memcpy(&c.cam_pos, s->cam_pos, 12);
  std::memcpy(&c.cam_dir, s->cam_dir, 12);
  std::memcpy(&c.cam_up, s->cam_up, 12);
  std::memcpy(&c.mario_pos, s->mario_pos, 12);
  std::memcpy(&c.mario_front, s->mario_front, 12);
  return c;
}
}  // namespace gxc
