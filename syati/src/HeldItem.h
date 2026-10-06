#pragma once
#include <stdint.h>

#include "Inbox.h"

class LiveActor;

// What the player holds in Minecraft, drawn in Steve's hands, the off hand's in the left (HeldItem.cpp). Create once per
// scene, after VoxelPlanetCreate (its memory comes from the planet's heap).
void HeldItemCreate();
// A GXC_MSG_HELD record from the inbox.
void HeldItemSet(const gxc::InboxHeld& held);
// Once per frame, after Mario's movement: his actor, and whether his model (Steve) is drawn.
void HeldItemFrame(const LiveActor* mario, bool shown);
// GXC_HELD_* of what is in the right hand now, and the left's << 8 (NONE also if it could not be
// built), for the dev harness.
uint32_t HeldItemKind();
// Minecraft's hand frame (blocks, before an item's display transform) to the world for a hand (0
// right, 1 left), where pieces held in it go (EntityDraw); false while Steve is not drawn.
bool HeldItemHandMtx(uint32_t hand, float out[12]);
// After Mario's animation, before his model is drawn: an arm raised to block, as Minecraft's.
// Returns how many arms it raised.
uint32_t HeldItemPoseArms(const LiveActor* mario);
