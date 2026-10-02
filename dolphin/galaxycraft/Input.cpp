#include "Input.h"

#include <algorithm>
#include <atomic>
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
