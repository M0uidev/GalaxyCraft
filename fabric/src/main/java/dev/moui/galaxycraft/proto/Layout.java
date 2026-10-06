package dev.moui.galaxycraft.proto;

/** Mirror of protocol/galaxycraft_protocol.h. Offsets are pinned by protocol/test_layout.c. */
public final class Layout {
    public static final String SHM_PATH = "/dev/shm/galaxycraft_v1";
    public static final int MAGIC = 0x52435847; // "GXCR"
    public static final int VERSION = 10;
    public static final int MOD_VERSION = 1;

    public static final long OFF_HEADER = 0;
    public static final long OFF_WORLD = 64;
    public static final long OFF_PLAYER = 128;
    public static final long OFF_INPUT = 224;
    public static final long OFF_GAMECAM = 320;
    /** GxcPointerState: the mouse over the host's window (0..1) while a Minecraft screen is open. */
    public static final long OFF_POINTER = 448;
    public static final int POINTER_INSIDE = 1;
    /** GxcTextState: characters typed in the host's window. */
    public static final long OFF_TEXT = 512;
    public static final int TEXT_RING = 64;
    public static final long OFF_RING_S2M = 4096;
    public static final int RING_S2M_CAP = 4 * 1024 * 1024;
    public static final long OFF_RING_M2S = OFF_RING_S2M + 16 + RING_S2M_CAP;
    public static final int RING_M2S_CAP = 1024 * 1024;
    public static final long OFF_OVERLAY = 5251072;
    public static final long OVERLAY_FRAME_BYTES = 1920L * 1080L * 4L;
    public static final long TOTAL_SIZE = OFF_OVERLAY + 32 + 3 * OVERLAY_FRAME_BYTES;

    // Header fields.
    public static final long H_MAGIC = 0, H_VERSION = 4, H_HOST_PID = 8, H_MOD_PID = 12;
    public static final long H_HOST_HEARTBEAT = 16, H_MOD_HEARTBEAT = 24, H_HOST_FLAGS = 32, H_MOD_FLAGS = 36;
    /** GalaxyCraft's own galaxy, where Minecraft's worlds are played (GXC_SPACE_STAGE). */
    public static final String SPACE_STAGE = "GalaxyCraftSpace";
    /** host_flags: SMG2 is in GalaxyCraftSpace, ready for a world (GXC_HOST_SPACE_READY). */
    public static final int HOST_SPACE_READY = 4;
    /** mod_flags: Minecraft is in a world (else Dolphin shows its menus over the game). */
    public static final int MOD_IN_WORLD = 2;
    /** mod_flags: entering that world, its screen still over the game: silent (GXC_MOD_ENTERING). */
    public static final int MOD_ENTERING = 4;

    public static final long HEARTBEAT_TIMEOUT_MS = 2000;
    public static final int KCL_CHUNK_MAX = 65536;

    public static final int MSG_SCENE_CHANGE = 1;
    public static final int MSG_PART_UPSERT = 2;
    public static final int MSG_PART_REMOVE = 3;
    public static final int MSG_KCL_CHUNK = 4;
    public static final int MSG_HELLO = 101;
    /** Voxel planet, M -> S: GxcPlanet, GxcChunk + display list + KCL, teleport. */
    public static final int MSG_PLANET = 102, MSG_CHUNK = 103, MSG_PLANET_TP = 104, MSG_OUTLINE = 105, MSG_HELD = 106;
    /** A piece of the block atlas (GxcAtlas + data), M -> S. */
    public static final int MSG_ATLAS = 107, ATLAS_PIECE_MAX = 65536;
    public static final int MSG_SKIN = 108, MSG_MODEL = 109, MSG_ENTITIES = 110, MSG_HURT = 111, MSG_SEAT = 112, MSG_SKY = 113;
    /** The skin of Mario's model (Steve), M -> S for the host: a 64x64 GXC_MSG_SKIN payload. */
    public static final int MSG_MARIO_SKIN = 114;
    /** The block being broken, M -> S: GxcCrack, Minecraft's cracks at stage 0..CRACK_STAGES - 1. */
    public static final int MSG_CRACK = 115, CRACK_STAGES = 10;
    /** Edges a GxcOutline carries at most. */
    public static final int OUTLINE_MAX_EDGES = 96;
    public static final int HURT_HIT = 0, HURT_FIRE = 1, HURT_EXPLOSION = 2;
    public static final int ENT_MAX_SKINS = 256, ENT_MAX_MODELS = 2048, ENT_MAX = 768, ENT_BYTES = 60;
    public static final int ENT_SKIN_MAX = 256, ENT_DL_MAX = 65536, ENT_VTXFMT = 3;
    public static final int PLANET_MAX_CHUNKS = 131072;
    public static final int MSG_PAD = 0xFFFF;

    public static final int PLAYER_ON_GROUND = 1;
    /** PlayerState.flags: something in the main hand, the clicks break and place blocks. */
    public static final int PLAYER_ITEM_ACTIVE = 2;
    /** PlayerState.flags: a Minecraft screen is open (chat...): the keyboard is not Mario's. */
    public static final int PLAYER_SCREEN = 4;
    /** PlayerState.flags: /fly, the player flies on its own with the galaxy's +Y up. */
    public static final int PLAYER_FLYING = 8;
    /** PlayerState.flags: F3+B, Mario's collision is drawn. */
    public static final int PLAYER_HITBOXES = 16;
    /** PlayerState.flags: Minecraft movement, the player walks on its own and Mario goes with it. */
    public static final int PLAYER_WALKING = 32;
    /** Minecraft's feel on Mario: SMG2 moves him at Minecraft's speeds, with its 1.25-block jump. */
    public static final int PLAYER_MC_FEEL = 128;
    /** PlayerState.flags: hold SMG2's + button (its own pause menu). */
    public static final int PLAYER_PLUS = 64;
    /** WorldState.flags: queryPos is the host's anchor (where the player is). */
    public static final int WORLD_ANCHOR = 1;
    public static final int WORLD_FOLLOW = 2;

    /** PlayerState.view: the perspective chosen with F5. */
    public static final int VIEW_FIRST = 0, VIEW_BACK = 1, VIEW_FRONT = 2, VIEW_GALAXY = 3;
    /** GameCamera.flags. */
    public static final int GAMECAM_VALID = 1, GAMECAM_DEMO = 2;

    private Layout() {}
}
