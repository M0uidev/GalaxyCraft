package dev.moui.galaxycraft.net;

import java.nio.file.Path;
import java.util.UUID;

/** Where a guest caches a host's galaxy. The id comes from the network, so it must be a UUID, nothing else. */
public final class GuestFolder {
    private GuestFolder() {}

    public static Path of(Path dataDir, String worldId) {
        UUID id = UUID.fromString(worldId); // IllegalArgumentException for most junk
        // UUID.fromString is lenient (short groups, odd lengths): only the canonical text is a folder name.
        if (!id.toString().equals(worldId)) throw new IllegalArgumentException("not a world id: " + worldId);
        return dataDir.resolve("guest").resolve(id.toString());
    }
}
