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
  u32 chunk_count;
  f32 occluder;
  f32 mario_radius;  // 0: Mario keeps his own collision sphere
};

struct InboxChunk
{
  u32 slot, version, dl_size, kcl_size;
  f32 sphere[4];  // bounding sphere, center relative to the planet's
  const u8* dl;   // dl_size bytes
  const u8* kcl;  // kcl_size bytes (0: drawn only)
};

struct InboxOutline
{
  u32 visible;
  f32 corners[8][3];  // relative to the planet's center
};

struct InboxHeld
{
  u32 kind;  // GXC_HELD_*
  u32 tiles[3];  // BLOCK: atlas tiles of the top, the sides and the bottom
  const u8* sprite;  // CUBE, ITEM, TOOL: 16x16 GX RGB5A3 (512 bytes)
};

struct InboxRecord
{
  enum Type
  {
    PLANET = 102,
    CHUNK = 103,
    TELEPORT = 104,
    OUTLINE = 105,
    HELD = 106,
  };
  u32 type;
  InboxPlanet planet;
  InboxChunk chunk;
  InboxOutline outline;
  InboxHeld held;
};

u32 ReadBE32(const u8* p);

// Reads the record at records + *offset (of bytes total) and advances *offset past it.
// False at the end or on a malformed record (then nothing after it can be trusted either).
bool NextInboxRecord(const u8* records, u32 bytes, u32* offset, u32 max_slots, InboxRecord* out);

// Where Mario lands on a planet: above the surface on the line from the center through him
// (straight up if he is at the center).
void PlanetDrop(const f32 center[3], f32 surface, f32 above, const f32 mario[3], f32 out[3]);

// The camera of a view matrix (3x4 row-major, world -> view): its position and the unit direction
// it looks along (-z).
void ViewEye(const f32 view[12], f32 eye[3], f32 fwd[3]);

// view (3x4 row-major) times a translation by t: the position matrix of something placed at t.
void ViewTranslate(const f32 view[12], const f32 t[3], f32 out[12]);

// Whether a sphere (c, r) cannot be seen from cam: wholly behind the camera (fwd: unit view
// direction), or in the shadow of the opaque ball (center, occluder radius) seen from cam.
bool SphereHidden(const f32 cam[3], const f32 fwd[3], const f32 center[3], f32 occluder, const f32 c[3], f32 r);
}  // namespace gxc
