// GalaxyCraft module for SMG2 (SB4E), loaded by Syati's loader as CustomCode_SB4E.bin.
// Publishes the GXCRMBX1 mailbox that Dolphin's host bridge mirrors to the Minecraft mod.
#include "syati.h"

#include "galaxycraft_protocol.h"

namespace
{
// The magic lives only in this initializer, so the host's RAM scan finds the mailbox itself
// and not a stray copy of the string.
GxcMailbox gMailbox = {{'G', 'X', 'C', 'R', 'M', 'B', 'X', '1'}, GXC_MBX_VERSION};
}  // namespace

// Originals, by their mangled names in symbols/SB4E.txt.
extern "C" void movement__10MarioActorFv(void* self);

namespace
{
void MarioMovement(void* self)
{
  movement__10MarioActorFv(self);
  gMailbox.game_seq++;
}
}  // namespace

// __vt__10MarioActor + 0x14: MarioActor::movement.
kmWritePointer(0x806C7448 + 0x14, MarioMovement);
