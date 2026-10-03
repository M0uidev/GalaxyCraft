#include "CodePatch.h"

namespace gxc
{
u32 EncodeBranch(u32 from, u32 to)
{
  return 0x48000000u | ((to - from) & 0x03FFFFFCu);
}

bool IsLfsR2(u32 insn)
{
  return (insn >> 26) == 48 && ((insn >> 16) & 31) == 2;
}

void BuildLoadStub(u32 insn, u32 site, u32 value_addr, u32 stub_addr, u32 out[3])
{
  const u32 freg = (insn >> 21) & 31;
  const u32 ha = ((value_addr + 0x8000u) >> 16) & 0xFFFFu;
  out[0] = (15u << 26) | (12u << 21) | ha;                              // lis r12, ha
  out[1] = (48u << 26) | (freg << 21) | (12u << 16) | (value_addr & 0xFFFFu);  // lfs fN, lo(r12)
  out[2] = EncodeBranch(stub_addr + 8, site + 4);
}
}  // namespace gxc
