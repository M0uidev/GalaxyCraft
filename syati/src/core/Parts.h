#pragma once
#include "gxc_types.h"

namespace gxc
{
// A collision part as the module sees it: KCL in RAM plus its local -> galaxy matrix.
struct PartCandidate
{
  u32 id, kcl, size;
  float mtx[12];  // 3x4 row-major; the translation column is the bounding sphere's centre
  float radius;
};

// Picks the parts whose bounding sphere surface is closer than max_dist to pos
// (|pos - centre| - radius), nearest first. Writes up to max_out indices into out_idx
// (max_out is capped at 64) and returns how many it wrote.
int SelectParts(const PartCandidate* in, int n, const float pos[3], float max_dist, int* out_idx,
                int max_out);
}  // namespace gxc
