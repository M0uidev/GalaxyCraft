package dev.moui.galaxycraft.client;

import dev.moui.galaxycraft.gravity.GravityFrame;
import dev.moui.galaxycraft.universe.OriginPolicy;
import dev.moui.galaxycraft.universe.SystemIndex;
import dev.moui.galaxycraft.universe.UPos;
import dev.moui.galaxycraft.universe.Universe;
import java.util.List;
import java.util.Optional;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import org.joml.Vector3d;

/**
 * Warp to another solar system, free: aim at its star on the sky and press K, or pick it on the
 * galaxy map (N, or K with no star in the crosshair). The screen goes dark, Mario lands on the
 * system's first planet (the floating origin moves there first) and the camera zooms in from
 * space, as entering a world.
 */
public final class Warp {
    /** A star counts as aimed at within this many degrees of the crosshair. */
    static final double AIM_DEG = 2.5;
    /** SDL scancodes: K warps to the star aimed at (or opens the map), N opens the map. */
    static final int SC_K = 14, SC_N = 17;
    private static final double UNITS = 1 / GravityFrame.SCALE;
    private static int hintTicks;

    private Warp() {}

    /** The system Mario is in, if any. */
    static Optional<Universe.Star> here() {
        Universe u = UniverseClient.universe();
        Vector3d mario = PlanetClient.marioUniverse();
        if (u == null || mario == null) return Optional.empty();
        return u.systemAt(UPos.of(mario), OriginPolicy.SYSTEM_MARGIN);
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
        return String.format("%s: %d planets, %,d blocks", s.home() ? "Home" : "System " + SystemIndex.name(s.sector()), s.planets(), blocks);
    }

    /** K: to the star in the crosshair, or the map when there is none. */
    static void key(Minecraft mc) {
        if (mc.player == null || UniverseClient.universe() == null || PlanetClient.waitingToLand()) return;
        Optional<Universe.Star> s = aimed(mc.player);
        if (s.isPresent()) to(mc, s.get());
        else map(mc);
    }

    static void map(Minecraft mc) {
        if (mc.player == null || UniverseClient.universe() == null) return;
        mc.gui.setScreen(new GalaxyMapScreen());
    }

    /** Off to that system's first planet. */
    public static void to(Minecraft mc, Universe.Star s) {
        int index = s.home() ? PlanetClient.catalog().getFirst().index() : SystemIndex.index(s.sector(), 0);
        GalaxyCraftClient.say("warping to " + label(s));
        Flight.end(mc.player); // no glide or pulse into the warp (the landing ends any left too)
        PlanetClient.travelTo(index);
        EnteringScreen.warping(mc, "Warping to " + (s.home() ? "home" : "system " + SystemIndex.name(s.sector())));
    }

    /** Every client tick: out in the void, the star in the crosshair is named above the hotbar. */
    static void tick(Minecraft mc) {
        if (mc.player == null || mc.gui.screen() != null || !GalaxyCraftClient.inVoid() || ++hintTicks < 10) return;
        hintTicks = 0;
        aimed(mc.player).ifPresent(s -> mc.gui.hud.setOverlayMessage(Component.literal(label(s) + " - K to warp"), false));
    }
}
