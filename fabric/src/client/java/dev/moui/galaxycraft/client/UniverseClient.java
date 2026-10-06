package dev.moui.galaxycraft.client;

import dev.moui.galaxycraft.GalaxyCraft;
import dev.moui.galaxycraft.bridge.BridgeClient;
import dev.moui.galaxycraft.gravity.GravityFrame;
import dev.moui.galaxycraft.proto.Layout;
import dev.moui.galaxycraft.proto.Seqlock;
import dev.moui.galaxycraft.universe.GameOrigin;
import dev.moui.galaxycraft.universe.Origin;
import dev.moui.galaxycraft.universe.OriginPolicy;
import dev.moui.galaxycraft.universe.UPos;
import dev.moui.galaxycraft.universe.Universe;
import dev.moui.galaxycraft.voxel.PlanetLayout;
import dev.moui.galaxycraft.voxel.PlanetSession;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.joml.Vector3d;

/**
 * The floating origin in play: each tick, OriginPolicy says whether the game's origin moves (into a
 * system's center on the way in, after Mario out in the void), the move goes to the game
 * (GXC_MSG_ORIGIN) before anything sent from the new origin, and planet records still queued are
 * made again from it. A new scene or host is told the epoch first.
 *
 * -Dgalaxycraft.floatingOrigin=false keeps the origin at the universe's (0, 0, 0) (as before);
 * /galaxycraft origin x y z pins it there (blocks) to see what far-away floats do.
 */
final class UniverseClient {
    private static final double UNITS = 1 / GravityFrame.SCALE;
    private static final boolean ON = !"false".equals(System.getProperty("galaxycraft.floatingOrigin"));

    private static int scene = Integer.MIN_VALUE, host = Integer.MIN_VALUE;
    private static boolean told;
    /** /galaxycraft origin: the origin stays here (universe units) until "auto". */
    private static Vector3d pinned;
    private static Universe universe;
    private static int moves;

    private UniverseClient() {}

    /** The world's universe, from its seed (null: none, out of a world). */
    static void enter(Universe u) {
        universe = u;
    }

    static Universe universe() {
        return universe;
    }

    /** Moves made since the client started (tests). */
    static int moves() {
        return moves;
    }

    /** Pins the origin at that many blocks from the universe's (0, 0, 0); null lets it follow again. */
    static void pin(Vector3d blocks) {
        pinned = blocks == null ? null : new Vector3d(blocks).mul(UNITS);
    }

    /** Before the planets' messages of the tick. landing: a teleport or landing is under way. */
    static void tick(BridgeClient bridge, Seqlock.WorldState world, boolean landing) {
        if (world.sceneId() != scene || bridge.hostPid() != host) {
            scene = world.sceneId();
            host = bridge.hostPid();
            told = false;
        }
        if (!told) told = bridge.send(Layout.MSG_ORIGIN, GameOrigin.message(GameOrigin.now()));
        if (!told) return;
        Vector3d mario = world.queryPos();
        Optional<UPos> goal;
        if (pinned != null) goal = Optional.of(UPos.of(pinned));
        else if (!ON || mario == null) goal = Optional.empty();
        else {
            List<PlanetLayout.Sphere> spheres = new ArrayList<>();
            for (PlanetSession s : PlanetClient.planets())
                if (s.active()) spheres.add(new PlanetLayout.Sphere(s.center(), s.gravityUnits()));
            boolean clear = OriginPolicy.clear(mario, spheres, landing, UNITS);
            UPos at = UPos.of(mario);
            Optional<Universe.Star> system = universe == null ? Optional.empty() : universe.systemAt(at, OriginPolicy.SYSTEM_MARGIN);
            goal = OriginPolicy.target(GameOrigin.origin(), at, system, clear);
        }
        goal.ifPresent(g -> move(bridge, g));
    }

    private static void move(BridgeClient bridge, UPos goal) {
        Origin.Shift s = GameOrigin.moveTo(goal, m -> bridge.send(Layout.MSG_ORIGIN, m));
        if (s == null) return;
        moves++;
        for (PlanetSession p : PlanetClient.planets()) p.originMoved();
        PlanetClient.originMoved();
        GalaxyCraft.LOG.info("Floating origin moved by {} {} {} cells (epoch {}), now at {} blocks", s.dx(), s.dy(), s.dz(),
                s.epoch(), GameOrigin.offset().div(UNITS));
    }

    /** The world is left: the origin goes back to the universe's (0, 0, 0) for the next one. */
    static void leave(BridgeClient bridge) {
        universe = null;
        pinned = null;
        if (GameOrigin.origin().peek(UPos.ZERO) != null && GameOrigin.moveTo(UPos.ZERO, m -> bridge.send(Layout.MSG_ORIGIN, m)) == null)
            GameOrigin.reset(); // not linked: the next scene is told anew
        told = false;
    }
}
