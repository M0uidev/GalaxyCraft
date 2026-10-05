#pragma once
#include <array>
#include <deque>
#include <functional>
#include <map>
#include <optional>

#include "GuestMemory.h"
#include "MarioSkin.h"
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
  // sleep_ms: how the bridge waits for a stalled Minecraft (see WaitForMod); tests pass one that
  // moves their clock. GALAXYCRAFT_NO_WAIT=1 turns the wait off.
  HostBridge(Shm& shm, std::function<u64()> clock_ms, std::function<void(u32)> sleep_ms = {});

  // The mod's records taken from the ring and not yet in the module's inbox, at most this many
  // bytes: the rest stays in the ring, so the mod sees the game is behind and holds back the bulk
  // of a planet instead of queuing it here in front of Mario's collision.
  static constexpr size_t INBOX_BACKLOG = 512 * 1024;
  // Minecraft builds Mario's collision as he moves on a planet: a heartbeat older than this while
  // he plays means its tick stalled, and the game waits for it (at most MOD_WAIT_MAX_MS a stall).
  static constexpr u64 MOD_STALL_MS = 250;
  static constexpr u64 MOD_WAIT_MAX_MS = 1500;
  // Booting by ourselves, Minecraft is in its world while it last said so and its heartbeat is
  // younger than this: a stall (a planet being made) must not hand the screen to its menus, nor
  // stop the planet's collision under Mario.
  static constexpr u64 IN_WORLD_TIMEOUT_MS = 10000;

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
  // tools/gxplay.sh (GALAXYCRAFT_BOOT=space): the game boots by itself into GalaxyCraftSpace
  // (GXC_MBX_BOOT_SPACE), and Minecraft mode follows the mod: on while it is in a world
  // (GXC_MOD_IN_WORLD), else Minecraft's menus show and Mario waits (GXC_MBX_HOLD).
  void SetBootSpace(bool on) { m_boot_space = on; }
  bool BootSpace() const { return m_boot_space; }
  // Booting by ourselves and Minecraft is not in a world: its menus have the screen and the input.
  bool InMenu() const { return m_boot_space && !m_in_world; }
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
  // Minecraft movement: the player walks on its own and Mario goes with it, the keys are not his.
  bool Walking() const { return m_following && m_player && (m_player->flags & GXC_PLAYER_WALKING) != 0; }
  // Minecraft's feel on Mario: Minecraft's speeds and jump, none of Mario's moves.
  bool McFeel() const
  {
    const PlayerState* p = m_dev_follow ? &*m_dev_follow : m_player ? &*m_player : nullptr;
    return m_following && p && (p->flags & GXC_PLAYER_MC_FEEL) != 0;
  }
  // Minecraft's feel: sprinting (Ctrl) or sneaking (Shift), told to the game (GXC_MBX_MC_*).
  void SetGait(bool sprint, bool sneak, bool walking)
  {
    m_gait = (sprint ? GXC_MBX_MC_SPRINT : 0u) | (sneak ? GXC_MBX_MC_SNEAK : 0u) | (walking ? GXC_MBX_MC_WALK : 0u);
  }
  // The mod holds SMG2's + button (its pause menu), as Escape opens Minecraft's own instead.
  bool PlusHeld() const { return m_following && m_player && (m_player->flags & GXC_PLAYER_PLUS) != 0; }
  // Copies of Mario's skin texture written by the last GXC_MSG_MARIO_SKIN, for the dev harness.
  int MarioSkinWrites() const { return m_skin_writes; }
  // A savestate was loaded (CPU thread): the game's RAM went back in time, the mod did not. At the
  // next tick the game gets a scene id never seen before, so the mod sends everything again.
  void OnStateLoaded() { m_state_loaded = true; }
  // Voxel planet records waiting for the module's inbox.
  size_t PendingInbox() const { return m_inbox.size(); }
  size_t PendingInboxBytes() const { return m_inbox_bytes; }
  // Milliseconds the game has waited for a stalled Minecraft, all told.
  u64 ModWaitMs() const { return m_mod_wait_ms; }

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
  bool PopMessages(size_t limit);
  void WaitForMod();
  void QueueInbox(const Msg& msg);
  void FlushInbox(GuestMemory& mem, const Mailbox& mbx);

  Shm& m_shm;
  std::function<u64()> m_clock;
  std::function<void(u32)> m_sleep;  // empty: never wait for the mod
  bool m_waited_out = false;         // gave up on this stall: no more waiting until the mod is back
  u64 m_mod_wait_ms = 0;
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
  u32 m_gait = 0;  // GXC_MBX_MC_SPRINT / GXC_MBX_MC_SNEAK / GXC_MBX_MC_WALK
  bool m_minecraft_mode = true;
  bool m_boot_space = false;
  bool m_in_world = false;
  bool m_relink = false;
  bool m_link_on_save = false;
  bool m_on_title = true;
  u32 m_host_seq = 0;
  std::deque<std::vector<u8>> m_inbox;  // big-endian records, ready for the guest
  size_t m_inbox_bytes = 0;
  std::optional<u32> m_inbox_scene;
  u64 m_frame = 0;
  bool m_state_loaded = false;
  std::optional<u32> m_last_scene;  // the newest scene id seen, kept across mailbox losses
  MarioSkin m_skin;
  int m_skin_tries = 0;     // scans left to find Mario's texture in this scene
  int m_skin_cooldown = 0;  // ticks to the next scan
  int m_skin_writes = 0;
};
}  // namespace gxc
