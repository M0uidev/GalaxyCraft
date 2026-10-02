#pragma once
#include <array>
#include <functional>
#include <map>
#include <optional>

#include "GuestMemory.h"
#include "Shm.h"

namespace gxc
{
// Host side of GalaxyCraft, ticked once per video field on the emulator's CPU thread.
// Mirrors the guest mailbox (written by the Syati module inside SMG2) into the shared memory
// the Minecraft mod reads, and the mod's player pose back into the mailbox.
class HostBridge
{
public:
  HostBridge(Shm& shm, std::function<u64()> clock_ms);

  void Tick(GuestMemory& mem);

  bool HasMailbox() const { return m_mailbox.has_value(); }
  std::optional<u32> MailboxAddress() const { return m_mailbox; }

  // Development: follow with this look/up/FOV instead of the mod's, as if the mod were alive
  // (nullopt: back to the mod). A zero up vector means "opposite of the game's gravity".
  void SetDevFollow(std::optional<PlayerState> pose) { m_dev_follow = pose; }

  // Minecraft mode: keyboard and mouse play Mario, the camera sits in his eyes (FOLLOW) and the
  // player follows him. Wiimote mode: the game is left alone (no FOLLOW, no new parts,
  // host_flags 0). Back to Minecraft: the scene is republished and the mod re-anchored on Mario.
  void SetMinecraftMode(bool on) { m_minecraft_mode = on; }
  bool MinecraftMode() const { return m_minecraft_mode; }
  // Minecraft mode with a live mod (or a dev follow): the Wii Remote override belongs to us.
  bool Following() const { return m_following; }
  // Mario is playable: game frames within the last 30 ticks, gravity, no cutscene. Else a menu.
  bool InGame() const { return m_in_game; }

private:
  struct PartState
  {
    u32 kcl_addr;
    u32 kcl_size;
    std::array<float, 12> mtx;
  };

  struct Mailbox;

  bool FindMailbox(GuestMemory& mem);
  void PublishParts(GuestMemory& mem, const Mailbox& mbx, bool republish);
  bool SendPart(GuestMemory& mem, u32 id, const PartState& p, bool with_kcl);
  void WriteFollow(GuestMemory& mem, const PlayerState* player);

  Shm& m_shm;
  std::function<u64()> m_clock;
  Ring m_s2m;
  Ring m_m2s;
  std::optional<u32> m_mailbox;
  int m_scan_cooldown = 0;
  std::optional<u32> m_scene;
  std::map<u32, PartState> m_parts;
  bool m_anchored = true;
  u64 m_seen_player_frame = 0;
  std::optional<PlayerState> m_player;
  bool m_following = false;
  bool m_in_game = false;
  std::optional<u32> m_game_seq;
  int m_ticks_since_game_frame = 0;
  std::optional<PlayerState> m_dev_follow;
  bool m_minecraft_mode = true;
  bool m_relink = false;
  u32 m_host_seq = 0;
  u64 m_frame = 0;
};
}  // namespace gxc
