#pragma once
#include "Shm.h"

namespace gxc
{
// GLFW key code for a Qt key code (Qt::Key values), or -1 if Minecraft has no such key.
int QtKeyToGlfw(int qt_key);

// Publishes the whole InputState (keys as GLFW codes, accumulated mouse deltas) on every change.
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
