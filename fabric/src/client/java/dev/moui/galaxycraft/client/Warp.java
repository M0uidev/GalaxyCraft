package dev.moui.galaxycraft.client;

import dev.moui.galaxycraft.gravity.GravityFrame;
import dev.moui.galaxycraft.universe.OriginPolicy;
import dev.moui.galaxycraft.universe.SystemIndex;
import dev.moui.galaxycraft.universe.UPos;
import dev.moui.galaxycraft.universe.Universe;
import dev.moui.galaxycraft.universe.Visited;
import dev.moui.galaxycraft.universe.WarpGate;
import java.util.List;
import java.util.Optional;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import org.joml.Vector3d;

/**
 * Warp to another solar system: pick it on the galaxy map (its key, or the warp key; both in Controls). For now only in creative mode
 * or with cheats on (WarpPermission), and then only as far as the Warp Core reaches (WarpGate).
 * The screen goes dark, Mario lands on the system's first planet (the floating origin moves there
 * first) and the camera zooms in from space, as entering a world.
 */
public final class Warp {
    /** A star counts as aimed at within this many degrees of the crosshair. */
    static final double AIM_DEG = 2.5;
    private static final double UNITS = 1 / GravityFrame.SCALE;
    private static int hintTicks, visitTicks;
    private static Visited visited;
    private static java.nio.file.Path visitedFor;

    private Warp() {}

    /** The system Mario is in, if any. */
    static Optional<Universe.Star> here() {
        Universe u = UniverseClient.universe();
        Vector3d mario = PlanetClient.marioUniverse();
        if (u == null || mario == null) return Optional.empty();
        return u.systemAt(UPos.of(mario), OriginPolicy.SYSTEM_MARGIN);
    }

    /** This galaxy's visited systems (reloaded when the galaxy's folder changes); null outside a galaxy. */
    public static Visited visited() {
        java.nio.file.Path f = PlanetClient.visitedFile();
        if (f == null) return null;
        if (visited == null || !f.equals(visitedFor)) {
            visited = Visited.load(f);
            visitedFor = f;
        }
        return visited;
    }

    /** The other systems within the stars' reach, nearest first. */
    public static List<Universe.Star> nearby() {
        Universe u = UniverseClient.universe();
        Vector3d mario = PlanetClient.marioUniverse();
        if (u == null || mario == null) return List.of();
        Optional<Universe.Sector> in = here().map(Universe.Star::sector);
        return u.around(UPos.of(mario), UniverseClient.STAR_SECTORS).stream().filter(s -> in.isEmpty() || !s.sector().equals(in.get()))
                .toList();
    }

    /** The star nearest the crosshair, if one is within AIM_DEG of it. */
    static Optional<Universe.Star> aimed(LocalPlayer player) {
        Vector3d mario = PlanetClient.marioUniverse();
        Vector3d look = GalaxyCraftClient.galaxyLook(player.getYRot(), player.getXRot()).orElse(null);
        if (mario == null || look == null) return Optional.empty();
        double best = Math.cos(Math.toRadians(AIM_DEG));
        Universe.Star found = null;
        for (Universe.Star s : nearby()) {
            double dot = s.center().minus(UPos.of(mario)).normalize().dot(look);
            if (dot > best) {
                best = dot;
                found = s;
            }
        }
        return Optional.ofNullable(found);
    }

    /** "System 1_0_-2: 7 planets, 9,300 blocks". */
    static String label(Universe.Star s) {
        Vector3d mario = PlanetClient.marioUniverse();
        long blocks = mario == null ? 0 : Math.round(s.center().minus(UPos.of(mario)).length() / UNITS);
        String name = s.home() ? "Home" : "System " + SystemIndex.name(s.sector());
        Universe u = UniverseClient.universe();
        String kind = u == null ? "" : ", tier " + u.tier(s) + ", " + u.starType(s).name().toLowerCase().replace('_', ' ');
        return String.format("%s: %d planets, %,d blocks%s", name, s.planets(), blocks, kind);
    }

    /** Whether this player may warp now: creative mode, or cheats on (permission level 2 or more). */
    public static boolean allowed(Minecraft mc) {
        LocalPlayer p = mc.player;
        if (p == null) return false;
        boolean creative = mc.gameMode != null && mc.gameMode.getPlayerMode().isCreative();
        boolean cheats = p.permissions().hasPermission(net.minecraft.server.permissions.Permissions.COMMANDS_GAMEMASTER);
        return dev.moui.galaxycraft.universe.WarpPermission.allowed(creative, cheats);
    }

    /** K: the map (the star in the crosshair is no longer warped to by aiming). */
    static void key(Minecraft mc) {
        if (mc.player == null || UniverseClient.universe() == null || PlanetClient.waitingToLand()) return;
        map(mc);
    }

    static void map(Minecraft mc) {
        if (mc.player == null || UniverseClient.universe() == null) return;
        mc.gui.setScreen(new MapScreen());
    }

    /** Off to that system's first planet. */
    public static void to(Minecraft mc, Universe.Star s) {
        if (!allowed(mc)) {
            GalaxyCraftClient.say(dev.moui.galaxycraft.universe.WarpPermission.refusal());
            return;
        }
        Universe u = UniverseClient.universe();
        Visited seen = visited();
        if (u != null && seen != null && !WarpGate.allowed(u, s, seen, GalaxyOptions.WARP_CORE.get())) {
            GalaxyCraftClient.say(WarpGate.why(u, s, GalaxyOptions.WARP_CORE.get()));
            return;
        }
        int index = s.home() ? PlanetClient.catalog().getFirst().index() : SystemIndex.index(s.sector(), 0);
        GalaxyCraftClient.say("warping to " + label(s));
        Flight.end(mc.player); // no glide or pulse into the warp (the landing ends any left too)
        PlanetClient.travelTo(index);
        EnteringScreen.warping(mc, "Warping to " + (s.home() ? "home" : "system " + SystemIndex.name(s.sector())));
    }

    /** Every client tick: out in the void, the star in the crosshair is named above the hotbar. */
    static void tick(Minecraft mc) {
        if (++visitTicks >= 20) { // once a second: the system Mario is in counts as visited
            visitTicks = 0;
            Visited seen = visited();
            if (seen != null)
                here().ifPresent(s -> {
                    if (seen.mark(s.sector())) {
                        try {
                            seen.save();
                        } catch (java.io.IOException e) {
                            dev.moui.galaxycraft.GalaxyCraft.LOG.warn("Visited systems not saved: {}", e.toString());
                        }
                    }
                });
        }
        if (mc.player == null || mc.gui.screen() != null || !GalaxyCraftClient.inVoid() || ++hintTicks < 10) return;
        hintTicks = 0;
        aimed(mc.player).ifPresent(s -> mc.gui.hud.setOverlayMessage(Component.literal(label(s) + " - " + ModKeys.label(ModKeys.WARP) + " for the map"), false));
    }
}
