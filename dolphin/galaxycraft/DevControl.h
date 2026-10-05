#pragma once
#include <array>
#include <optional>
#include <string>
#include <string_view>
#include <vector>

#include "GuestMemory.h"

namespace gxc
{
// Development commands written by tools/gxdev.py to /dev/shm/galaxycraft_ctl, one per line:
//   peek ADDR LEN   hex dump of guest memory (ADDR in hex, LEN 1..4096)
//   poke ADDR HEX   write these bytes (HEX: even number of hex digits, up to 64 bytes)
//   mbx             one-line summary of the guest mailbox
//   shot NAME | save PATH | load PATH   run by the emulator on its host thread
//   lean W S N                   Minecraft's feel: the stick's lean walking, sprinting, sneaking
//                                (MarioInput's defaults until Dolphin restarts; for measuring)
//   follow LX LY LZ [UX UY UZ] [back] [feel]   follow Mario looking this way, as if the mod sent
//                                it; back: third person, 4 blocks behind (addr bit 0); feel:
//                                Minecraft's feel (GXC_PLAYER_MC_FEEL, addr bit 1)
//                                (no up: opposite of the game's gravity)
//   unfollow                     back to whatever the mod says
//   text STRING                  type these characters (ASCII) into Minecraft, as the keyboard would
//   status                       focus/input gates, mode and mod state (Dolphin side); fps, vps,
//                                speed and max_speed (unthrottled, percent: 100 = full speed),
//                                mario_skin_writes (copies of Mario's skin the last /skin wrote),
//                                plus_held (the mod holds SMG2's + button: the pause menu's SMG2 Menu)
//   link on|off                  same as the Ctrl+G hotkey: Minecraft mode or Wiimote mode
//   keys [w a s d space shift ctrl esc tab lmb rmb]...   hold these until the next keys, as if
//                                typed in Minecraft mode (no list: release all)
struct DevCommand
{
  enum Kind
  {
    Peek,
    Poke,
    Mbx,
    Shot,
    Save,
    Load,
    Follow,
    Unfollow,
    Link,
    Status,
    Keys,
    Text,
    Lean,
    Bad
  } kind;
  u32 addr = 0, len = 0;
  std::string arg;  // file name/path, Poke's raw bytes, Keys' names, or the offending line for Bad
  std::array<float, 6> pose{};  // Follow: look, up; Lean: walk, sprint, sneak
};

std::vector<DevCommand> ParseDevCommands(std::string_view text);

// Keys' names -> SDL scancode bitmap and protocol mouse mask (both replaced); false on unknown names.
bool DevKeysToInput(std::string_view names, u8 keys[64], u32& buttons);

// Runs Peek/Mbx against guest memory; anything else (or a failure) yields "error: ...".
std::string RunMemoryCommand(const DevCommand& cmd, GuestMemory& mem, std::optional<u32> mailbox);
}  // namespace gxc
