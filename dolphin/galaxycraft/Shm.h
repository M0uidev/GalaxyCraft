#pragma once
#include <cstddef>
#include <memory>
#include <optional>
#include <string>
#include <vector>

#include "GuestMemory.h"

namespace gxc
{
// The shared memory file (/dev/shm/galaxycraft_v1), owned and initialized by the host.
// Field accessors are little-endian, as the protocol header specifies.
class Shm
{
public:
  // Creates or reuses the file, sizes it, and clears the header, slots and ring headers.
  static std::unique_ptr<Shm> Create(const std::string& path);
  ~Shm();
  Shm(const Shm&) = delete;
  Shm& operator=(const Shm&) = delete;

  u8* Data() const { return m_data; }
  u32 GetU32(size_t off) const;
  void SetU32(size_t off, u32 v);
  u64 GetU64(size_t off) const;
  void SetU64(size_t off, u64 v);
  float GetF32(size_t off) const;
  void SetF32(size_t off, float v);
  u32 LoadAcquire(size_t off) const;
  void StoreRelease(size_t off, u32 v);

private:
  Shm(u8* data, size_t size) : m_data(data), m_size(size) {}
  u8* m_data;
  size_t m_size;
};

struct Msg
{
  u16 type;
  std::vector<u8> payload;
};

// Single-producer single-consumer ring (GxcRingHeader).
class Ring
{
public:
  Ring(Shm& shm, size_t offset);
  bool Push(u16 type, const void* data, u32 n);
  std::optional<Msg> Pop();
  // Bytes free, and the worst-case bytes a message of n payload bytes can consume (incl. padding).
  u32 Free() const;
  static u32 Cost(u32 n) { return 8 + ((n + 7) & ~7u); }

private:
  Shm& m_shm;
  size_t m_off;
  size_t m_data;
  u32 m_cap;
};

struct Vec3
{
  float x, y, z;
};

struct WorldState
{
  u32 scene_id;
  u64 frame_id;
  Vec3 gravity;
  Vec3 query_pos;
  u32 flags;
};

struct PlayerState
{
  u32 flags;
  u64 frame_id;
  Vec3 pos, look, up;
  float fov_y;
  float eye_height;
};

void WriteWorld(Shm& shm, const WorldState& w);
std::optional<WorldState> ReadWorld(const Shm& shm);
void WritePlayer(Shm& shm, const PlayerState& p);
std::optional<PlayerState> ReadPlayer(const Shm& shm);
}  // namespace gxc
