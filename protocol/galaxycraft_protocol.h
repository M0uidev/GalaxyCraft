/*
 * GalaxyCraft shared-memory protocol, version 11.
 *
 * Source of truth for the layout of /dev/shm/galaxycraft_v1. Mirrors:
 *   tools/gxproto.py
 *   fabric/src/main/java/dev/moui/galaxycraft/proto/Layout.java
 * protocol/test_layout.c pins every offset.
 *
 * "S" (host) is Dolphin or tools/fake_galaxy.py; "M" is the Minecraft mod.
 * Everything is little-endian with fixed-size structs.
 */
#ifndef GALAXYCRAFT_PROTOCOL_H
#define GALAXYCRAFT_PROTOCOL_H

#include <stdint.h>

#define GXC_SHM_NAME "/galaxycraft_v1"
#define GXC_MAGIC 0x52435847u /* "GXCR" */
#define GXC_VERSION 11u

/* Regions (byte offsets from the start of the mapping). */
#define GXC_OFF_HEADER 0
#define GXC_OFF_WORLD 64
#define GXC_OFF_PLAYER 128
#define GXC_OFF_INPUT 224
#define GXC_OFF_GAMECAM 320
#define GXC_OFF_POINTER 448
#define GXC_OFF_TEXT 512
#define GXC_OFF_RING_S2M 4096
#define GXC_RING_S2M_CAP (4u * 1024u * 1024u)
#define GXC_OFF_RING_M2S (GXC_OFF_RING_S2M + 16 + GXC_RING_S2M_CAP)
#define GXC_RING_M2S_CAP (1024u * 1024u) /* planet chunks travel this way */
#define GXC_OFF_OVERLAY 5251072 /* end of ring M2S rounded up to 4096 */
#define GXC_OVERLAY_MAX_W 1920
#define GXC_OVERLAY_MAX_H 1080
#define GXC_OVERLAY_FRAME_BYTES (GXC_OVERLAY_MAX_W * GXC_OVERLAY_MAX_H * 4)
#define GXC_TOTAL_SIZE (GXC_OFF_OVERLAY + 32 + 3 * GXC_OVERLAY_FRAME_BYTES)

/* host_flags (header): SMG2 is in GalaxyCraftSpace, ready for a world (also in Minecraft's menus). */
#define GXC_HOST_SPACE_READY 4u

/* mod_flags: Minecraft is in a world (else its title, world list or another menu). */
#define GXC_MOD_IN_WORLD 2u
/* mod_flags: entering that world, its screen still over the game (the galaxy loads, Mario lands): silent. */
#define GXC_MOD_ENTERING 4u

/* Heartbeat older than this means the other side is gone. */
#define GXC_HEARTBEAT_TIMEOUT_MS 2000

typedef struct {
  uint32_t magic;
  uint32_t version;
  uint32_t host_pid;
  uint32_t mod_pid;
  uint64_t host_heartbeat_ms; /* CLOCK_MONOTONIC milliseconds */
  uint64_t mod_heartbeat_ms;
  uint32_t host_flags; /* bit0: game linked (gravity/collision are real) */
  uint32_t mod_flags;  /* bit0: mod drives the player; GXC_MOD_IN_WORLD */
  uint8_t reserved[24];
} GxcHeader;

/*
 * Seqlock slots: the writer increments seq to odd, writes the payload, then
 * increments seq to even. Readers retry while seq is odd or changed.
 */
typedef struct { /* S -> M */
  uint32_t seq;
  uint32_t scene_id;
  uint64_t frame_id;
  float gravity[3];   /* galaxy space, unit vector (0 = no gravity) */
  float query_pos[3]; /* galaxy position gravity was evaluated at */
  uint32_t flags; /* GXC_WORLD_* */
  uint32_t origin_epoch; /* the floating origin's epoch query_pos is in (GXC_MSG_ORIGIN) */
  uint8_t pad[16];
} GxcWorldState;

