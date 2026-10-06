#pragma once
#include <stdint.h>

// Voxel planet (VoxelPlanet.cpp). Create once per scene, while actors are initialized (Mario's
// init); Frame once per frame: applies the inbox the host filled and says where it is.
// A new scene: its inbox goes to the mailbox at once, before the host can fill the old one (gone
// with the old scene's heap) with records meant for this scene.
void VoxelPlanetCreate(uint32_t* inbox_addr, uint32_t* inbox_size);
void VoxelPlanetFrame(uint32_t scene_id, uint32_t* inbox_addr, uint32_t* inbox_size);
// Mario moved this frame (before VoxelPlanetFrame): replaced collision may go once he has moved a while.
void VoxelPlanetMarioMoved();
// The collision sphere Mario should have at pos (galaxy units) in the planet's gravity, if the
// mod gave one (GxcPlanet.mario_radius): his own is 1.5 blocks wide and fits no tunnel.
bool VoxelPlanetMarioRadius(const float pos[3], float* radius);
// Mario's collision as his movement sees it, drawn over the planet (F3+B, GXC_MBX_HITBOXES):
// the balls that push him out of the blocks (blue, Mario::checkBaseTransBall), a cylinder of
// their radius from his feet (red) to the top one, and his three ground probes (yellow, 120
// degrees apart from where he faces). Galaxy units; null hides it.
struct MarioHitbox
{
  float feet[3], up[3], front[3];
  float radius;
  float balls[3][3];
};
void VoxelPlanetHitbox(const MarioHitbox* box);
// 32-byte aligned memory from the scene's MEM2 heap (null if it would leave the game short), for
// the held item (HeldItem.cpp).
uint8_t* VoxelPlanetAlloc32(uint32_t size);

// Counters for the dev harness (peek): inbox batches, records, chunks with something to draw,
// collision parts made, last chunk slot and version seen, chunks dropped for lack of memory, free
// bytes of the scene's MEM2 and MEM1 heaps (at the last batch), collision parts alive, chunks
// drawn last frame (the rest were behind the camera or the horizon), chunks left without
// collision because the stage's main collision zone was missing or full, the block atlas being put
// together (its id, bytes of it in, 1 once complete: planets are drawn only then). Chunks and
// parts count every planet's.
struct VoxelStats
{
  uint32_t batches, records, chunks, parts_made, last_slot, last_version, alloc_failed, free_mem2, free_mem1;
  uint32_t parts_live, drawn_last, no_zone;
  uint32_t atlas_id, atlas_bytes, atlas_ready;
  uint32_t far_drawn;  // parts of planets' far views drawn last frame (PlanetLod)
  // MEM2's free memory in all (free_mem2 is its largest free block: what one allocation can get),
  // and the bytes the module holds there now.
  uint32_t total_free_mem2, module_bytes;
};
extern VoxelStats gVoxelStats;
