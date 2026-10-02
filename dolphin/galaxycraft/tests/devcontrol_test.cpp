#include <cstring>

#include "DevControl.h"
#include "FakeGuestMemory.h"
#include "TestRunner.h"
#include "galaxycraft_protocol.h"

using namespace gxc;

TEST(parses_every_command_kind)
{
  auto c = ParseDevCommands("peek 0x80001800 16\nmbx\nshot a\nsave /x\nload /x\nfoo\n");
  CHECK(c.size() == 6);
  CHECK(c[0].kind == DevCommand::Peek && c[0].addr == 0x80001800u && c[0].len == 16);
  CHECK(c[1].kind == DevCommand::Mbx);
  CHECK(c[2].kind == DevCommand::Shot && c[2].arg == "a");
  CHECK(c[3].kind == DevCommand::Save && c[3].arg == "/x");
  CHECK(c[4].kind == DevCommand::Load && c[4].arg == "/x");
  CHECK(c[5].kind == DevCommand::Bad && c[5].arg == "foo");
}

TEST(malformed_commands_are_bad)
{
  const char* bad[] = {"peek 0x80001800", "peek 0x80001800 4097", "peek 0x80001800 0",
                       "peek zz 4",       "peek 0x0 99999999",    "shot",
                       "save",            "peek 0x80001800 16 x", "mbx now"};
  for (const char* line : bad)
  {
    auto c = ParseDevCommands(line);
    CHECK(c.size() == 1 && c[0].kind == DevCommand::Bad);
  }
  CHECK(ParseDevCommands("\n\n  \n").empty());
  auto c = ParseDevCommands("peek 80001800 2");  // 0x prefix optional
  CHECK(c.size() == 1 && c[0].kind == DevCommand::Peek && c[0].addr == 0x80001800u);
}

TEST(peek_dumps_hex)
{
  FakeGuestMemory mem;
  const u8 bytes[18] = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 0xAB, 0xCD};
  mem.PutBytes(0x80001800u, bytes, 18);
  auto c = ParseDevCommands("peek 0x80001800 18");
  const std::string out = RunMemoryCommand(c[0], mem, std::nullopt);
  CHECK(out == "80001800: 00 01 02 03 04 05 06 07 08 09 0a 0b 0c 0d 0e 0f\n"
               "80001810: ab cd\n");
}

TEST(peek_out_of_range_is_error)
{
  FakeGuestMemory mem;
  auto c = ParseDevCommands("peek 0x0 16\npeek 0x807ffff8 16");
  CHECK(RunMemoryCommand(c[0], mem, std::nullopt) == "error: unreadable 0x00000000\n");
  CHECK(RunMemoryCommand(c[1], mem, std::nullopt) == "error: unreadable 0x807ffff8\n");
}

TEST(mbx_without_mailbox_is_error)
{
  FakeGuestMemory mem;
  auto c = ParseDevCommands("mbx");
  CHECK(RunMemoryCommand(c[0], mem, std::nullopt) == "error: no mailbox\n");
}

TEST(bad_and_host_commands_are_errors_here)
{
  FakeGuestMemory mem;
  auto c = ParseDevCommands("foo bar\nshot a");
  CHECK(RunMemoryCommand(c[0], mem, std::nullopt) == "error: bad command: foo bar\n");
  CHECK(RunMemoryCommand(c[1], mem, std::nullopt).starts_with("error: "));
}

TEST(mbx_summarizes_mailbox)
{
  FakeGuestMemory mem;
  constexpr u32 MBX = 0x80401000u;
  mem.PutBytes(MBX, GXC_MBX_MAGIC, 8);
  mem.PutU32(MBX + 12, 1234);  // game_seq
  mem.PutU32(MBX + 20, 5);     // scene
  mem.PutF32(MBX + 24, 0), mem.PutF32(MBX + 28, -1), mem.PutF32(MBX + 32, 0);
  mem.PutF32(MBX + 36, 1.5f), mem.PutF32(MBX + 40, 2), mem.PutF32(MBX + 44, -3);
  mem.PutU32(MBX + 48, 2), mem.PutU32(MBX + 52, GXC_MBX_DRIVE);
  mem.PutF32(MBX + 56, 7), mem.PutF32(MBX + 60, 8), mem.PutF32(MBX + 64, 9);
  mem.PutU32(MBX + 100, 3);
  auto c = ParseDevCommands("mbx");
  const std::string out = RunMemoryCommand(c[0], mem, MBX);
  for (const char* key : {"game_seq=1234 ", "scene=5 ", "grav=(0.000,-1.000,0.000) ",
                          "anchor=(1.5,2.0,-3.0) ", "flags=2/1 ", "parts=3", "player=(7.0,8.0,9.0)"})
    CHECK(out.find(key) != std::string::npos);
  CHECK(out.ends_with("\n"));
}

TEST(mbx_unreadable_is_error)
{
  FakeGuestMemory mem;
  auto c = ParseDevCommands("mbx");
  CHECK(RunMemoryCommand(c[0], mem, 0x807fffc0u) == "error: unreadable 0x807fffc0\n");
}

TEST(parses_follow_and_unfollow)
{
  auto c = ParseDevCommands("follow 1 0 0\nfollow 1 0 0 0 1 0\nunfollow\n"
                            "follow 1 0\nunfollow now\nfollow 1 x 0\ndrive 1 2 3 0 0 1");
  CHECK(c.size() == 7);
  CHECK(c[0].kind == DevCommand::Follow && c[0].pose[0] == 1 && c[0].pose[1] == 0 &&
        c[0].pose[4] == 0);  // no up given: zero, host picks -gravity
  CHECK(c[1].kind == DevCommand::Follow && c[1].pose[4] == 1);
  CHECK(c[2].kind == DevCommand::Unfollow);
  for (int i = 3; i < 7; i++)
    CHECK(c[i].kind == DevCommand::Bad);
}

TEST(mbx_reports_its_address)
{
  FakeGuestMemory mem;
  mem.PutBytes(0x80401000u, GXC_MBX_MAGIC, 8);
  auto c = ParseDevCommands("mbx");
  CHECK(RunMemoryCommand(c[0], mem, 0x80401000u).starts_with("at=80401000 game_seq="));
}

TEST(parses_link)
{
  auto c = ParseDevCommands("link on\nlink off\nlink maybe\nlink");
  CHECK(c.size() == 4);
  CHECK(c[0].kind == DevCommand::Link && c[0].arg == "on");
  CHECK(c[1].kind == DevCommand::Link && c[1].arg == "off");
  CHECK(c[2].kind == DevCommand::Bad && c[3].kind == DevCommand::Bad);
}

TEST(parses_status)
{
  auto c = ParseDevCommands("status\nstatus now");
  CHECK(c.size() == 2 && c[0].kind == DevCommand::Status && c[1].kind == DevCommand::Bad);
}
