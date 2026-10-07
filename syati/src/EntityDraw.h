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
// GXC_MSG_HURT: Mario reacts as to an enemy's blow from there (SMG2's life meter is kept).
void EntityDrawHurt(const gxc::InboxHurt& hurt);
// GXC_MSG_SEAT: Mario sits on something (a minecart, a boat) and goes with it.
void EntityDrawSeat(const gxc::InboxSeat& seat);
// The floating origin moved: everything in the game by d (galaxy units).
void EntityDrawShift(const float d[3]);
// Right after Mario's own movement each frame: on a seat, he is put back on it.
void EntityDrawAfterMario();
// Mario is (or just was) seated by the mod: SMG2's fall-too-far kill is off.
bool EntityDrawSafeFromAbyss();
// The player glides (seat riding 2): Mario is shown, in his flight pose.
bool EntityDrawFlying();
// Bit 0: Mario sits on something; above: seat records received.
uint32_t EntityDrawRiding();
// Once per frame: Mario's actor (MarioActor).
void EntityDrawMario(void* marioActor);
// Blows passed on to Mario so far (and how many he took), for the dev harness.
uint32_t EntityDrawHurts(uint32_t* taken);
// Pieces drawn last frame, for the dev harness.
uint32_t EntityDrawCount();
