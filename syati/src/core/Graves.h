#pragma once
#include "gxc_types.h"

namespace gxc
{
// Memory something may still read for a few frames (the GPU a display list, Mario's binder the
// last triangle he stood on): freed that many frames after it was let go, first in first out.
// Only when more than SLOTS wait does the oldest go early (Early() counts those).
class Graves
{
public:
  static const u32 SLOTS = 2048;
  typedef void (*FreeFn)(void*);

  void Reset();
  // p (null: nothing) is freed with free once Tick has run frames times.
  void Bury(void* p, u32 frames, FreeFn free);
  // Once a frame.
  void Tick(FreeFn free);
  u32 Count() const { return mCount; }
  u32 Early() const { return mEarly; }

private:
  void* mPtr[SLOTS];
  u32 mDue[SLOTS];
  u32 mHead, mCount, mFrame, mEarly;
};
}  // namespace gxc
