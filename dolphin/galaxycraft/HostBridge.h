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

  // Development: drive the game from this pose instead of the mod's (nullopt: back to the mod).
  // A zero up vector means "opposite of the game's gravity".
  void SetDevDrive(std::optional<PlayerState> pose) { m_dev_drive = pose; }

  // Off: the game is left alone (no DRIVE, no new parts, host_flags 0) so its menus can be used
  // with the Wii Remote. Back on: the scene is republished and the mod re-anchored on Mario.
  void SetLinkEnabled(bool enabled) { m_link_enabled = enabled; }
  bool LinkEnabled() const { return m_link_enabled; }
  bool Driving() const { return m_driving; }

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
  void WriteDrive(GuestMemory& mem, const PlayerState* player);

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
  bool m_driving = false;
  std::optional<PlayerState> m_dev_drive;
  bool m_link_enabled = true;
  bool m_relink = false;
  u32 m_host_seq = 0;
  u64 m_frame = 0;
};
}  // namespace gxc
