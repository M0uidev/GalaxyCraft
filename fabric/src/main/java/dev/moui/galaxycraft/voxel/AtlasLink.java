package dev.moui.galaxycraft.voxel;

import dev.moui.galaxycraft.proto.Layout;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Sends the atlas to the game in GXC_MSG_ATLAS pieces, from the start again in every new scene or
 * host (which start without it). No Minecraft types, so it is unit tested.
 */
public final class AtlasLink {
    public static final int PIECE = Layout.ATLAS_PIECE_MAX;

    private final Atlas atlas;
    private final int id;
    private int scene = Integer.MIN_VALUE, host = Integer.MIN_VALUE;
    private int offset;

    public AtlasLink(Atlas atlas, int id) {
        this.atlas = atlas;
        this.id = id;
    }

    /** The next piece for this scene and host, or null once all of it is out. */
    public byte[] peek(int sceneId, int hostPid) {
        if (sceneId != scene || hostPid != host) {
            scene = sceneId;
            host = hostPid;
            offset = 0;
        }
        if (offset >= atlas.data.length) return null;
        int size = Math.min(PIECE, atlas.data.length - offset);
        ByteBuffer b = ByteBuffer.allocate(24 + size).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(id).putInt(atlas.width()).putInt(atlas.height()).putInt(Atlas.LEVELS).putInt(atlas.data.length)
                .putInt(offset);
        b.put(atlas.data, offset, size);
        return b.array();
    }

    /** The ring took the piece peek gave. */
    public void sent() {
        offset = Math.min(atlas.data.length, offset + PIECE);
    }

    public boolean done() {
        return offset >= atlas.data.length;
    }
}
