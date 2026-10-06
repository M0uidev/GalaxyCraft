package dev.moui.galaxycraft.client;

import dev.moui.galaxycraft.GalaxyCraft;
import dev.moui.galaxycraft.settings.Movement;
import dev.moui.galaxycraft.settings.Setting;
import dev.moui.galaxycraft.settings.Settings;
import dev.moui.galaxycraft.voxel.PlanetSession;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;

/**
 * Everything GalaxyCraft's settings screen (Esc, then GalaxyCraft...) shows, in its order: the
 * settings kept between sessions, then buttons that do something now. Adding one here is all it
 * takes: {@link GalaxySettingsScreen} lays them out by kind, {@link Settings} keeps them.
 */
public final class GalaxyOptions {
    /** A button in the settings screen: its label (asked each time it is drawn), what it does. */
    public record Action(Supplier<String> label, String tooltip, BooleanSupplier active, Runnable run) {
        Action(String label, String tooltip, Runnable run) {
            this(() -> label, tooltip, () -> true, run);
        }
    }

    /** Test runs keep theirs in memory: a player's choices must not change what they test. */
    private static final boolean TEST_RUN = System.getProperty("galaxycraft.planetDir") != null
            || System.getProperty("galaxycraft.repoRoot") != null;
    public static final Settings SETTINGS = new Settings(
            TEST_RUN ? null : FabricLoader.getInstance().getConfigDir().resolve("galaxycraft.properties"));

    public static final Setting.Choice<Movement> MOVEMENT = SETTINGS.add(new Setting.Choice<>("movement", "Movement",
            "Mario: SMG2 moves Mario (his jumps, spins and long jumps), drawn as Mario with Steve's skin.\n"
                    + "Minecraft: Minecraft's own physics move you, as Steve; Mario goes with you.\n"
                    + "Mario at Minecraft's speeds: Mario moves at Minecraft's speeds (Ctrl sprints, Shift sneaks) and jumps 1.25 blocks.\n"
                    + "F6 switches them while playing.",
            Movement.class, Movement.MARIO, Movement::label));
    public static final Setting.Text SKIN = SETTINGS.add(new Setting.Text("skin", "Skin",
            "A Minecraft account's name: its skin goes on your character (also /skin <name>). Empty: Steve.", "", 16));
    public static final Setting.Range ENTITY_RANGE = SETTINGS.add(new Setting.Range("entityRange", "Entity Distance",
            "How far from Mario the game draws mobs, items and particles", 16, 64, 8, 64, " blocks"));
    public static final Setting.Range CAMERA_DISTANCE = SETTINGS.add(new Setting.Range("cameraDistance", "Camera Distance",
            "Third person: how far behind you the camera sits, walking on a planet", 2, 16, 1, 4, " blocks"));
    public static final Setting.Range CAMERA_DISTANCE_GLIDING = SETTINGS.add(new Setting.Range("cameraDistanceGliding",
            "Camera Distance Gliding", "Third person, with the elytra open near a planet", 2, 16, 1, 6, " blocks"));
    public static final Setting.Range CAMERA_DISTANCE_SPACE = SETTINGS.add(new Setting.Range("cameraDistanceSpace",
            "Camera Distance in Space", "Third person, out in space (past every planet's gravity); the camera eases between the three",
            2, 16, 1, 10, " blocks"));
    public static final Setting.Range BLOCK_DISTANCE = SETTINGS.add(new Setting.Range("blockDistance", "Planet Block Distance",
            "How far around Mario a planet is its real blocks; past that, its far view. Farther costs more of the game's memory and time",
            32, 160, 16, 96, " blocks"));
    public static final Setting.Range FAR_VIEW_DETAIL = SETTINGS.add(new Setting.Range("farViewDetail", "Far View Detail",
            "How far the far view stays fine past the blocks: next to them it is almost block by block, and each step farther "
                    + "its patches are twice as wide. Higher: finer farther out, more memory",
            1, 6, 1, 2, ""));
    public static final Setting.Toggle PARTICLES = SETTINGS.add(new Setting.Toggle("particles", "Game Particles",
            "Minecraft's particles (explosions, broken blocks, hits) drawn in the game", true));

    /** Buttons under the settings. */
    public static final List<Action> ACTIONS = List.of(
            new Action(() -> "Fly: " + (GalaxyCraftClient.flying() ? "ON" : "OFF"),
                    "Free flight, as /fly: the galaxy's up stays up", () -> Minecraft.getInstance().player != null,
                    GalaxyCraftClient::toggleFlying),
            new Action(() -> "Go to the Planet", "Lands you on the planet nearest Mario (P)",
                    GalaxyCraftClient::linked, () -> {
                        Minecraft.getInstance().gui.setScreen(null);
                        PlanetClient.teleport();
                    }),
            new Action("Planets...", "Design planets and put them in this galaxy (/galaxycraft)",
                    () -> PlanetEditorScreen.open(Minecraft.getInstance())));

    private GalaxyOptions() {}

    /** Loads the settings and puts what they say in place; once, at startup. */
    static void init() {
        BLOCK_DISTANCE.onChange(v -> applyLevelOfDetail());
        FAR_VIEW_DETAIL.onChange(v -> applyLevelOfDetail());
        applyLevelOfDetail();
        SKIN.onChange(name -> SkinClient.wear(name, GalaxyCraftClient::say));
        if (!SKIN.get().isEmpty()) SkinClient.wear(SKIN.get(), msg -> GalaxyCraft.LOG.info("Skin: {}", msg));
    }

    /** Far View Detail d: levels of the far view 16 d blocks apart. */
    private static void applyLevelOfDetail() {
        // -Dgalaxycraft.renderDistance (probes) wins over the setting.
        double render = System.getProperty("galaxycraft.renderDistance") != null ? PlanetSession.RENDER : BLOCK_DISTANCE.get();
        PlanetSession.setLevelOfDetail(render, 16.0 * FAR_VIEW_DETAIL.get());
    }
}
