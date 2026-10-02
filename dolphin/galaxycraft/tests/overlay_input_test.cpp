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

TEST(qt_keys_map_to_glfw)
{
  CHECK(QtKeyToGlfw(0x45) == 69);              // Qt::Key_E
  CHECK(QtKeyToGlfw(0x31) == 49);              // Qt::Key_1
  CHECK(QtKeyToGlfw(0x20) == 32);              // Qt::Key_Space
  CHECK(QtKeyToGlfw(0x01000000) == 256);       // Qt::Key_Escape
  CHECK(QtKeyToGlfw(0x01000020) == 340);       // Qt::Key_Shift
  CHECK(QtKeyToGlfw(0x01000013) == 265);       // Qt::Key_Up
  CHECK(QtKeyToGlfw(0x01000030) == 290);       // Qt::Key_F1
  CHECK(QtKeyToGlfw(0x01000061) == -1);        // Qt::Key_VolumeDown: no GLFW key
}

TEST(input_writer_sets_keys_and_accumulates_mouse)
{
  ShmFixture f;
  InputWriter in(*f.shm);
  in.Key(69, true);
  in.MouseDelta(3, -2);
  in.MouseDelta(3, -2);
  in.Buttons(1);
  in.Wheel(1.5);
  CHECK(KeyBit(*f.shm, 69));
  in.Key(69, false);
  CHECK(!KeyBit(*f.shm, 69));
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
