#include "MarioInput.h"

#include <cmath>

namespace gxc
{
namespace
{
bool Down(const u8 keys[64], int scancode)
{
  return (keys[scancode / 8] >> (scancode % 8)) & 1;
}
}  // namespace

WiimoteState MarioInput::Update(const u8 keys[64], u32 buttons, bool in_game, bool free_pointer,
                                bool item_active)
{
  WiimoteState s;
  float x = static_cast<float>(Down(keys, SC_D)) - static_cast<float>(Down(keys, SC_A));
  float y = static_cast<float>(Down(keys, SC_W)) - static_cast<float>(Down(keys, SC_S));
  if (x != 0 && y != 0)
  {
    const float inv = 1.f / std::sqrt(2.f);
    x *= inv, y *= inv;
  }
  s.stick_x = x, s.stick_y = y;

  const bool clicks = !in_game || !item_active;
  const bool left = (buttons & MOUSE_LEFT) != 0;
  s.a = Down(keys, SC_SPACE) || (left && !in_game);
  s.b = (buttons & MOUSE_RIGHT) != 0 && clicks;
  s.z = Down(keys, SC_LSHIFT) || Down(keys, SC_RSHIFT);
  s.c = Down(keys, SC_LCTRL) || Down(keys, SC_RCTRL);
  s.plus = Down(keys, SC_ESCAPE);
  s.minus = Down(keys, SC_TAB);

  const bool spin_click = left && clicks;
  const bool f = Down(keys, SC_F);
  if ((spin_click && !m_left_was_down) || (f && !m_f_was_down))
    m_shake_frames = SHAKE_MIN_FRAMES;
  m_left_was_down = spin_click;
  m_f_was_down = f;
  s.shake = spin_click || f || m_shake_frames > 0;
  if (m_shake_frames > 0)
    m_shake_frames--;

  s.center_ir = in_game && !free_pointer;
  return s;
}

void MarioInput::Reset()
{
  m_shake_frames = 0;
  m_left_was_down = false;
  m_f_was_down = false;
}
}  // namespace gxc
