/*
 * GalaxyCraft shared-memory protocol, version 1.
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
#define GXC_VERSION 1u

/* Regions (byte offsets from the start of the mapping). */
#define GXC_OFF_HEADER 0
#define GXC_OFF_WORLD 64
#define GXC_OFF_PLAYER 128
#define GXC_OFF_INPUT 224
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
  uint32_t flags; /* GXC_WORLD_ANCHOR */
  uint8_t pad[20];
} GxcWorldState;

/*
 * Set until the host sees a fresh PlayerState (after start, a scene change or HELLO):
 * query_pos is then where the player is (e.g. Mario), and the mod anchors its frame there.
 */
#define GXC_WORLD_ANCHOR 1u

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
  uint8_t pad[36];
} GxcPlayerState;

typedef struct { /* S -> M */
  uint32_t seq;
  uint32_t buttons; /* mouse buttons bitmask */
  double mouse_x;   /* accumulated deltas since start */
  double mouse_y;
  double wheel;
  uint8_t keys[64]; /* bitmap indexed by GLFW key code */
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

#endif