/*
 * Set until the host sees a fresh PlayerState (after start, a scene change or HELLO):
 * query_pos is then where the player is (e.g. Mario), and the mod anchors its frame there.
 */
#define GXC_WORLD_ANCHOR 1u
/* Minecraft mode: the player follows Mario (query_pos), SMG2 owns the movement. */
#define GXC_WORLD_FOLLOW 2u

#define GXC_PLAYER_ON_GROUND 1u
/* Something in the main hand: the clicks break and place blocks instead of spinning (B). */
#define GXC_PLAYER_ITEM_ACTIVE 2u
/* A Minecraft screen is open (chat, inventory...): the keyboard is Minecraft's, not Mario's. */
#define GXC_PLAYER_SCREEN 4u
/* /fly: the player flies on its own, up is the galaxy's +Y; Mario stays put (and is drawn). */
#define GXC_PLAYER_FLYING 8u
/* F3+B: Mario's collision (radius, ground probes, binder) is drawn over everything. */
#define GXC_PLAYER_HITBOXES 16u
/* Minecraft movement: the player walks by Minecraft's own physics and Mario goes with it (hidden,
   GXC_MSG_SEAT every frame); the keyboard is not Mario's. Steve is drawn as an entity. */
#define GXC_PLAYER_WALKING 32u
/* Hold SMG2's + button (its own pause menu): Escape opens Minecraft's pause menu instead. */
#define GXC_PLAYER_PLUS 64u
/* Minecraft's feel on Mario: SMG2 moves him (its own collision), at Minecraft's walk, sprint
   (Ctrl) and sneak (Shift) speeds, with Minecraft's 1.25-block jump and none of Mario's moves. */
#define GXC_PLAYER_MC_FEEL 128u

typedef struct { /* M -> S */
  uint32_t seq;
  uint32_t flags;
  uint64_t frame_id;
  float pos[3];  /* galaxy units, feet position */
  float look[3]; /* galaxy space unit vector */
  float up[3];   /* galaxy space unit vector */
  float fov_y;   /* degrees */
  float eye_height; /* galaxy units above pos */
  float cam_offset[3]; /* camera minus pos, galaxy units (FIRST/BACK/FRONT) */
  uint32_t view;       /* GXC_VIEW_*: look/up above are the camera's */
  uint32_t scene_id;   /* WorldState.scene_id the mod's frame is anchored in */
  uint8_t pad[16];
} GxcPlayerState;

/* Perspective chosen with F5 (PlayerState.view). */
#define GXC_VIEW_FIRST 0u
#define GXC_VIEW_BACK 1u
#define GXC_VIEW_FRONT 2u
#define GXC_VIEW_GALAXY 3u /* SMG2's own camera; Minecraft follows it */

/* S -> M: SMG2's camera and Mario, from the same game frame (Galaxy view). */
typedef struct {
  uint32_t seq;
  uint32_t flags; /* GXC_GAMECAM_* */
  uint64_t frame_id;
  float cam_pos[3];
  float cam_dir[3]; /* unit, where the camera looks */
  float cam_up[3];
  float fov_y;      /* degrees */
  float mario_pos[3];
  float mario_front[3];
  uint32_t origin_epoch; /* the floating origin's epoch cam_pos and mario_pos are in */
  uint8_t pad[12];
} GxcGameCamera;

#define GXC_GAMECAM_VALID 1u
#define GXC_GAMECAM_DEMO 2u /* a cutscene shows Mario: Steve is not drawn */

typedef struct { /* S -> M */
  uint32_t seq;
  uint32_t buttons; /* bit n = SDL mouse button n (1 left, 2 middle, 3 right) */
  double mouse_x;   /* accumulated deltas since start */
  double mouse_y;
  double wheel;
  uint8_t keys[64]; /* bitmap indexed by SDL scancode (USB HID usage), as Minecraft 26.x uses */
} GxcInputState;

