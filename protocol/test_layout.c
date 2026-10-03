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
_Static_assert(offsetof(GxcPlayerState, cam_offset) == 60, "player.cam_offset");
_Static_assert(offsetof(GxcPlayerState, view) == 72, "player.view");
_Static_assert(offsetof(GxcPlayerState, scene_id) == 76, "player.scene");

_Static_assert(sizeof(GxcGameCamera) == 96, "gamecam");
_Static_assert(offsetof(GxcGameCamera, frame_id) == 8, "gamecam.frame");
_Static_assert(offsetof(GxcGameCamera, cam_pos) == 16, "gamecam.pos");
_Static_assert(offsetof(GxcGameCamera, cam_dir) == 28, "gamecam.dir");
_Static_assert(offsetof(GxcGameCamera, cam_up) == 40, "gamecam.up");
_Static_assert(offsetof(GxcGameCamera, fov_y) == 52, "gamecam.fov");
_Static_assert(offsetof(GxcGameCamera, mario_pos) == 56, "gamecam.mario");
_Static_assert(offsetof(GxcGameCamera, mario_front) == 68, "gamecam.front");

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
_Static_assert(GXC_OFF_GAMECAM == 320 && GXC_OFF_GAMECAM + sizeof(GxcGameCamera) <= GXC_OFF_RING_S2M, "off gamecam");
_Static_assert(GXC_OFF_RING_S2M == 4096, "off s2m");
_Static_assert(GXC_OFF_RING_M2S == 4198416, "off m2s");
_Static_assert(GXC_OFF_OVERLAY == 5251072 && GXC_OFF_OVERLAY >= GXC_OFF_RING_M2S + 16 + GXC_RING_M2S_CAP, "off overlay");
_Static_assert(GXC_TOTAL_SIZE == 30134304, "total");

_Static_assert(sizeof(GxcMbxPart) == 60, "mbx part");
_Static_assert(offsetof(GxcMbxPart, mtx) == 12, "mbx part.mtx");
_Static_assert(sizeof(GxcMailbox) == 4048, "mailbox");
_Static_assert(offsetof(GxcMailbox, stage_name) == 4016, "mbx.stage");
_Static_assert(offsetof(GxcMailbox, inbox_addr) == 4008, "mbx.inbox");
_Static_assert(sizeof(GxcPlanet) == 32 && sizeof(GxcChunk) == 32 && sizeof(GxcInboxHeader) == 16, "voxel");
_Static_assert(GXC_MSG_PLANET == 102 && GXC_MSG_CHUNK == 103 && GXC_MSG_PLANET_TP == 104, "voxel msgs");
_Static_assert(GXC_PLAYER_ITEM_ACTIVE == 2u, "item active");
_Static_assert(offsetof(GxcMailbox, game_seq) == 12, "mbx.game_seq");
_Static_assert(offsetof(GxcMailbox, scene_id) == 20, "mbx.scene");
_Static_assert(offsetof(GxcMailbox, gravity) == 24, "mbx.gravity");
_Static_assert(offsetof(GxcMailbox, anchor_pos) == 36, "mbx.anchor");
_Static_assert(offsetof(GxcMailbox, host_flags) == 52, "mbx.host_flags");
_Static_assert(offsetof(GxcMailbox, player_pos) == 56, "mbx.player_pos");
_Static_assert(offsetof(GxcMailbox, fov_y) == 92, "mbx.fov");
_Static_assert(offsetof(GxcMailbox, cam_offset) == 100, "mbx.cam_offset");
_Static_assert(offsetof(GxcMailbox, cam_pos) == 112, "mbx.cam_pos");
_Static_assert(offsetof(GxcMailbox, cam_fov) == 148, "mbx.cam_fov");
_Static_assert(offsetof(GxcMailbox, mario_front) == 152, "mbx.mario_front");
_Static_assert(offsetof(GxcMailbox, part_count) == 164, "mbx.part_count");
_Static_assert(offsetof(GxcMailbox, parts) == 168, "mbx.parts");
_Static_assert(GXC_MBX_GAME_FOLLOWING == 1u && GXC_MBX_GAME_DEMO == 2u, "mbx flags");
_Static_assert(GXC_MBX_FOLLOW == 2u && GXC_WORLD_FOLLOW == 2u && GXC_MBX_GALAXY_VIEW == 4u &&
               GXC_MBX_THIRD_PERSON == 8u, "flags");
_Static_assert(GXC_VERSION == 6u && GXC_MBX_VERSION == 4u, "v6");
_Static_assert(sizeof(GxcTextState) == 264 && GXC_OFF_TEXT >= GXC_OFF_GAMECAM + sizeof(GxcGameCamera) &&
               GXC_OFF_TEXT + sizeof(GxcTextState) <= GXC_OFF_RING_S2M, "text");
_Static_assert(GXC_PLAYER_SCREEN == 4u && GXC_PLAYER_FLYING == 8u, "screen, flying");

int main(void) { puts("OK"); return 0; }
