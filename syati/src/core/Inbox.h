#pragma once
#include "gxc_types.h"

namespace gxc
{
// Records of the voxel planet's inbox (GxcInboxHeader in protocol/galaxycraft_protocol.h), read
// from big-endian bytes so the same code runs in the game and under g++.
struct InboxPlanet
{
  u32 id;
  f32 center[3];
  f32 surface;
  f32 gravity_range;
};

struct InboxChunk
{
  u32 slot, version, dl_size, kcl_size;
  const u8* dl;   // dl_size bytes
  const u8* kcl;  // kcl_size bytes
};

struct InboxRecord
{
  enum Type
  {
    PLANET = 102,
    CHUNK = 103,
    TELEPORT = 104,
  };
  u32 type;
  InboxPlanet planet;
  InboxChunk chunk;
};

u32 ReadBE32(const u8* p);

// Reads the record at records + *offset (of bytes total) and advances *offset past it.
// False at the end or on a malformed record (then nothing after it can be trusted either).
bool NextInboxRecord(const u8* records, u32 bytes, u32* offset, u32 max_slots, InboxRecord* out);

// Where Mario lands on a planet: above the surface on the line from the center through him
// (straight up if he is at the center).
void PlanetDrop(const f32 center[3], f32 surface, f32 above, const f32 mario[3], f32 out[3]);

// view (3x4 row-major) times a translation by t: the position matrix of something placed at t.
void ViewTranslate(const f32 view[12], const f32 t[3], f32 out[12]);
}  // namespace gxc