/*
 * Where the mouse pointer is over the host's render window (S -> M), while it is not captured (a
 * Minecraft screen is open): x and y from 0 at the left and top to 1 at the right and bottom. The
 * overlay covers that whole window, so it maps onto Minecraft's window as is.
 */
#define GXC_POINTER_INSIDE 1u
#define GXC_POINTER_BACKGROUND 2u /* another window is in front of the host's (Alt+Tab): Minecraft
                                     counts as unfocused and pauses */
typedef struct {
  uint32_t seq;
  uint32_t flags; /* GXC_POINTER_* */
  float x;
  float y;
} GxcPointerState;

/*
 * Text typed in the host's window (S -> M), as characters after the keyboard layout: what GLFW's
 * char callback gives, for Minecraft's text fields. count only grows; character i is
 * codepoints[i % GXC_TEXT_RING]. A reader more than GXC_TEXT_RING behind lost the oldest ones.
 */
#define GXC_TEXT_RING 64
typedef struct {
  uint32_t seq;
  uint32_t count;
  uint32_t codepoints[GXC_TEXT_RING];
} GxcTextState;

/*
 * SPSC ring: head and tail are monotonically increasing byte counters
 * (mod 2^32); the data area follows the header. Messages are 8-byte aligned.
 * A message that does not fit before the end is preceded by a PAD message
 * filling the rest; if fewer than 8 bytes remain, both sides skip them.
 */
typedef struct {
  uint32_t head; /* written by producer */
  uint32_t tail; /* written by consumer */
  uint32_t capacity;
  uint32_t pad;
} GxcRingHeader;

typedef struct {
  uint16_t type;
  uint16_t reserved;
  uint32_t length; /* payload bytes, excluding this header and alignment */
} GxcMsgHeader;

enum {
  GXC_MSG_SCENE_CHANGE = 1, /* uint32_t scene_id, then char stage_name[32] (NUL-padded) */
  GXC_MSG_PART_UPSERT = 2,  /* GxcPartUpsert */
  GXC_MSG_PART_REMOVE = 3,  /* uint32_t part_id */
  GXC_MSG_KCL_CHUNK = 4,    /* GxcKclChunk + data */
  GXC_MSG_HELLO = 101,      /* uint32_t mod_version */
  /* Voxel planet, forwarded by the host to the module's inbox (GxcMailbox.inbox_addr). */
  GXC_MSG_PLANET = 102,    /* GxcPlanet */
  GXC_MSG_CHUNK = 103,     /* GxcChunk + display list + KCL (both big-endian already) */
  GXC_MSG_PLANET_TP = 104, /* f32 big-endian: Mario onto the ground at this radius (none: the surface),
                              then u32 big-endian: the planet's id (none: the first one) */
  GXC_MSG_OUTLINE = 105,   /* GxcOutline: the block the player can act on */
  GXC_MSG_HELD = 106,      /* GxcHeld: what the player holds in a hand, drawn in Steve's */
  GXC_MSG_ATLAS = 107,     /* GxcAtlas + data: a piece of the block atlas */
  GXC_MSG_SKIN = 108,      /* entity texture, all big-endian (see GXC_ENT_*) */
  GXC_MSG_MODEL = 109,     /* entity model display list, all big-endian */
  GXC_MSG_ENTITIES = 110,  /* this frame's entities, all big-endian; only the newest one counts */
  GXC_MSG_SEAT = 112,      /* Mario rides something: f32 pos[3] (galaxy), u32 riding (0: he gets off); big-endian,
                              sent every frame while he rides (the game lets go if they stop) */
  GXC_MSG_HURT = 111,      /* the player was hurt in Minecraft: f32 from[3] (galaxy), u32 GXC_HURT_*; big-endian */
  GXC_MSG_SKY = 113,       /* the sky's light now (Minecraft's lightmap at full sky light, day or night): f32 rgb[3],
                              big-endian; planets' sky-lit faces take it */
  GXC_MSG_MARIO_SKIN = 114, /* M -> S, for the host itself: the skin of Mario's model (Steve), all big-endian:
                               u32 id (unused), u32 width, u32 height (64 and 64), GX RGB5A3 texels. The host
                               writes them over the "steve" texture of Mario.bdl wherever it is in guest RAM */
  GXC_MSG_CRACK = 115,     /* GxcCrack: the block being broken, cracked as far as it has been */
  GXC_MSG_ORIGIN = 116,    /* the floating origin moves: u32 epoch, i32 shift[3] (cells of GXC_ORIGIN_CELL units),
                              big-endian. Everything in the game moves by -shift * GXC_ORIGIN_CELL at once (planets,
                              their gravity and collision, Mario); records after it are in the new epoch, which
                              the game echoes in GxcMailbox.origin_epoch */
  GXC_MSG_STARS = 117,     /* the other solar systems, drawn as points of light on the sky around the camera:
                              u32 count (<= GXC_STARS_MAX), then count x {f32 dir[3] (unit, galaxy space),
                              f32 size (pixels), u32 rgba}; big-endian. Replaces the last one */
  GXC_MSG_PAD = 0xFFFF,
};

