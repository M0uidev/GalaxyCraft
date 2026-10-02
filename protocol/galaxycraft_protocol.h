/*
 * GalaxyCraft shared-memory protocol, version 3.
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
#define GXC_VERSION 3u

/* Regions (byte offsets from the start of the mapping). */
#define GXC_OFF_HEADER 0
#define GXC_OFF_WORLD 64
#define GXC_OFF_PLAYER 128
#define GXC_OFF_INPUT 224
#define GXC_OFF_GAMECAM 320
#define GXC_OFF_RING_S2M 4096
#define GXC_RING_S2M_CAP (4u * 1024u * 1024u)
#define GXC_OFF_RING_M2S (GXC_OFF_RING_S2M + 16 + GXC_RING_S2M_CAP)
#define GXC_RING_M2S_CAP (64u * 1024u)
#define GXC_OFF_OVERLAY 4268032 /* end of ring M2S rounded up to 4096 */
#define GXC_OVERLAY_MAX_W 1920
#define GXC_OVERLAY_MAX_H 1080
#define GXC_OVERLAY_FRAME_BYTES (GXC_OVERLAY_MAX_W * GXC_OVERLAY_MAX_H * 4)
#define GXC_TOTAL_SIZE (GXC_OFF_OVERLAY + 32 + 3 * GXC_OVERLAY_FRAME_BYTES)

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
  uint32_t mod_flags;  /* bit0: mod drives the player */
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
  uint8_t pad[20];
} GxcWorldState;

/*
 * Set until the host sees a fresh PlayerState (after start, a scene change or HELLO):
 * query_pos is then where the player is (e.g. Mario), and the mod anchors its frame there.
 */
#define GXC_WORLD_ANCHOR 1u
/* Minecraft mode: the player follows Mario (query_pos), SMG2 owns the movement. */
#define GXC_WORLD_FOLLOW 2u

#define GXC_PLAYER_ON_GROUND 1u

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
  uint8_t pad[16];
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
  GXC_MSG_SCENE_CHANGE = 1, /* uint32_t scene_id */
  GXC_MSG_PART_UPSERT = 2,  /* GxcPartUpsert */
  GXC_MSG_PART_REMOVE = 3,  /* uint32_t part_id */
  GXC_MSG_KCL_CHUNK = 4,    /* GxcKclChunk + data */
  GXC_MSG_HELLO = 101,      /* uint32_t mod_version */
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
#define GXC_MBX_VERSION 2u
#define GXC_MBX_MAX_PARTS 64
#define GXC_MBX_FOLLOW 2u /* host_flags: Minecraft mode, the camera sits in Mario's eyes */
#define GXC_MBX_GALAXY_VIEW 4u /* host_flags: keep the game's camera */
#define GXC_MBX_THIRD_PERSON 8u /* host_flags: not first person, the model (Steve) is drawn */
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
} GxcMailbox;

#endif
