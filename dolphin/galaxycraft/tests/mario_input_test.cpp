#include <cmath>
#include <initializer_list>

#include "DevControl.h"
#include "MarioInput.h"
#include "TestRunner.h"

using namespace gxc;

namespace
{
struct Keys
{
  u8 bits[64] = {};
  Keys(std::initializer_list<int> scancodes)
  {
    for (int k : scancodes)
      bits[k / 8] |= static_cast<u8>(1u << (k % 8));
  }
};

bool Near(float a, float b)
{
  return std::fabs(a - b) <= 1e-4f;
}

bool AllReleased(const WiimoteState& s)
{
  return !s.a && !s.b && !s.minus && !s.plus && !s.home && !s.one && !s.two && !s.c && !s.z &&
         !s.shake && s.stick_x == 0 && s.stick_y == 0;
}
}  // namespace

TEST(wasd_stick_and_diagonals)
{
  MarioInput in;
  WiimoteState s = in.Update(Keys{SC_W}.bits, 0, true);
  CHECK(Near(s.stick_x, 0) && Near(s.stick_y, 1));
  s = in.Update(Keys{SC_W, SC_D}.bits, 0, true);
  CHECK(Near(s.stick_x, 0.7071f) && Near(s.stick_y, 0.7071f));
  s = in.Update(Keys{SC_A, SC_D}.bits, 0, true);
  CHECK(Near(s.stick_x, 0) && Near(s.stick_y, 0));
  s = in.Update(Keys{SC_S, SC_A}.bits, 0, true);
  CHECK(Near(s.stick_x, -0.7071f) && Near(s.stick_y, -0.7071f));
}

TEST(buttons_map)
{
  MarioInput in;
  CHECK(in.Update(Keys{SC_SPACE}.bits, 0, true).a);
  CHECK(in.Update(Keys{SC_LSHIFT}.bits, 0, true).z);
  CHECK(in.Update(Keys{SC_LCTRL}.bits, 0, true).c);
  CHECK(in.Update(Keys{SC_ESCAPE}.bits, 0, true).plus);
  CHECK(in.Update(Keys{SC_TAB}.bits, 0, true).minus);
  CHECK(in.Update(Keys{}.bits, MOUSE_RIGHT, true).b);
  const WiimoteState none = in.Update(Keys{}.bits, 0, true);
  CHECK(AllReleased(none));
}

TEST(shake_minimum_six_frames)
{
  MarioInput in;
  CHECK(in.Update(Keys{}.bits, MOUSE_LEFT, true).shake);  // call 1: pressed
  for (int call = 2; call <= 6; call++)                    // released at call 2, still shaking
    CHECK(in.Update(Keys{}.bits, 0, true).shake);
  CHECK(!in.Update(Keys{}.bits, 0, true).shake);  // call 7
  for (int call = 1; call <= 10; call++)          // held: shakes all along
    CHECK(in.Update(Keys{}.bits, MOUSE_LEFT, true).shake);
  CHECK(!in.Update(Keys{}.bits, 0, true).shake);
}

TEST(left_click_is_a_only_in_menus)
{
  MarioInput in;
  CHECK(!in.Update(Keys{}.bits, MOUSE_LEFT, true).a);
  in.Reset();
  CHECK(in.Update(Keys{}.bits, MOUSE_LEFT, false).a);
}

TEST(center_ir_only_in_game)
{
  MarioInput in;
  CHECK(in.Update(Keys{}.bits, 0, true).center_ir);
  CHECK(!in.Update(Keys{}.bits, 0, false).center_ir);
}

TEST(galaxy_view_frees_pointer_and_left_click_only_shakes)
{
  // SMG2's own camera: the mouse is the pointer, but this is still play, not a menu.
  MarioInput in;
  const WiimoteState s = in.Update(Keys{}.bits, MOUSE_LEFT, true, true);
  CHECK(!s.center_ir && s.shake && !s.a);
}

TEST(mode_switch_releases_everything)
{
  MarioInput in;
  in.Update(Keys{SC_W, SC_SPACE, SC_LSHIFT}.bits, MOUSE_LEFT | MOUSE_RIGHT, true);
  in.Reset();
  CHECK(AllReleased(in.Update(Keys{}.bits, 0, true)));
}

TEST(parses_keys)
{
  auto c = ParseDevCommands("keys w space lmb\nkeys\nkeys w jump");
  CHECK(c.size() == 3);
  CHECK(c[0].kind == DevCommand::Keys);
  CHECK(c[1].kind == DevCommand::Keys && c[1].arg.empty());
  CHECK(c[2].kind == DevCommand::Bad);
  u8 keys[64] = {};
  u32 buttons = 0;
  CHECK(DevKeysToInput(c[0].arg, keys, buttons));
  CHECK((keys[SC_W / 8] >> (SC_W % 8)) & 1);
  CHECK((keys[SC_SPACE / 8] >> (SC_SPACE % 8)) & 1);
  CHECK(buttons == MOUSE_LEFT);
  CHECK(DevKeysToInput("", keys, buttons) && buttons == 0 && keys[SC_W / 8] == 0);
}

TEST(item_in_hand_takes_the_clicks)
{
  MarioInput in;
  WiimoteState s = in.Update(Keys{}.bits, MOUSE_LEFT | MOUSE_RIGHT, true, false, true);
  CHECK(!s.shake && !s.b && !s.a);
  s = in.Update(Keys{}.bits, 0, true, false, true);
  CHECK(!s.shake);
  // Empty hand: the same clicks spin and press B.
  s = in.Update(Keys{}.bits, MOUSE_LEFT | MOUSE_RIGHT, true, false, false);
  CHECK(s.shake && s.b);
  // Menus ignore the item: left click is still A.
  MarioInput menu;
  s = menu.Update(Keys{}.bits, MOUSE_LEFT, false, false, true);
  CHECK(s.a);
}

TEST(f_spins_even_with_an_item)
{
  MarioInput in;
  WiimoteState s = in.Update(Keys{SC_F}.bits, 0, true, false, true);
  CHECK(s.shake);
  for (int i = 0; i < MarioInput::SHAKE_MIN_FRAMES; i++)
    s = in.Update(Keys{}.bits, 0, true, false, true);
  CHECK(!s.shake);
}