typedef struct {
  uint32_t part_id;
  uint32_t kcl_size; /* bytes; KCL chunks follow unless already sent */
  float mtx[12];     /* 3x4 row-major, part local -> galaxy */
} GxcPartUpsert;

#define GXC_KCL_CHUNK_MAX 65536

typedef struct {
  uint32_t part_id;
  uint32_t offset;
  uint32_t total;
  /* uint8_t data[]; up to GXC_KCL_CHUNK_MAX */
} GxcKclChunk;

/*
 * Several planets per scene (8 at most), each by its id (1..255). A GxcPlanet may be followed by a
 * big-endian u32 of GXC_PLANET_* flags (the host passes it on as is). chunk_count 0: only its far
 * view, no chunks.
 */
#define GXC_PLANET_GONE 1u /* this planet (planet_id) leaves the scene */
/* A station: after the flags, big-endian f32 up[3], forward[3], half[3] (its gravity box's half
 * extents along right = up x forward, up, forward) and box_center[3] (from center), galaxy units:
 * a box gravity pulls toward -up inside that box. */
#define GXC_PLANET_FLAT 2u
typedef struct {
  uint32_t planet_id; /* 0: no planet (the module drops them all) */
  float center[3];    /* galaxy units */
  float surface;      /* radius of the surface, galaxy units */
  float gravity_range; /* radius of its point gravity, galaxy units */
  uint32_t chunk_count; /* slots: GxcChunk.slot < chunk_count <= GXC_PLANET_MAX_CHUNKS */
  float occluder;       /* radius of the opaque ball under the crust (bedrock), galaxy units */
  float mario_radius;   /* Mario's collision sphere while in its gravity (galaxy units), 0 his own */
} GxcPlanet;

/*
 * Positions in the display list and the KCL are relative to the planet's center. A chunk far from
 * Mario comes without KCL (drawn only): the game's collision zones hold 512 parts at most.
 */
/*
 * slot: the planet's id in the top byte; with GXC_CHUNK_FAR_VIEW a part of its far view (a tile:
 * up to 16 x 16 per face of its cube, GXC_FAR_VIEW_PARTS in all, drawn where Mario's render
 * distance does not reach instead of its chunks, and where it does only from afar); positions in whole
 * units from the planet's center, GX vertex format 5, no KCL) and the tile below; else the
 * chunk's index.
 */
#define GXC_CHUNK_FAR_VIEW 0x800000u
/* With GXC_CHUNK_FAR_VIEW: the tile is chunks in the game. The part is drawn only for a camera far
 * from the planet (instead of its chunks); without a display list, the last one is kept. */
