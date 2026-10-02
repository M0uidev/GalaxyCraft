package dev.moui.galaxycraft.proto;

/** Mirror of protocol/galaxycraft_protocol.h. Offsets are pinned by protocol/test_layout.c. */
public final class Layout {
    public static final String SHM_PATH = "/dev/shm/galaxycraft_v1";
    public static final int MAGIC = 0x52435847; // "GXCR"
    public static final int VERSION = 2;
    public static final int MOD_VERSION = 1;

    public static final long OFF_HEADER = 0;
    public static final long OFF_WORLD = 64;
    public static final long OFF_PLAYER = 128;
    public static final long OFF_INPUT = 224;
    public static final long OFF_RING_S2M = 4096;
    public static final int RING_S2M_CAP = 4 * 1024 * 1024;
    public static final long OFF_RING_M2S = OFF_RING_S2M + 16 + RING_S2M_CAP;
    public static final int RING_M2S_CAP = 64 * 1024;
    public static final long OFF_OVERLAY = 4268032;
    public static final long OVERLAY_FRAME_BYTES = 1920L * 1080L * 4L;
    public static final long TOTAL_SIZE = OFF_OVERLAY + 32 + 3 * OVERLAY_FRAME_BYTES;

    // Header fields.
    public static final long H_MAGIC = 0, H_VERSION = 4, H_HOST_PID = 8, H_MOD_PID = 12;
    public static final long H_HOST_HEARTBEAT = 16, H_MOD_HEARTBEAT = 24, H_HOST_FLAGS = 32, H_MOD_FLAGS = 36;

    public static final long HEARTBEAT_TIMEOUT_MS = 2000;
    public static final int KCL_CHUNK_MAX = 65536;

    public static final int MSG_SCENE_CHANGE = 1;
    public static final int MSG_PART_UPSERT = 2;
    public static final int MSG_PART_REMOVE = 3;
    public static final int MSG_KCL_CHUNK = 4;
    public static final int MSG_HELLO = 101;
    public static final int MSG_PAD = 0xFFFF;

    public static final int PLAYER_ON_GROUND = 1;
    /** WorldState.flags: queryPos is the host's anchor (where the player is). */
    public static final int WORLD_ANCHOR = 1;
    public static final int WORLD_FOLLOW = 2;

    private Layout() {}
}
