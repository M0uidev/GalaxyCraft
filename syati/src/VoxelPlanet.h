#pragma once
#include <stdint.h>

// Voxel planet (VoxelPlanet.cpp). Create once per scene, while actors are initialized (Mario's
// init); Frame once per frame: applies the inbox the host filled and says where it is.
void VoxelPlanetCreate();
void VoxelPlanetFrame(uint32_t scene_id, uint32_t* inbox_addr, uint32_t* inbox_size);

// Counters for the dev harness (peek): inbox batches, records, chunks drawn, collision parts made,
// last chunk slot and version seen.
struct VoxelStats
{
  uint32_t batches, records, chunks, parts_made, last_slot, last_version;
};
extern VoxelStats gVoxelStats;
