#pragma once
#include "Shm.h"

namespace gxc
{
// SDL scancode (USB HID usage, what Minecraft 26.x uses) for a Qt key code, or -1 if none.
int QtKeyToScancode(int qt_key);

// Qt mouse button bits (left 1, right 2, middle 4) -> protocol mask (bit n = SDL button n).
u32 QtButtonsToSdl(u32 qt_buttons);

// Publishes the whole InputState (keys as SDL scancodes, accumulated mouse deltas) on every change.
class InputWriter
{
public:
  explicit InputWriter(Shm& shm) : m_shm(shm) {}
  void Key(int glfw_key, bool down);
  void MouseDelta(double dx, double dy);
  void Buttons(u32 mask);
  void Wheel(double delta);

private:
  void Publish();

  Shm& m_shm;
  u8 m_keys[64] = {};
  double m_mouse_x = 0, m_mouse_y = 0, m_wheel = 0;
  u32 m_buttons = 0;
};
}  // namespace gxc
