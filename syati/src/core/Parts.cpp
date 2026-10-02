#include "Parts.h"

#include "ViewMath.h"

namespace gxc
{
int SelectParts(const PartCandidate* in, int n, const float pos[3], float max_dist, int* out_idx,
                int max_out)
{
  enum { CAP = 64 };
  if (max_out > CAP)
    max_out = CAP;
  if (max_out < 0)
    max_out = 0;
  float dist[CAP];
  int count = 0;
  for (int i = 0; i < n; i++)
  {
    const float* m = in[i].mtx;
    const float d[3] = {pos[0] - m[3], pos[1] - m[7], pos[2] - m[11]};
    const float surface = Sqrt(d[0] * d[0] + d[1] * d[1] + d[2] * d[2]) - in[i].radius;
    if (!(surface < max_dist))
      continue;
    // Insertion into the sorted list; once full, only something nearer than the last gets in.
    int k = count < max_out ? count++ : max_out;
    if (k == max_out && (max_out == 0 || !(surface < dist[max_out - 1])))
      continue;
    if (k == max_out)
      k = max_out - 1;
    while (k > 0 && surface < dist[k - 1])
    {
      dist[k] = dist[k - 1];
      out_idx[k] = out_idx[k - 1];
      k--;
    }
    dist[k] = surface;
    out_idx[k] = i;
  }
  return count;
}
}  // namespace gxc
