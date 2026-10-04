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
  u32 flags;         // PLANET_GONE: this planet (id) leaves; chunk_count 0: only its far view
};

const u32 PLANET_GONE = 1;
// A chunk record's slot word: the planet's id in the top byte, FAR_VIEW for a part of its far view
// (the slot below is then its tile: face, then up to 16 × 16 tiles of it, PlanetLod.tile, and
// FAR_COVERED if the tile is chunks in the game), else the chunk's slot.
const u32 CHUNK_FAR_VIEW = 0x800000u, CHUNK_FAR_COVERED = 0x400000u, CHUNK_SLOT_MASK = 0x7FFFFFu,
          CHUNK_TRANSLUCENT = 0x200000u,
          FAR_VIEW_PARTS = 6 * 16 * 16;

struct InboxChunk
{
  u32 planet;  // the planet's id (0: whichever there is, as before several were)
  bool far;    // a part of the planet's far view: slot is its tile
  bool covered;  // and its tile is chunks: drawn only from afar (no display list: keep the last one)
  u32 slot, version, dl_size, kcl_size;
  f32 sphere[4];  // bounding sphere, center relative to the planet's
  u32 solid_size;  // bytes of dl drawn opaque; the rest is translucent (drawn after, blended)
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
  const u8* sprite;  // 16x64 GX RGB5A3 (2048 bytes): bands top, sides, bottom (HeldMesh)
};

// A piece of the block atlas (GxcAtlas): data is size bytes at offset of the atlas' total.
struct InboxAtlas
{
  u32 id, width, height, levels, total, offset, size;
  const u8* data;
};

// Entities (GXC_MSG_SKIN, MODEL, ENTITIES and GXC_ENT_* in protocol/galaxycraft_protocol.h).
const u32 ENT_MAX_SKINS = 256, ENT_MAX_MODELS = 2048, ENT_MAX = 768, ENT_BYTES = 60;
const u32 ENT_SKIN_MAX = 256, ENT_DL_MAX = 65536;

struct InboxSkin
{
  u32 id, width, height;
  const u8* data;  // width x height GX RGB5A3
};

struct InboxModel
{
  u32 id, dl_size;
  const u8* dl;
};

// The player was hurt (GXC_MSG_HURT): from where, and how (GXC_HURT_*).
struct InboxHurt
{
  f32 from[3];
  u32 kind;
};

// Mario rides something (GXC_MSG_SEAT): where he sits, or riding 0 when he gets off.
struct InboxSeat
{
  f32 pos[3];
  u32 riding;
};

// Mario onto the planet (GXC_MSG_PLANET_TP): the ground's radius where he lands, 0 for the surface.
struct InboxTeleport
{
  f32 ground;
  u32 planet;  // 0: the first one
};

struct InboxEntities
{
  u32 count;
  const u8* list;  // count x GXC_ENT_BYTES
};

// Bytes of a GX RGB5A3 texture of width x height texels with its mipmaps (each level half the
// last, down to levels of them), as the mod lays them out.
u32 AtlasBytes(u32 width, u32 height, u32 levels);

struct InboxRecord
{
  enum Type
  {
    PLANET = 102,
    CHUNK = 103,
    TELEPORT = 104,
    OUTLINE = 105,
    HELD = 106,
    ATLAS = 107,
    SKIN = 108,
    MODEL = 109,
    ENTITIES = 110,
    HURT = 111,
    SEAT = 112,
  };
  u32 type;
  InboxPlanet planet;
  InboxChunk chunk;
  InboxOutline outline;
  InboxHeld held;
  InboxAtlas atlas;
  InboxSkin skin;
  InboxModel model;
  InboxEntities entities;
  InboxHurt hurt;
  InboxSeat seat;
  InboxTeleport teleport;
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

// Whether a sphere of radius r centered at v (view space: the camera at the origin looking down
// -z) lies wholly beside the view: past its left, right, top or bottom side. proj is what
// GXGetProjectionv gives (type, then m00 m02 m11 m12 m22 m23); an orthographic one hides nothing.
bool SphereOutsideView(const f32 proj[7], const f32 v[3], f32 r);
}  // namespace gxc
