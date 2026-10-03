#pragma once
#include <array>
#include <deque>
#include <functional>
#include <map>
#include <optional>

#include "GuestMemory.h"
#include "Shm.h"
#include "galaxycraft_protocol.h"

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
  // tools/gxplay.sh: Wiimote mode on SMG2's title and file select (stage FileSelect), Minecraft
  // mode once a save is picked and another stage loads. Each crossing sets the mode once; in
  // between, SetMinecraftMode (Ctrl+G) has the last word.
  void SetLinkOnSave(bool on);
  // Minecraft mode with a live mod (or a dev follow): the Wii Remote override belongs to us.
  bool Following() const { return m_following; }
  // Mario is playable: game frames within the last 30 ticks, gravity, no cutscene. Else a menu.
  bool InGame() const { return m_in_game; }
  // A cutscene owns Mario and the camera (game frames within the last 30 ticks, DEMO).
  bool Cutscene() const { return m_cutscene; }
  // Following with the mod's Galaxy view (F5): SMG2 keeps its camera, the mouse is the pointer.
  bool GalaxyView() const { return m_galaxy_view; }
  // Following with something in Minecraft's main hand: the clicks break and place blocks.
  bool ItemActive() const
  {
    return m_following && m_player && (m_player->flags & GXC_PLAYER_ITEM_ACTIVE) != 0;
  }
  // A Minecraft screen is open (chat...): the keyboard belongs to Minecraft, not to Mario.
  bool ScreenOpen() const
  {
    return m_following && m_player && (m_player->flags & GXC_PLAYER_SCREEN) != 0;
  }
  // /fly: the player flies away from Mario, who stays put.
  bool Flying() const { return m_following && m_player && (m_player->flags & GXC_PLAYER_FLYING) != 0; }
  // Voxel planet records waiting for the module's inbox.
  size_t PendingInbox() const { return m_inbox.size(); }

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
  void QueueInbox(const Msg& msg);
  void FlushInbox(GuestMemory& mem, const Mailbox& mbx);

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
  bool m_cutscene = false;
  bool m_galaxy_view = false;
  std::optional<u32> m_game_seq;
  int m_ticks_since_game_frame = 0;
  std::optional<PlayerState> m_dev_follow;
  bool m_minecraft_mode = true;
  bool m_relink = false;
  bool m_link_on_save = false;
  bool m_on_title = true;
  u32 m_host_seq = 0;
  std::deque<std::vector<u8>> m_inbox;  // big-endian records, ready for the guest
  std::optional<u32> m_inbox_scene;
  u64 m_frame = 0;
};
}  // namespace gxc
