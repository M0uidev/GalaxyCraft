#include "Graves.h"

namespace gxc
{
void Graves::Reset()
{
  mHead = mCount = mFrame = mEarly = 0;
  for (u32 i = 0; i < SLOTS; i++)
    mPtr[i] = 0;
}

void Graves::Bury(void* p, u32 frames, FreeFn free)
{
  if (!p)
    return;
  if (mCount == SLOTS)
  {
    free(mPtr[mHead]);
    mHead = (mHead + 1) % SLOTS;
    mCount--;
    mEarly++;
  }
  const u32 at = (mHead + mCount) % SLOTS;
  mPtr[at] = p;
  mDue[at] = mFrame + frames;
  mCount++;
}

void Graves::Tick(FreeFn free)
{
  mFrame++;
  // All wait the same number of frames: the oldest are due first.
  while (mCount && static_cast<s32>(mFrame - mDue[mHead]) >= 0)
  {
    free(mPtr[mHead]);
    mPtr[mHead] = 0;
    mHead = (mHead + 1) % SLOTS;
    mCount--;
  }
}
}  // namespace gxc
