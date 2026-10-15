package dev.moui.galaxycraft.toad;

import dev.moui.galaxycraft.GalaxyCraft;
import dev.moui.galaxycraft.ship.model.BckReader;
import dev.moui.galaxycraft.ship.model.BdlReader;
import dev.moui.galaxycraft.ship.model.Rarc;
import dev.moui.galaxycraft.net.Protocol;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * SMG2's own Mario (Mario.arc, with his animations from MarioAnime.arc, both copied by the launcher
 * from the player's disc into the ship directory) as the rigid pieces of {@link ToadModels}, at the
 * size he has in the game: 16 pixels a block with 80 game units a block. Used to draw the other
 * players who are in Mario movement. Anything missing or unreadable is one warning, once, and null:
 * the caller keeps drawing Steve.
 */
public final class MarioModels {
    /** Pixels (16 a block) per game unit. */
    static final float PX_PER_UNIT = (float) (16 * dev.moui.galaxycraft.gravity.GravityFrame.SCALE);

    private static volatile ToadModels.Loaded loaded;
    private static volatile boolean tried;

    private MarioModels() {}

    /** Mario, read from dir the first time; null if he cannot be (warned once). */
    public static ToadModels.Loaded get(Path dir) {
        if (!tried) {
            synchronized (MarioModels.class) {
                if (!tried) {
                    loaded = read(dir);
                    tried = true;
                }
            }
        }
        return loaded;
    }

    /** Forget what was read (the ship directory changed, or a test). */
    public static synchronized void reset() {
        loaded = null;
        tried = false;
    }

    static ToadModels.Loaded read(Path dir) {
        try {
            Path body = dir.resolve("Mario.arc");
            if (!Files.isRegularFile(body)) {
                GalaxyCraft.LOG.warn("Mario.arc is not in {}: the other players stay Steve (the launcher copies it from your disc)", dir);
                return null;
            }
            byte[] bdl = bdl(Rarc.read(Files.readAllBytes(body)));
            if (bdl == null) throw new IllegalArgumentException("no model in Mario.arc");
            Map<String, BckReader.Anim> anims = new HashMap<>();
            Path animArc = dir.resolve("MarioAnime.arc");
            if (Files.isRegularFile(animArc))
                for (var e : new TreeMap<>(Rarc.read(Files.readAllBytes(animArc))).entrySet())
                    if (e.getKey().endsWith(".bck"))
                        try {
                            anims.put(e.getKey().substring(0, e.getKey().length() - 4), BckReader.read(e.getValue()));
                        } catch (IllegalArgumentException x) {
                            // one animation we cannot read is not worth Mario
                        }
            ToadModels.Loaded l = ToadModels.build(BdlReader.readRig(bdl), anims, PX_PER_UNIT);
            if (l == null) throw new IllegalArgumentException("Mario has nothing to draw");
            if (anims.isEmpty()) GalaxyCraft.LOG.warn("MarioAnime.arc is missing or unreadable: Mario will stand in his rest pose");
            return l;
        } catch (IOException | IllegalArgumentException e) {
            GalaxyCraft.LOG.warn("Mario could not be read from {} ({}): the other players stay Steve", dir, e.getMessage());
            return null;
        }
    }

    private static byte[] bdl(Map<String, byte[]> files) {
        return new TreeMap<>(files).entrySet().stream().filter(e -> e.getKey().endsWith(".bdl") || e.getKey().endsWith(".bmd"))
                .map(Map.Entry::getValue).findFirst().orElse(null);
    }

    /**
     * Which of Mario's animations a peer plays: from its flags (Protocol.F_*)
     * and walkSpeed (0..1, Minecraft's limb speed). Names are those of MarioAnime.arc.
     */
    public static String animation(int flags, float walkSpeed) {
        boolean sneak = (flags & Protocol.F_SNEAK) != 0, swim = (flags & Protocol.F_SWIM) != 0, glide = (flags & Protocol.F_GLIDE) != 0,
                ground = (flags & Protocol.F_GROUND) != 0, seated = (flags & (Protocol.F_SHIP | Protocol.F_RIDE)) != 0;
        if (seated) return "RideWait";
        if (swim) return walkSpeed > 0.1f ? "SwimFlutterSurface" : "SwimWait";
        if (glide) return "Fall";
        if (sneak) return walkSpeed > 0.1f ? "SquatWalk" : "SquatWait";
        if (!ground) return "Fall";
        if (walkSpeed < 0.1f) return "Wait";
        return walkSpeed < 0.6f ? "Walk" : "Run";
    }
}