#define GXC_CHUNK_FAR_COVERED 0x400000u
/* A chunk (not a far view part) whose display list is two: its opaque faces, then from the offset
 * in a uint32_t after GxcChunk its translucent ones (water), drawn after every opaque one, blended. */
#define GXC_CHUNK_TRANSLUCENT 0x200000u
#define GXC_FAR_VIEW_PARTS (6 * 16 * 16)
typedef struct {
  uint32_t slot;    /* planet id << 24 | chunk index (or GXC_CHUNK_FAR_VIEW | tile) */
  uint32_t version; /* newer replaces older */
  uint32_t dl_size; /* bytes, multiple of 32; 0: the chunk is empty (no KCL either) */
  uint32_t kcl_size; /* 0: no collision */
  float sphere[4];  /* bounding sphere: center relative to the planet's, radius (galaxy units) */
} GxcChunk;

#define GXC_PLANET_MAX_CHUNKS 131072

/* The floating origin moves by whole cells of this many units (2^16): a float moved by them toward
   0 stays exactly the same point, so nothing jumps. */
#define GXC_ORIGIN_CELL 65536
#define GXC_STARS_MAX 4096
#define GXC_STAR_BYTES 20

/* Minecraft's block outline around what the player points at (and can break, use, place against
 * or scoop): the edges of the block's shape as Minecraft draws them (no line across a face), each
 * from one end to the other, relative to the planet's center, galaxy units. Sent with only its
 * count of edges: 8 + 24 * count bytes. */
#define GXC_OUTLINE_MAX_EDGES 96
typedef struct {
  uint32_t visible; /* the planet's id: edges from its center; 0: none (and no edges) */
  uint32_t count;   /* edges that follow, up to GXC_OUTLINE_MAX_EDGES */
  float edges[GXC_OUTLINE_MAX_EDGES][2][3];
} GxcOutline;

/* Minecraft's cracks over the block the player is breaking: the atlas tile of destroy_stage_<stage>
 * on the six sides of the box the corners make (in GxcOutline's order), multiplied into what is
 * drawn under it as Minecraft does (twice texture times screen: mid gray changes nothing). Corner m
 * is (di, dj, dk) = (m & 1, m >> 1 & 1, m >> 2) of the cell, relative to the planet's center. Sent
 * when the stage or the block changes. */
#define GXC_CRACK_STAGES 10
typedef struct {
  uint32_t visible; /* the planet's id: corners from its center; 0: none */
  uint32_t stage;   /* 0 .. GXC_CRACK_STAGES - 1 */
  float uv[4];      /* the stage's tile in the block atlas: u0, v0, u1, v1 (0 to 1) */
  float corners[8][3]; /* galaxy units */
} GxcCrack;

/* What the player holds in the main hand, drawn by the game in Steve's right hand as Minecraft
 * draws it in third person. Sent when it changes and again in every new scene. */
#define GXC_HELD_NONE 0u
#define GXC_HELD_BLOCK 1u /* a cube: the sprite's first band on top, the second on the sides, the third below */
#define GXC_HELD_CUBE 2u  /* a cube with the sprite's first band on every face */
#define GXC_HELD_ITEM 3u  /* the first band as a flat item one texel thick (Minecraft's item/generated) */
#define GXC_HELD_TOOL 4u  /* the same, held as a tool (item/handheld) */
#define GXC_HELD_SPRITE 16 /* texels a side of a band */
#define GXC_HELD_BANDS 4   /* the sprite is 16 wide and 4 bands of 16 tall (the last one unused) */
#define GXC_HELD_POSE_NONE 0u  /* the arm as Mario's animation has it */
#define GXC_HELD_POSE_BLOCK 1u /* raised to block with a shield (Minecraft's ArmPose.BLOCK) */
typedef struct {
  uint32_t kind;     /* GXC_HELD_* */
  uint32_t hand;     /* 0: the right (main) hand; 1: the left (off) hand */
  uint32_t pose;     /* GXC_HELD_POSE_*: how that arm is held */
  uint32_t reserved;
  /* GX RGB5A3, 16x64 texels (4x4 texel blocks, big-endian already); alpha 0 is a hole */
  uint8_t sprite[GXC_HELD_SPRITE * GXC_HELD_SPRITE * GXC_HELD_BANDS * 2];
} GxcHeld;

