#pragma once
#include "Shm.h"

namespace gxc
{
// SDL scancode (USB HID usage, what Minecraft 26.x uses) for a Qt key code, or -1 if none.
int QtKeyToScancode(int qt_key);

// Qt mouse button bits (left 1, right 2, middle 4) -> protocol mask (bit n = SDL button n).
u32 QtButtonsToSdl(u32 qt_buttons);

// SDL scancode for a Linux evdev key code (X11 keycode - 8), or -1 if none.
int EvdevToScancode(int evdev);

// X11 keymap (XQueryKeymap: bit per keycode) -> protocol key bitmap of SDL scancodes. Escape is
// left out: it stays Dolphin's (pause/stop).
void X11KeymapToScancodes(const char keymap[32], u8 keys[64]);

// XInput2 button bits (bit n = X button n+1: left, middle, right, wheel...) -> protocol mask.
u32 X11ButtonsToSdl(u32 x_buttons);

// SDL scancode for a DirectInput key code (DIK_*, the PC's set 1 scancodes; 0x80 and up are the
// extended keys), or -1 if none. The same keys as EvdevToScancode.
int DikToScancode(int dik);

// DirectInput keyboard state (bit 7 of each DIK_* byte: down) -> protocol key bitmap of SDL
// scancodes. Escape is left out, as in X11KeymapToScancodes.
void DikKeysToScancodes(const u8 dik[256], u8 keys[64]);

// DirectInput mouse buttons (DIMOUSESTATE2::rgbButtons: left, right, middle, back, forward; bit 7
// down) -> protocol mask.
u32 DInputButtonsToSdl(const u8 buttons[8]);

// The character an X11 keysym types (the layout already applied), or 0 if it types none
// (modifiers, arrows, Return, dead keys...).
u32 KeysymToCodepoint(u32 keysym);

// Publishes the whole InputState (keys as SDL scancodes, accumulated mouse deltas) on every change.
class InputWriter
{
public:
  explicit InputWriter(Shm& shm) : m_shm(shm) {}
  void Key(int glfw_key, bool down);
  void MouseDelta(double dx, double dy);
  void Buttons(u32 mask);
  void Wheel(double delta);
  // Lets go of every key and mouse button (input is being taken away from the mod).
  void ReleaseAll();
  // Replaces the whole key bitmap (SDL scancodes); publishes only if something changed.
  void SetKeys(const u8 keys[64]);
  // A character typed (GxcTextState), for Minecraft's text fields.
  void Text(u32 codepoint);
  // Where the pointer is over the render window (GxcPointerState: 0..1, inside or not);
  // publishes only if it changed.
  void Pointer(float x, float y, bool inside);

private:
  void Publish();

  Shm& m_shm;
  u8 m_keys[64] = {};
  double m_mouse_x = 0, m_mouse_y = 0, m_wheel = 0;
  u32 m_buttons = 0;
  u32 m_text_count = 0;
  float m_pointer_x = -1, m_pointer_y = -1;
  bool m_pointer_inside = false;
};
}  // namespace gxc
