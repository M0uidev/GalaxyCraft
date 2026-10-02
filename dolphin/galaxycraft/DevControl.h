#pragma once
#include <optional>
#include <string>
#include <string_view>
#include <vector>

#include "GuestMemory.h"

namespace gxc
{
// Development commands written by tools/gxdev.py to /dev/shm/galaxycraft_ctl, one per line:
//   peek ADDR LEN   hex dump of guest memory (ADDR in hex, LEN 1..4096)
//   mbx             one-line summary of the guest mailbox
//   shot NAME | save PATH | load PATH   run by the emulator on its host thread
struct DevCommand
{
  enum Kind
  {
    Peek,
    Mbx,
    Shot,
    Save,
    Load,
    Bad
  } kind;
  u32 addr = 0, len = 0;
  std::string arg;  // file name/path, or the offending line for Bad
};

std::vector<DevCommand> ParseDevCommands(std::string_view text);

// Runs Peek/Mbx against guest memory; anything else (or a failure) yields "error: ...".
std::string RunMemoryCommand(const DevCommand& cmd, GuestMemory& mem, std::optional<u32> mailbox);
}  // namespace gxc
