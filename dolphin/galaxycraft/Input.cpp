#include "Input.h"

#include <atomic>
#include <cstring>

#include "galaxycraft_protocol.h"

namespace gxc
{
int QtKeyToGlfw(int k)
{
  // Printable keys share their ASCII codes between Qt (upper-case letters) and GLFW.
  if (k == 0x20 || k == 0x27 || (k >= 0x2C && k <= 0x39) || k == 0x3B || k == 0x3D ||
      (k >= 0x41 && k <= 0x5D) || k == 0x60)
    return k;
  if (k >= 0x01000030 && k <= 0x0100003B)  // F1..F12
    return 290 + (k - 0x01000030);
  switch (k)
  {
  case 0x01000000: return 256;  // Escape
  case 0x01000001: return 258;  // Tab
  case 0x01000003: return 259;  // Backspace
  case 0x01000004: return 257;  // Return
  case 0x01000005: return 335;  // Enter (keypad)
  case 0x01000006: return 260;  // Insert
  case 0x01000007: return 261;  // Delete
  case 0x01000010: return 268;  // Home
  case 0x01000011: return 269;  // End
  case 0x01000012: return 263;  // Left
  case 0x01000013: return 265;  // Up
  case 0x01000014: return 262;  // Right
  case 0x01000015: return 264;  // Down
  case 0x01000016: return 266;  // PageUp
  case 0x01000017: return 267;  // PageDown
  case 0x01000020: return 340;  // Shift
  case 0x01000021: return 341;  // Control
  case 0x01000022: return 343;  // Meta
  case 0x01000023: return 342;  // Alt
  case 0x01000024: return 280;  // CapsLock
  default: return -1;
  }
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