/* A piece of the block atlas the planets are drawn with (GX RGB5A3 with its mipmaps, big-endian
 * already: offset and data index into that). The module puts the pieces together and draws once
 * all total bytes of one atlas id are in. Sent again in every new scene. */
typedef struct {
  uint32_t atlas_id; /* a new id starts a new atlas */
  uint32_t width;    /* texels, level 0, a power of two up to 1024 */
  uint32_t height;
  uint32_t levels;   /* mipmap levels, the first included */
  uint32_t total;    /* bytes of all levels */
  uint32_t offset;   /* of this piece's data */
  /* uint8_t data[]; */
} GxcAtlas;

#define GXC_ATLAS_PIECE_MAX 65536

/* Entities on a planet (dropped items, mobs, primed TNT, falling blocks), drawn by the game.
 * Minecraft's models are cut into rigid pieces (a mob's head, body, legs...), each sent once per
 * scene as a model; textures once as skins; then every frame only where each piece is. The mod
 * writes these three messages big-endian already: the host swaps nothing.
 *   SKIN:     u32 id, u32 width, u32 height (multiples of 4, at most 256), GX RGB5A3 texels
 *   MODEL:    u32 id, u32 dl_size (a multiple of 32), display list: GX_QUADS in GXC_ENT_VTXFMT,
 *             position s16 xyz (1/16 of a model pixel), color RGBA8, texcoord s16 st (1/4096)
 *   ENTITIES: u32 count, then count x { u16 model (bit 15: faces the camera, a particle; bit 14: held in Steve's hand,
 *             mtx from Minecraft's hand frame, bit 13: the left one), u16 skin, u8 overlay[4] (RGBA: the color
 *             mixed over the piece by A/255, red when hurt, white when TNT flashes), u8 tint[4] (RGBA
 *             the piece is multiplied by: dyed wool and leather, tinted leaves),
 *             f32 mtx[12] (3x4 row-major, model pixels -> galaxy) } */
#define GXC_ENT_MAX_SKINS 256
#define GXC_ENT_MAX_MODELS 2048
#define GXC_ENT_MAX 1536
#define GXC_ENT_BYTES 60
#define GXC_ENT_SKIN_MAX 256
#define GXC_ENT_DL_MAX 65536
#define GXC_ENT_VTXFMT 3
#define GXC_HURT_HIT 0u       /* a blow, an arrow */
#define GXC_HURT_FIRE 1u      /* fire, lava */
#define GXC_HURT_EXPLOSION 2u /* TNT, a creeper */

typedef struct {
  uint32_t latest; /* index 0..2 of the newest complete frame, 0xFFFFFFFF none */
  uint32_t width;
  uint32_t height;
  uint32_t frame_id[3];
  uint32_t reserved[2];
} GxcOverlayHeader;

/*
 * Guest mailbox: lives in emulated Wii RAM, written by the Syati module inside SMG2 and
 * synced with the shared memory by Dolphin once per video field. BIG-ENDIAN (PowerPC).
 * Dolphin finds it by scanning MEM1/MEM2 for the magic.
 */
#define GXC_MBX_MAGIC "GXCRMBX1"
#define GXC_MBX_VERSION 6u
#define GXC_MBX_MAX_PARTS 64
#define GXC_MBX_FOLLOW 2u /* host_flags: Minecraft mode, the camera sits in Mario's eyes */
#define GXC_MBX_GALAXY_VIEW 4u /* host_flags: keep the game's camera */
#define GXC_MBX_THIRD_PERSON 8u /* host_flags: not first person, the model (Steve) is drawn */
#define GXC_MBX_HIDE_POINTER 16u /* host_flags: playing in Minecraft's view, the IR sits under its
                                   crosshair: the star pointer is not drawn */
