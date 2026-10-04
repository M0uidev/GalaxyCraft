#pragma once
#include <stdint.h>

#include "Inbox.h"

// Entities on the planet as Minecraft has them (dropped items, mobs, TNT, falling blocks), drawn
// inside SMG2 (EntityDraw.cpp). Create once per scene, after VoxelPlanetCreate (its memory comes
// from the planet's heap).
void EntityDrawCreate();
// GXC_MSG_SKIN, MODEL and ENTITIES records from the inbox.
void EntityDrawSkin(const gxc::InboxSkin& skin);
void EntityDrawModel(const gxc::InboxModel& model);
void EntityDrawFrame(const gxc::InboxEntities& entities);
// Pieces drawn last frame, for the dev harness.
uint32_t EntityDrawCount();
