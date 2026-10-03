#pragma once
#include <stdint.h>

#include "Inbox.h"

class LiveActor;

// What the player holds in Minecraft, drawn in Steve's right hand (HeldItem.cpp). Create once per
// scene, after VoxelPlanetCreate (it draws blocks with the planet's atlas).
void HeldItemCreate();
// A GXC_MSG_HELD record from the inbox.
void HeldItemSet(const gxc::InboxHeld& held);
// Once per frame, after Mario's movement: his actor, and whether his model (Steve) is drawn.
void HeldItemFrame(const LiveActor* mario, bool shown);
// GXC_HELD_* of what is in hand now (NONE also if it could not be built), for the dev harness.
uint32_t HeldItemKind();
