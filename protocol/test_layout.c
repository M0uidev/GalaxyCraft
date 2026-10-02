/* Layout test: every offset here is mirrored in tools/gxproto.py and fabric proto/Layout.java. */
#include <stddef.h>
#include <stdio.h>
#include "galaxycraft_protocol.h"

_Static_assert(sizeof(GxcHeader) == 64, "header");
_Static_assert(offsetof(GxcHeader, host_heartbeat_ms) == 16, "hb");
_Static_assert(offsetof(GxcHeader, host_flags) == 32, "flags");

_Static_assert(sizeof(GxcWorldState) == 64, "world");
_Static_assert(offsetof(GxcWorldState, frame_id) == 8, "world.frame");
_Static_assert(offsetof(GxcWorldState, gravity) == 16, "world.gravity");
_Static_assert(offsetof(GxcWorldState, query_pos) == 28, "world.query");
_Static_assert(offsetof(GxcWorldState, flags) == 40, "world.flags");

_Static_assert(sizeof(GxcPlayerState) == 96, "player");
_Static_assert(offsetof(GxcPlayerState, pos) == 16, "player.pos");
_Static_assert(offsetof(GxcPlayerState, look) == 28, "player.look");
_Static_assert(offsetof(GxcPlayerState, up) == 40, "player.up");
_Static_assert(offsetof(GxcPlayerState, fov_y) == 52, "player.fov");
_Static_assert(offsetof(GxcPlayerState, eye_height) == 56, "player.eye");

_Static_assert(sizeof(GxcInputState) == 96, "input");
_Static_assert(offsetof(GxcInputState, mouse_x) == 8, "input.mx");
_Static_assert(offsetof(GxcInputState, keys) == 32, "input.keys");

_Static_assert(sizeof(GxcRingHeader) == 16, "ring");
_Static_assert(sizeof(GxcMsgHeader) == 8, "msg");
_Static_assert(sizeof(GxcPartUpsert) == 56, "part");
_Static_assert(sizeof(GxcKclChunk) == 12, "chunk");
_Static_assert(sizeof(GxcOverlayHeader) == 32, "overlay");

_Static_assert(GXC_OFF_WORLD == 64, "off world");
_Static_assert(GXC_OFF_PLAYER == 128, "off player");
_Static_assert(GXC_OFF_INPUT == 224, "off input");
_Static_assert(GXC_OFF_RING_S2M == 4096, "off s2m");
_Static_assert(GXC_OFF_RING_M2S == 4198416, "off m2s");
_Static_assert(GXC_OFF_OVERLAY == 4268032, "off overlay");
_Static_assert(GXC_TOTAL_SIZE == 29151264, "total");

int main(void) { puts("OK"); return 0; }
