#include <cstring>
#include <filesystem>
#include <unistd.h>

#include "Input.h"
#include "Overlay.h"
#include "Shm.h"
#include "TestRunner.h"
#include "galaxycraft_protocol.h"

using namespace gxc;

namespace
{
struct ShmFixture
{
  std::string path = "/tmp/gxc_overlay_test_" + std::to_string(getpid());
  std::unique_ptr<Shm> shm = Shm::Create(path);
  ~ShmFixture() { std::filesystem::remove(path); }

  void PublishOverlay(u32 index, u32 w, u32 h, u32 frame_id, u8 fill)
  {
    std::memset(shm->Data() + GXC_OFF_OVERLAY + sizeof(GxcOverlayHeader) +
                    index * static_cast<size_t>(GXC_OVERLAY_FRAME_BYTES),
                fill, static_cast<size_t>(w) * h * 4);
    shm->SetU32(GXC_OFF_OVERLAY + offsetof(GxcOverlayHeader, width), w);
    shm->SetU32(GXC_OFF_OVERLAY + offsetof(GxcOverlayHeader, height), h);
    shm->SetU32(GXC_OFF_OVERLAY + offsetof(GxcOverlayHeader, frame_id) + 4 * index, frame_id);
    shm->StoreRelease(GXC_OFF_OVERLAY + offsetof(GxcOverlayHeader, latest), index);
  }
};

bool KeyBit(const Shm& shm, int key)
{
  const u8 byte = shm.Data()[GXC_OFF_INPUT + offsetof(GxcInputState, keys) + key / 8];
  return (byte >> (key % 8)) & 1;
}
}  // namespace

TEST(overlay_absent_until_published)
{
  ShmFixture f;
  CHECK(!LatestOverlay(*f.shm).has_value());
}

TEST(overlay_returns_latest_buffer)
{
  ShmFixture f;
  f.PublishOverlay(1, 4, 2, 77, 0xAB);
  auto o = LatestOverlay(*f.shm);
  CHECK(o && o->width == 4 && o->height == 2 && o->frame_id == 77);
  CHECK(o->rgba[0] == 0xAB && o->rgba[4 * 2 * 4 - 1] == 0xAB);
}

TEST(overlay_too_large_is_rejected)
{
  ShmFixture f;
  f.PublishOverlay(0, 4, 2, 1, 0);
  f.shm->SetU32(GXC_OFF_OVERLAY + offsetof(GxcOverlayHeader, width), 4000);
  f.shm->SetU32(GXC_OFF_OVERLAY + offsetof(GxcOverlayHeader, height), 4000);
  CHECK(!LatestOverlay(*f.shm).has_value());
}

TEST(qt_keys_map_to_sdl_scancodes)
{
  CHECK(QtKeyToScancode(0x45) == 8);           // Qt::Key_E
  CHECK(QtKeyToScancode(0x41) == 4);           // Qt::Key_A
  CHECK(QtKeyToScancode(0x5A) == 29);          // Qt::Key_Z
  CHECK(QtKeyToScancode(0x31) == 30);          // Qt::Key_1
  CHECK(QtKeyToScancode(0x30) == 39);          // Qt::Key_0
  CHECK(QtKeyToScancode(0x20) == 44);          // Qt::Key_Space
  CHECK(QtKeyToScancode(0x01000000) == 41);    // Qt::Key_Escape
  CHECK(QtKeyToScancode(0x01000001) == 43);    // Qt::Key_Tab
  CHECK(QtKeyToScancode(0x01000004) == 40);    // Qt::Key_Return
  CHECK(QtKeyToScancode(0x01000020) == 225);   // Qt::Key_Shift
  CHECK(QtKeyToScancode(0x01000021) == 224);   // Qt::Key_Control
  CHECK(QtKeyToScancode(0x01000013) == 82);    // Qt::Key_Up
  CHECK(QtKeyToScancode(0x01000030) == 58);    // Qt::Key_F1
  CHECK(QtKeyToScancode(0x0100003B) == 69);    // Qt::Key_F12
  CHECK(QtKeyToScancode(0x01000061) == -1);    // Qt::Key_VolumeDown: no key
}

TEST(qt_buttons_map_to_sdl_buttons)
{
  CHECK(QtButtonsToSdl(0x1) == (1u << 1));     // left -> SDL button 1
  CHECK(QtButtonsToSdl(0x2) == (1u << 3));     // right -> SDL button 3
  CHECK(QtButtonsToSdl(0x4) == (1u << 2));     // middle -> SDL button 2
  CHECK(QtButtonsToSdl(0x7) == 0b1110u);
}

TEST(input_writer_sets_keys_and_accumulates_mouse)
{
  ShmFixture f;
  InputWriter in(*f.shm);
  in.Key(8, true);
  in.MouseDelta(3, -2);
  in.MouseDelta(3, -2);
  in.Buttons(1);
  in.Wheel(1.5);
  CHECK(KeyBit(*f.shm, 8));
  in.Key(8, false);
  CHECK(!KeyBit(*f.shm, 8));
  GxcInputState s;
  std::memcpy(&s, f.shm->Data() + GXC_OFF_INPUT, sizeof(s));
  CHECK(s.mouse_x == 6 && s.mouse_y == -4 && s.buttons == 1 && s.wheel == 1.5);
  CHECK(s.seq != 0 && s.seq % 2 == 0);
}

TEST(input_writer_ignores_out_of_range_keys)
{
  ShmFixture f;
  InputWriter in(*f.shm);
  in.Key(-1, true);
  in.Key(512, true);
  GxcInputState s;
  std::memcpy(&s, f.shm->Data() + GXC_OFF_INPUT, sizeof(s));
  for (u8 b : s.keys)
    CHECK(b == 0);
}

TEST(input_writer_release_all_lets_go_of_keys_and_buttons)
{
  ShmFixture f;
  InputWriter in(*f.shm);
  in.Key(8, true);
  in.Key(26, true);
  in.Buttons(5);
  in.MouseDelta(3, -2);
  in.ReleaseAll();
  CHECK(!KeyBit(*f.shm, 8) && !KeyBit(*f.shm, 26));
  GxcInputState s;
  std::memcpy(&s, f.shm->Data() + GXC_OFF_INPUT, sizeof(s));
  CHECK(s.buttons == 0);
  CHECK(s.mouse_x == 3 && s.mouse_y == -2);  // accumulated motion is not a held state
}
