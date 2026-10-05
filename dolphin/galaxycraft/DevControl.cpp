#include "DevControl.h"

#include <algorithm>
#include <array>
#include <bit>
#include <charconv>
#include <cstdio>
#include <cstring>

#include "MarioInput.h"
#include "galaxycraft_protocol.h"

namespace gxc
{
namespace
{
constexpr u32 PEEK_MAX = 4096;
constexpr u32 POKE_MAX = 64;

std::vector<std::string_view> Words(std::string_view line)
{
  std::vector<std::string_view> out;
  size_t i = 0;
  while (i < line.size())
  {
    while (i < line.size() && (line[i] == ' ' || line[i] == '\t' || line[i] == '\r'))
      i++;
    const size_t start = i;
    while (i < line.size() && line[i] != ' ' && line[i] != '\t' && line[i] != '\r')
      i++;
    if (i > start)
      out.push_back(line.substr(start, i - start));
  }
  return out;
}

std::optional<u32> ParseU32(std::string_view s, int base)
{
  if (base == 16 && (s.starts_with("0x") || s.starts_with("0X")))
    s.remove_prefix(2);
  u32 v = 0;
  auto [end, ec] = std::from_chars(s.data(), s.data() + s.size(), v, base);
  if (s.empty() || ec != std::errc() || end != s.data() + s.size())
    return std::nullopt;
  return v;
}

std::optional<float> ParseFloat(std::string_view s)
{
  float v = 0;
  auto [end, ec] = std::from_chars(s.data(), s.data() + s.size(), v);
  if (s.empty() || ec != std::errc() || end != s.data() + s.size())
    return std::nullopt;
  return v;
}

DevCommand ParseLine(std::string_view line)
{
  const auto w = Words(line);
  const DevCommand bad{DevCommand::Bad, 0, 0, std::string(line)};
  if (w[0] == "peek")
  {
    if (w.size() != 3)
      return bad;
    const auto addr = ParseU32(w[1], 16);
    const auto len = ParseU32(w[2], 10);
    if (!addr || !len || *len == 0 || *len > PEEK_MAX)
      return bad;
    return {DevCommand::Peek, *addr, *len, {}};
  }
  if (w[0] == "poke")
  {
    if (w.size() != 3 || w[2].size() % 2 != 0 || w[2].size() > 2 * POKE_MAX)
      return bad;
    const auto addr = ParseU32(w[1], 16);
    if (!addr)
      return bad;
    DevCommand cmd{DevCommand::Poke, *addr, static_cast<u32>(w[2].size() / 2), {}};
    for (size_t i = 0; i < w[2].size(); i += 2)
    {
      const auto byte = ParseU32(w[2].substr(i, 2), 16);
      if (!byte)
        return bad;
      cmd.arg.push_back(static_cast<char>(*byte));
    }
    return cmd;
  }
  if (w[0] == "mbx")
    return w.size() == 1 ? DevCommand{DevCommand::Mbx, 0, 0, {}} : bad;
  if (w[0] == "status")
    return w.size() == 1 ? DevCommand{DevCommand::Status, 0, 0, {}} : bad;
  if (w[0] == "link")
    return w.size() == 2 && (w[1] == "on" || w[1] == "off") ?
               DevCommand{DevCommand::Link, 0, 0, std::string(w[1])} :
               bad;
  if (w[0] == "text")
  {
    const size_t at = line.find("text") + 4;
    std::string_view rest = line.substr(at);
    if (!rest.empty() && rest.front() == ' ')
      rest.remove_prefix(1);
    return rest.empty() ? bad : DevCommand{DevCommand::Text, 0, 0, std::string(rest)};
  }
  if (w[0] == "lean")
  {
    DevCommand cmd{DevCommand::Lean, 0, 0, {}};
    if (w.size() != 4)
      return bad;
    for (size_t i = 1; i < 4; i++)
    {
      const auto v = ParseFloat(w[i]);
      if (!v || *v < 0 || *v > 1)
        return bad;
      cmd.pose[i - 1] = *v;
    }
    return cmd;
  }
  if (w[0] == "keys")
  {
    std::string names;
    for (size_t i = 1; i < w.size(); i++)
      names += (i > 1 ? " " : "") + std::string(w[i]);
    u8 keys[64];
    u32 buttons;
    return DevKeysToInput(names, keys, buttons) ? DevCommand{DevCommand::Keys, 0, 0, names} : bad;
  }
  if (w[0] == "unfollow")
    return w.size() == 1 ? DevCommand{DevCommand::Unfollow, 0, 0, {}} : bad;
  if (w[0] == "follow")
  {
    // Trailing words: back (third person), feel (Minecraft's feel, GXC_PLAYER_MC_FEEL).
    u32 opts = 0;
    size_t n = w.size();
    while (n > 1 && (w[n - 1] == "back" || w[n - 1] == "feel"))
      opts |= w[--n] == "back" ? 1u : 2u;
    if (n != 4 && n != 7)
      return bad;
    DevCommand cmd{DevCommand::Follow, opts, 0, {}};
    for (size_t i = 1; i < n; i++)
    {
      const auto v = ParseFloat(w[i]);
      if (!v)
        return bad;
      cmd.pose[i - 1] = *v;
    }
    return cmd;
  }
  const std::pair<std::string_view, DevCommand::Kind> with_arg[] = {
      {"shot", DevCommand::Shot}, {"save", DevCommand::Save}, {"load", DevCommand::Load}};
  for (const auto& [name, kind] : with_arg)
    if (w[0] == name)
      return w.size() == 2 ? DevCommand{kind, 0, 0, std::string(w[1])} : bad;
  return bad;
}

std::string Unreadable(u32 addr)
{
  char buf[40];
  std::snprintf(buf, sizeof(buf), "error: unreadable 0x%08x\n", addr);
  return buf;
}

std::string Peek(const DevCommand& cmd, GuestMemory& mem)
{
  std::vector<u8> data(cmd.len);
  if (!mem.Read(cmd.addr, data.data(), cmd.len))
    return Unreadable(cmd.addr);
  std::string out;
  char buf[16];
  for (u32 i = 0; i < cmd.len; i++)
  {
    if (i % 16 == 0)
    {
      std::snprintf(buf, sizeof(buf), "%08x:", cmd.addr + i);
      out += buf;
    }
    std::snprintf(buf, sizeof(buf), " %02x", data[i]);
    out += buf;
    if (i % 16 == 15 || i + 1 == cmd.len)
      out += '\n';
  }
  return out;
}

std::string Mailbox(GuestMemory& mem, u32 at)
{
  std::array<u8, offsetof(GxcMailbox, parts)> b;
  if (!mem.Read(at, b.data(), static_cast<u32>(b.size())))
    return Unreadable(at);
  auto u = [&](size_t off) {
    return static_cast<u32>(b[off]) << 24 | static_cast<u32>(b[off + 1]) << 16 |
           static_cast<u32>(b[off + 2]) << 8 | b[off + 3];
  };
  auto f = [&](size_t off) { return static_cast<double>(std::bit_cast<float>(u(off))); };
  char stage[33] = {};
  if (!mem.Read(at + offsetof(GxcMailbox, stage_name), stage, 32))
    stage[0] = 0;
  char buf[512];
  std::snprintf(buf, sizeof(buf),
                "at=%08x game_seq=%u host_seq=%u scene=%u grav=(%.3f,%.3f,%.3f) anchor=(%.1f,%.1f,%.1f) "
                "flags=%x/%x player=(%.1f,%.1f,%.1f) parts=%u stage=%s\n",
                at, u(offsetof(GxcMailbox, game_seq)), u(offsetof(GxcMailbox, host_seq)),
                u(offsetof(GxcMailbox, scene_id)), f(24), f(28), f(32), f(36), f(40), f(44),
                u(offsetof(GxcMailbox, game_flags)), u(offsetof(GxcMailbox, host_flags)), f(56),
                f(60), f(64), u(offsetof(GxcMailbox, part_count)), stage[0] ? stage : "-");
  return buf;
}
}  // namespace

bool DevKeysToInput(std::string_view names, u8 keys[64], u32& buttons)
{
  const std::pair<std::string_view, int> scancodes[] = {
      {"w", SC_W},         {"a", SC_A},         {"s", SC_S},           {"d", SC_D},
      {"space", SC_SPACE}, {"shift", SC_LSHIFT}, {"ctrl", SC_LCTRL}, {"esc", SC_ESCAPE},
      {"tab", SC_TAB}};
  std::fill(keys, keys + 64, u8{0});
  buttons = 0;
  for (const std::string_view name : Words(names))
  {
    if (name == "lmb" || name == "rmb")
    {
      buttons |= name == "lmb" ? MOUSE_LEFT : MOUSE_RIGHT;
      continue;
    }
    const auto it = std::find_if(std::begin(scancodes), std::end(scancodes),
                                 [&](const auto& p) { return p.first == name; });
    if (it == std::end(scancodes))
      return false;
    keys[it->second / 8] |= static_cast<u8>(1u << (it->second % 8));
  }
  return true;
}

std::vector<DevCommand> ParseDevCommands(std::string_view text)
{
  std::vector<DevCommand> out;
  while (!text.empty())
  {
    const size_t nl = text.find('\n');
    const std::string_view line = text.substr(0, nl);
    text.remove_prefix(nl == std::string_view::npos ? text.size() : nl + 1);
    if (!Words(line).empty())
      out.push_back(ParseLine(line));
  }
  return out;
}

std::string RunMemoryCommand(const DevCommand& cmd, GuestMemory& mem, std::optional<u32> mailbox)
{
  switch (cmd.kind)
  {
  case DevCommand::Peek:
    return Peek(cmd, mem);
  case DevCommand::Poke:
  {
    if (!mem.Write(cmd.addr, cmd.arg.data(), cmd.len))
      return Unreadable(cmd.addr);
    char buf[40];
    std::snprintf(buf, sizeof(buf), "ok poke %08x %u\n", cmd.addr, cmd.len);
    return buf;
  }
  case DevCommand::Mbx:
    return mailbox ? Mailbox(mem, *mailbox) : "error: no mailbox\n";
  case DevCommand::Bad:
    return "error: bad command: " + cmd.arg + "\n";
  default:
    return "error: not a memory command\n";
  }
}
}  // namespace gxc
