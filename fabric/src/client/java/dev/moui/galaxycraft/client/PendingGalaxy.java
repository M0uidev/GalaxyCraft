package dev.moui.galaxycraft.client;

import dev.moui.galaxycraft.voxel.GalaxyCatalog;
import java.util.Optional;

/**
 * The Create World tab's galaxy options, from the tab to the new world's first visit (its catalog
 * is made then, with the world's seed). Cleared when a Create World screen opens again.
 */
public final class PendingGalaxy {
    private static GalaxyCatalog.Options options;

    private PendingGalaxy() {}

    public static synchronized void set(GalaxyCatalog.Options o) {
        options = o;
    }

    public static synchronized Optional<GalaxyCatalog.Options> peek() {
        return Optional.ofNullable(options);
    }

    public static synchronized Optional<GalaxyCatalog.Options> take() {
        Optional<GalaxyCatalog.Options> o = Optional.ofNullable(options);
        options = null;
        return o;
    }
}
