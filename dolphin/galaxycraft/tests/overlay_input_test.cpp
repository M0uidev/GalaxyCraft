#include <bit>
#include <cstdlib>
#include <cstring>
#include <filesystem>

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
  std::string path = gxc_test::TempFile("gxc_overlay_test_");
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

TEST(input_writer_publishes_the_pointer_when_it_changes)
{
  ShmFixture f;
  InputWriter in(*f.shm);
  in.Pointer(0.25f, 0.75f, true);
  GxcPointerState p;
  std::memcpy(&p, f.shm->Data() + GXC_OFF_POINTER, sizeof(p));
  CHECK(p.x == 0.25f && p.y == 0.75f && p.flags == GXC_POINTER_INSIDE && p.seq == 2);
  in.Pointer(0.25f, 0.75f, true);  // the same: not published again
  std::memcpy(&p, f.shm->Data() + GXC_OFF_POINTER, sizeof(p));
  CHECK(p.seq == 2);
  in.Pointer(1.5f, 0.75f, false);
  std::memcpy(&p, f.shm->Data() + GXC_OFF_POINTER, sizeof(p));
  CHECK(p.flags == 0 && p.seq == 4);
  in.Pointer(1.5f, 0.75f, false, true);  // Dolphin's window went to the background
  std::memcpy(&p, f.shm->Data() + GXC_OFF_POINTER, sizeof(p));
  CHECK(p.flags == GXC_POINTER_BACKGROUND && p.seq == 6);
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

TEST(evdev_keys_map_to_sdl_scancodes)
{
  CHECK(EvdevToScancode(17) == 26);   // KEY_W
  CHECK(EvdevToScancode(30) == 4);    // KEY_A
  CHECK(EvdevToScancode(50) == 16);   // KEY_M
  CHECK(EvdevToScancode(2) == 30);    // KEY_1
  CHECK(EvdevToScancode(11) == 39);   // KEY_0
  CHECK(EvdevToScancode(57) == 44);   // KEY_SPACE
  CHECK(EvdevToScancode(28) == 40);   // KEY_ENTER
  CHECK(EvdevToScancode(42) == 225);  // KEY_LEFTSHIFT
  CHECK(EvdevToScancode(29) == 224);  // KEY_LEFTCTRL
  CHECK(EvdevToScancode(56) == 226);  // KEY_LEFTALT
  CHECK(EvdevToScancode(59) == 58);   // KEY_F1
  CHECK(EvdevToScancode(88) == 69);   // KEY_F12
  CHECK(EvdevToScancode(103) == 82);  // KEY_UP
  CHECK(EvdevToScancode(1) == 41);    // KEY_ESC
  CHECK(EvdevToScancode(240) == -1);
  CHECK(EvdevToScancode(-3) == -1);
}

TEST(x11_keymap_becomes_sdl_bitmap_without_escape)
{
  char keymap[32] = {};
  for (int keycode : {25, 65, 9})  // X keycode = evdev + 8: w, space, Escape
    keymap[keycode / 8] |= static_cast<char>(1 << (keycode % 8));
  u8 keys[64];
  X11KeymapToScancodes(keymap, keys);
  auto bit = [&](int sc) { return (keys[sc / 8] >> (sc % 8)) & 1; };
  CHECK(bit(26) && bit(44));
  CHECK(!bit(41));  // Escape belongs to Dolphin
  int set = 0;
  for (u8 b : keys)
    set += std::popcount(b);
  CHECK(set == 2);
}

TEST(x11_buttons_map_to_sdl_buttons)
{
  CHECK(X11ButtonsToSdl(0) == 0);
  CHECK(X11ButtonsToSdl(1) == 2);       // left
  CHECK(X11ButtonsToSdl(2) == 4);       // middle
  CHECK(X11ButtonsToSdl(4) == 8);       // right
  CHECK(X11ButtonsToSdl(0x1F) == 0x0E); // wheel "buttons" 4/5 are not buttons
}

TEST(dinput_keys_map_like_evdev)
{
  // Set 1 scancodes below 0x59: the same codes as evdev.
  for (int code = 0; code <= 0x58; code++)
    CHECK(DikToScancode(code) == EvdevToScancode(code));
  CHECK(DikToScancode(0x01) == 41);   // Escape
  CHECK(DikToScancode(0x1E) == 4);    // A
  CHECK(DikToScancode(0x1D) == 224);  // left Ctrl
  CHECK(DikToScancode(0x9D) == 228);  // right Ctrl (extended)
  CHECK(DikToScancode(0xB8) == 230);  // right Alt
  CHECK(DikToScancode(0xC8) == 82);   // Up
  CHECK(DikToScancode(0xCB) == 80);   // Left
  CHECK(DikToScancode(0xCD) == 79);   // Right
  CHECK(DikToScancode(0xD0) == 81);   // Down
  CHECK(DikToScancode(0xD2) == 73);   // Insert
  CHECK(DikToScancode(0xD3) == 76);   // Delete
  CHECK(DikToScancode(0x64) == -1);   // F13: not evdev's right Alt
  CHECK(DikToScancode(0x61) == -1);   // not evdev's right Ctrl

  u8 dik[256] = {};
  dik[0x11] = 0x80;  // W
  dik[0x2A] = 0x80;  // left Shift
  dik[0x01] = 0x80;  // Escape: Dolphin's
  dik[0x1F] = 0x7F;  // S, bit 7 clear: up
  u8 keys[64];
  DikKeysToScancodes(dik, keys);
  auto bit = [&](int sc) { return (keys[sc / 8] >> (sc % 8)) & 1; };
  CHECK(bit(26) && bit(225));
  CHECK(!bit(41) && !bit(22));
  int set = 0;
  for (u8 b : keys)
    set += std::popcount(b);
  CHECK(set == 2);
}

TEST(dinput_buttons_map_to_sdl_buttons)
{
  u8 b[8] = {};
  CHECK(DInputButtonsToSdl(b) == 0);
  b[0] = 0x80;
  CHECK(DInputButtonsToSdl(b) == 2);  // left
  b[0] = 0, b[1] = 0x80;
  CHECK(DInputButtonsToSdl(b) == 8);  // right
  b[1] = 0, b[2] = 0x80;
  CHECK(DInputButtonsToSdl(b) == 4);  // middle
  b[0] = b[1] = b[3] = b[4] = 0x80;
  CHECK(DInputButtonsToSdl(b) == 0x3E);
}

TEST(input_writer_set_keys_publishes_only_changes)
{
  ShmFixture f;
  InputWriter in(*f.shm);
  u8 keys[64] = {};
  keys[26 / 8] = 1 << (26 % 8);
  in.SetKeys(keys);
  CHECK(KeyBit(*f.shm, 26));
  GxcInputState s;
  std::memcpy(&s, f.shm->Data() + GXC_OFF_INPUT, sizeof(s));
  const u32 seq = s.seq;
  in.SetKeys(keys);
  std::memcpy(&s, f.shm->Data() + GXC_OFF_INPUT, sizeof(s));
  CHECK(s.seq == seq);
  keys[26 / 8] = 0;
  in.SetKeys(keys);
  CHECK(!KeyBit(*f.shm, 26));
}

TEST(typed_text_goes_round_the_ring)
{
  ShmFixture f;
  InputWriter w(*f.shm);
  for (u32 i = 0; i < GXC_TEXT_RING + 2; i++)
    w.Text('a' + i);
  w.Text(0);  // types nothing
  CHECK(f.shm->GetU32(GXC_OFF_TEXT + offsetof(GxcTextState, count)) == GXC_TEXT_RING + 2);
  const u32 base = GXC_OFF_TEXT + offsetof(GxcTextState, codepoints);
  CHECK(f.shm->GetU32(base) == 'a' + GXC_TEXT_RING);  // the 17th overwrote the 1st
  CHECK(f.shm->GetU32(base + 4 * 2) == 'c');
  CHECK(f.shm->GetU32(GXC_OFF_TEXT) % 2 == 0);
}

TEST(keysyms_to_characters)
{
  CHECK(KeysymToCodepoint('/') == '/');
  CHECK(KeysymToCodepoint(0xF1) == 0xF1);              // ntilde, on a Spanish keyboard
  CHECK(KeysymToCodepoint(0x010020AC) == 0x20AC);      // EuroSign
  CHECK(KeysymToCodepoint(0xFFB7) == '7');             // KP_7
  CHECK(KeysymToCodepoint(0xFF0D) == 0);               // Return
  CHECK(KeysymToCodepoint(0xFFE1) == 0);               // Shift_L
  CHECK(KeysymToCodepoint(0xFE51) == 0);               // dead_acute
}

TEST(shm_dir_is_gxc_shm_dir_or_the_system_default)
{
#ifdef _WIN32
  _putenv_s("GXC_SHM_DIR", "C:\\gxc\\");
  CHECK(ShmFile("galaxycraft_v1") == "C:\\gxc\\galaxycraft_v1");
  _putenv_s("GXC_SHM_DIR", "");
  CHECK(!ShmDir().empty() && ShmDir().back() != '\\');
#else
  setenv("GXC_SHM_DIR", "/run/gxc/", 1);
  CHECK(ShmFile("galaxycraft_v1") == "/run/gxc/galaxycraft_v1");
  unsetenv("GXC_SHM_DIR");
  CHECK(ShmFile("galaxycraft_v1") == "/dev/shm/galaxycraft_v1");
#endif
}
