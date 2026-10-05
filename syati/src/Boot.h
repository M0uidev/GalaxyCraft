#pragma once
#include <stdint.h>

// GalaxyCraft boots SMG2 by itself (host flag GXC_MBX_BOOT_SPACE): the file selector skips the
// title, picks a file (making one if there is none) and starts it, and the stage after it is
// GalaxyCraftSpace. There Mario waits at the origin while Minecraft is in its menus
// (GXC_MBX_HOLD) and until the mod's first teleport puts him on a planet.

// From GalaxyCraft.cpp: the mailbox's host flags, and the debug words Boot fills (file
// selector, its nerve as r13 - N, frames in it, steps taken: 1 title, 2 file, 4 start, 8 stage,
// 16 new file, 32 icon skipped).
uint32_t BootHostFlags();
uint32_t* BootDebugWords();
// A new Mario (a new stage): held again if the stage is GalaxyCraftSpace.
void BootStage(const char* stage);
// After Mario's movement: put him back at the origin while he is held. True while held.
bool BootHoldMario();
// The mod teleported Mario onto a planet: no longer held.
void BootTeleported();
