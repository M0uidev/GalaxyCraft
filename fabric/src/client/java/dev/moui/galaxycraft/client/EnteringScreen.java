package dev.moui.galaxycraft.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Entering a world: an opaque screen over the game until Mario stands on the world's planet
 * (SMG2 may still be booting behind it, its warning and file select showing), then a short fade
 * into the camera's zoom from space down to the player's view. The world keeps running behind it (not a pause screen), so the landing happens.
 */
final class EnteringScreen extends Screen {
    /** Ticks the game is shown settled (landed, linked) before the fade starts. */
    private static final int SETTLE_TICKS = 10;
    /** The fade, ms: quick, the camera's zoom from space (GalaxyCraftClient.startIntro) is the way in. */
    private static final long FADE_MS = 300;
    /** Longest it covers the game, ms: whatever happens, the player is not left behind it. */
    private static final long MAX_MS = 60_000;
    /** Past this, ms, landed and linked but a planet still loading: the player goes in anyway. */
    private static final long LOAD_MS = 40_000;
    private final long opened = System.currentTimeMillis();
    private int settled;
    private long fadeFrom;

    private EnteringScreen() {
        this("Entering the galaxy");
    }

    private EnteringScreen(String text) {
        super(Component.literal(text));
    }

    /** A warp to another system: the same dark screen and zoom in, with what it says. */
    static void warping(Minecraft mc, String text) {
        mc.gui.setScreen(new EnteringScreen(text));
    }

    /** Shown at the first tick with no screen after a world is entered (Minecraft's own loading screen goes first). */
    private static boolean pending;

    /** Joined, not yet known whether the world has a galaxy (GalaxyWorlds.joined runs a task later). */
    private static boolean joining;

    /** A world is being joined: entering from now on, the game kept silent. */
    static void joining() {
        joining = true;
    }

    /** A world was entered. */
    static void show(Minecraft mc) {
        pending = PlanetClient.galaxy() != null;
        joining = false;
    }

    /**
     * Whether the world is still being entered: the screen still to come or up, not yet fading
     * into the zoom. The game is not heard until then (its galaxy loading, Mario landing).
     */
    static boolean entering(Minecraft mc) {
        return joining || pending || mc.gui.screen() instanceof EnteringScreen s && s.fadeFrom == 0;
    }

    /** Every client tick: the screen up once Minecraft's loading screen is gone. */
    static void tick(Minecraft mc) {
        if (!pending || mc.level == null) return;
        if (mc.gui.screen() == null) {
            mc.gui.setScreen(new EnteringScreen());
            pending = false;
        }
    }

    @Override
    public void tick() {
        int[] loaded = PlanetClient.loadProgress();
        boolean ready = !PlanetClient.waitingToLand() && GalaxyCraftClient.linkedToGalaxy()
                && (loaded[0] >= loaded[1] || System.currentTimeMillis() - opened > LOAD_MS);
        settled = ready ? settled + 1 : 0;
        if (fadeFrom == 0 && (settled >= SETTLE_TICKS || System.currentTimeMillis() - opened > MAX_MS)) {
            fadeFrom = System.currentTimeMillis();
            if (ready) GalaxyCraftClient.startIntro();
        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
        double t = fadeFrom == 0 ? 0 : (System.currentTimeMillis() - fadeFrom) / (double) FADE_MS;
        if (t >= 1) {
            minecraft.gui.setScreen(null);
            return;
        }
        int alpha = (int) Math.round(255 * (1 - t * t)); // slow at first, then quickly out
        g.fill(0, 0, width, height, alpha << 24 | 0x05060C);
        if (fadeFrom == 0) {
            int dots = (int) (System.currentTimeMillis() / 400 % 4);
            g.centeredText(font, title.getString() + ".".repeat(dots), width / 2, height / 2 - 4, 0xFFFFFFFF);
            int[] loaded = PlanetClient.loadProgress();
            if (!PlanetClient.waitingToLand() && loaded[1] > 0)
                g.centeredText(font, "Planets " + loaded[0] + " / " + loaded[1], width / 2, height / 2 + 12, 0xFF9AA0B5);
        }
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
        // Its own fill only: no blur or world behind.
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false;
    }
}
