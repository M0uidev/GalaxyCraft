#pragma once
#include "gxc_types.h"

namespace gxc
{
// PowerPC encodings for patching a float load in the game's code: the load is replaced by a
// branch to a stub that loads the same register from a variable of ours, then branches back.

// "b to" placed at from (relative, within ±32 MiB).
u32 EncodeBranch(u32 from, u32 to);

// Whether insn is "lfs fN, d(r2)", a load from the small-data constants (the patchable kind).
bool IsLfsR2(u32 insn);

// The 3-instruction stub, at stub_addr, for the load insn found at site: lis r12, value@ha;
// lfs fN, value@l(r12); b site + 4. r12 is free across a plain instruction (volatile, unused by
// the compiler between statements).
void BuildLoadStub(u32 insn, u32 site, u32 value_addr, u32 stub_addr, u32 out[3]);
}  // namespace gxc