#define GXC_MBX_HITBOXES 32u /* host_flags: draw Mario's collision (GXC_PLAYER_HITBOXES) */
#define GXC_MBX_MC_FEEL 64u /* host_flags: Minecraft's speeds and jump on Mario (GXC_PLAYER_MC_FEEL) */
#define GXC_MBX_MC_SPRINT 128u /* host_flags, with GXC_MBX_MC_FEEL: Ctrl held, Minecraft's sprint */
#define GXC_MBX_MC_SNEAK 256u  /* host_flags, with GXC_MBX_MC_FEEL: Shift held, Minecraft's sneak */
#define GXC_MBX_MC_WALK 512u   /* host_flags, with GXC_MBX_MC_FEEL: a movement key is held (WASD) */
#define GXC_MBX_BOOT_SPACE 1024u /* host_flags: the game boots by itself into GalaxyCraftSpace */
#define GXC_MBX_HOLD 2048u /* host_flags: Minecraft is in its menus, Mario waits at the origin */
#define GXC_SPACE_STAGE "GalaxyCraftSpace" /* GalaxyCraft's own galaxy (tools/space_galaxy.py) */
#define GXC_MBX_GAME_FOLLOWING 1u /* game_flags: Mario hidden, first-person camera this frame */
#define GXC_MBX_GAME_DEMO 2u   /* game_flags: a cutscene owns Mario and the camera */

typedef struct {
  uint32_t part_id;
  uint32_t kcl_addr; /* effective address of the KCL data in guest RAM */
  uint32_t kcl_size;
  float mtx[12]; /* 3x4 row-major, part local -> galaxy */
} GxcMbxPart;

typedef struct {
  char magic[8];
  uint32_t version;
  uint32_t game_seq; /* game: +1 per frame */
  uint32_t host_seq; /* host: +1 per write */
  uint32_t scene_id;
  float gravity[3];    /* game: gravity at Mario */
  float anchor_pos[3]; /* game: Mario's position */
  uint32_t game_flags; /* GXC_MBX_GAME_* */
  uint32_t host_flags; /* GXC_MBX_FOLLOW */
  float player_pos[3]; /* host: the mod's feet position (echo), galaxy units */
  float look[3];
  float up[3];
  float fov_y;
  float eye_height;
  float cam_offset[3]; /* host: camera minus Mario's feet (FOLLOW without GALAXY_VIEW) */
  float cam_pos[3];    /* game: its camera this frame */
  float cam_dir[3];
  float cam_up[3];
  float cam_fov;
  float mario_front[3]; /* game: where Mario faces */
  uint32_t part_count; /* <= GXC_MBX_MAX_PARTS */
  GxcMbxPart parts[GXC_MBX_MAX_PARTS];
  uint32_t inbox_addr; /* game: GxcInbox for the voxel planet, 0 none */
  uint32_t inbox_size; /* game: bytes, header included */
  char stage_name[32]; /* game: the stage (galaxy) loaded, NUL-padded */
  uint32_t origin_epoch; /* game: the floating origin's epoch anchor_pos and cam_pos are in (GXC_MSG_ORIGIN) */
} GxcMailbox;

/*
 * Inbox in guest RAM (big-endian). The host fills it only while state is 0: records first, then
 * count and bytes, then state 1. The module applies every record and sets state back to 0.
 * Record: GxcMsgHeader (type GXC_MSG_PLANET/CHUNK/PLANET_TP, length) + payload, padded to 4.
 */
typedef struct {
  uint32_t state;
  uint32_t count;
  uint32_t bytes; /* of records after this header */
  uint32_t scene_id; /* the scene the records were meant for */
} GxcInboxHeader;

#endif
