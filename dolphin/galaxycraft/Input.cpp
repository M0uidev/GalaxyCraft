#include "Input.h"

#include <algorithm>
#include <atomic>
#include <cstddef>
#include <cstring>

#include "galaxycraft_protocol.h"

namespace gxc
{
int QtKeyToScancode(int k)
{
  if (k >= 0x41 && k <= 0x5A)  // A..Z
    return 4 + (k - 0x41);
  if (k >= 0x31 && k <= 0x39)  // 1..9
    return 30 + (k - 0x31);
  if (k >= 0x01000030 && k <= 0x0100003B)  // F1..F12
    return 58 + (k - 0x01000030);
  switch (k)
  {
  case 0x30: return 39;        // 0
  case 0x20: return 44;        // Space
  case 0x2D: return 45;        // -
  case 0x3D: return 46;        // =
  case 0x5B: return 47;        // [
  case 0x5D: return 48;        // ]
  case 0x5C: return 49;        // backslash
  case 0x3B: return 51;        // ;
  case 0x27: return 52;        // '
  case 0x60: return 53;        // `
  case 0x2C: return 54;        // ,
  case 0x2E: return 55;        // .
  case 0x2F: return 56;        // /
  case 0x01000000: return 41;  // Escape
  case 0x01000001: return 43;  // Tab
  case 0x01000003: return 42;  // Backspace
  case 0x01000004: return 40;  // Return
  case 0x01000005: return 88;  // Enter (keypad)
  case 0x01000006: return 73;  // Insert
  case 0x01000007: return 76;  // Delete
  case 0x01000010: return 74;  // Home
  case 0x01000011: return 77;  // End
  case 0x01000012: return 80;  // Left
  case 0x01000013: return 82;  // Up
  case 0x01000014: return 79;  // Right
  case 0x01000015: return 81;  // Down
  case 0x01000016: return 75;  // PageUp
  case 0x01000017: return 78;  // PageDown
  case 0x01000020: return 225; // Shift
  case 0x01000021: return 224; // Control
  case 0x01000022: return 227; // Meta
  case 0x01000023: return 226; // Alt
  case 0x01000024: return 57;  // CapsLock
  default: return -1;
  }
}

int EvdevToScancode(int evdev)
{
  // Linux input-event-codes.h -> USB HID usage (SDL_Scancode), for the keys a keyboard game uses.
  static constexpr int LETTERS[26] = {30, 48, 46, 32, 18, 33, 34, 35, 23, 36, 37, 38, 50,
                                      49, 24, 25, 16, 19, 31, 20, 22, 47, 17, 45, 21, 44};
  for (int i = 0; i < 26; i++)
    if (LETTERS[i] == evdev)
      return 4 + i;  // SDL_SCANCODE_A..Z
  if (evdev >= 2 && evdev <= 11)
    return 30 + (evdev - 2);  // 1..9, 0
  if (evdev >= 59 && evdev <= 68)
    return 58 + (evdev - 59);  // F1..F10
  static constexpr std::pair<int, int> OTHERS[] = {
      {1, 41},    {12, 45},  {13, 46},  {14, 42},  {15, 43},  {26, 47},  {27, 48},
      {28, 40},   {29, 224}, {39, 51},  {40, 52},  {41, 53},  {42, 225}, {43, 49},
      {51, 54},   {52, 55},  {53, 56},  {54, 229}, {56, 226}, {57, 44},  {58, 57},
      {87, 68},   {88, 69},  {97, 228}, {100, 230}, {102, 74}, {103, 82}, {104, 75},
      {105, 80},  {106, 79}, {107, 77}, {108, 81}, {109, 78}, {110, 73}, {111, 76}};
  for (const auto& [code, scancode] : OTHERS)
    if (code == evdev)
      return scancode;
  return -1;
}

void X11KeymapToScancodes(const char keymap[32], u8 keys[64])
{
  std::fill(keys, keys + 64, u8{0});
  for (int keycode = 8; keycode < 256; keycode++)
  {
    if (!((static_cast<u8>(keymap[keycode / 8]) >> (keycode % 8)) & 1))
      continue;
    const int scancode = EvdevToScancode(keycode - 8);
    if (scancode < 0 || scancode == 41)  // 41: Escape stays Dolphin's
      continue;
    keys[scancode / 8] |= static_cast<u8>(1 << (scancode % 8));
  }
}

u32 X11ButtonsToSdl(u32 x_buttons)
{
  return (x_buttons & 0x7u) << 1;  // left, middle, right; 4 and up are wheel steps
}

u32 QtButtonsToSdl(u32 qt)
{
  return ((qt & 0x1) ? 1u << 1 : 0) | ((qt & 0x4) ? 1u << 2 : 0) | ((qt & 0x2) ? 1u << 3 : 0);
}

void InputWriter::Key(int glfw_key, bool down)
{
  if (glfw_key < 0 || glfw_key >= 512)
    return;
  const u8 bit = static_cast<u8>(1u << (glfw_key % 8));
  m_keys[glfw_key / 8] = down ? (m_keys[glfw_key / 8] | bit) : (m_keys[glfw_key / 8] & ~bit);
  Publish();
}

void InputWriter::MouseDelta(double dx, double dy)
{
  m_mouse_x += dx;
  m_mouse_y += dy;
  Publish();
}

void InputWriter::Buttons(u32 mask)
{
  m_buttons = mask;
  Publish();
}

void InputWriter::SetKeys(const u8 keys[64])
{
  if (std::equal(keys, keys + 64, m_keys))
    return;
  std::copy(keys, keys + 64, m_keys);
  Publish();
}

void InputWriter::ReleaseAll()
{
  std::fill(std::begin(m_keys), std::end(m_keys), u8{0});
  m_buttons = 0;
  Publish();
}

void InputWriter::Wheel(double delta)
{
  m_wheel += delta;
  Publish();
}

u32 KeysymToCodepoint(u32 keysym)
{
  if ((keysym >= 0x20 && keysym <= 0x7E) || (keysym >= 0xA0 && keysym <= 0xFF))
    return keysym;  // Latin-1 keysyms are their own code points
  if (keysym >= 0x01000100 && keysym <= 0x0110FFFF)
    return keysym - 0x01000000;  // the rest of Unicode
  if (keysym >= 0xFFB0 && keysym <= 0xFFB9)
    return '0' + (keysym - 0xFFB0);  // keypad digits
  switch (keysym)
  {
  case 0xFF80: return ' ';  // KP_Space
  case 0xFFAA: return '*';
  case 0xFFAB: return '+';
  case 0xFFAC: return ',';
  case 0xFFAD: return '-';
  case 0xFFAE: return '.';
  case 0xFFAF: return '/';
  case 0xFFBD: return '=';
  default: return 0;
  }
}

void InputWriter::Text(u32 codepoint)
{
  if (codepoint == 0)
    return;
  const u32 seq = m_shm.GetU32(GXC_OFF_TEXT);
  m_shm.StoreRelease(GXC_OFF_TEXT, seq + 1);
  std::atomic_thread_fence(std::memory_order_release);
  m_shm.SetU32(GXC_OFF_TEXT + offsetof(GxcTextState, codepoints) + 4 * (m_text_count % GXC_TEXT_RING), codepoint);
  m_text_count++;
  m_shm.SetU32(GXC_OFF_TEXT + offsetof(GxcTextState, count), m_text_count);
  m_shm.StoreRelease(GXC_OFF_TEXT, seq + 2);
}

void InputWriter::Publish()
{
  GxcInputState s{};
  s.buttons = m_buttons;
  s.mouse_x = m_mouse_x;
  s.mouse_y = m_mouse_y;
  s.wheel = m_wheel;
  std::memcpy(s.keys, m_keys, sizeof(m_keys));
  const u32 seq = m_shm.GetU32(GXC_OFF_INPUT);
  m_shm.StoreRelease(GXC_OFF_INPUT, seq + 1);
  std::atomic_thread_fence(std::memory_order_release);
  std::memcpy(m_shm.Data() + GXC_OFF_INPUT + 4, reinterpret_cast<const u8*>(&s) + 4, sizeof(s) - 4);
  m_shm.StoreRelease(GXC_OFF_INPUT, seq + 2);
}
}  // namespace gxc
