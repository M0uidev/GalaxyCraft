#pragma once
#include <stdint.h>

// Voxel planet (VoxelPlanet.cpp). Create once per scene, while actors are initialized (Mario's
// init); Frame once per frame: applies the inbox the host filled and says where it is.
void VoxelPlanetCreate();
void VoxelPlanetFrame(uint32_t scene_id, uint32_t* inbox_addr, uint32_t* inbox_size);

// Counters for the dev harness (peek): inbox batches, records, chunks with something to draw,
// collision parts made, last chunk slot and version seen, chunks dropped for lack of memory, free
// bytes of the scene's MEM2 and MEM1 heaps (at the last batch), collision parts alive, chunks
// drawn last frame (the rest were behind the camera or the horizon), chunks left without
// collision because the stage's main collision zone was missing.
struct VoxelStats
{
  uint32_t batches, records, chunks, parts_made, last_slot, last_version, alloc_failed, free_mem2, free_mem1;
  uint32_t parts_live, drawn_last, no_zone;
};
extern VoxelStats gVoxelStats;
