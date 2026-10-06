#pragma once
#include <stdint.h>

// GalaxyCraft boots SMG2 by itself (host flag GXC_MBX_BOOT_SPACE): the file selector skips the
// title, picks a file (making one if there is none) and starts it, and the stage after it is
// GalaxyCraftSpace. There Mario waits at the origin until the mod's teleport puts him on a
// planet, and again once the mod drops every planet (it left the world, back to its menus). A
// stall of Minecraft (GXC_MBX_HOLD for a moment) does not move him.

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
// The mod dropped every planet (it left its world): Mario waits at the origin again.
void BootPlanetsDropped();
// Every frame: the frames the galaxy's music has played in GalaxyCraftSpace (Minecraft plays its
// own; none is expected, and MR::stopStageBGM every frame froze the game). Bit 0: playing now.
uint32_t BootMusicFrames();
