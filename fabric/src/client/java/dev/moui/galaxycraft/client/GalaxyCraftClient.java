package dev.moui.galaxycraft.client;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;

import dev.moui.galaxycraft.GalaxyCraft;
import dev.moui.galaxycraft.bridge.BridgeClient;
import dev.moui.galaxycraft.gravity.Follow;
import dev.moui.galaxycraft.gravity.GravityFrame;
import dev.moui.galaxycraft.gravity.LookMath;
import dev.moui.galaxycraft.proto.Layout;
import dev.moui.galaxycraft.proto.Seqlock;
import dev.moui.galaxycraft.settings.Movement;
import dev.moui.galaxycraft.view.CameraMath;
import dev.moui.galaxycraft.view.View;
import dev.moui.galaxycraft.voxel.VoxelPlanet;
import java.nio.file.Path;
import java.util.Optional;
import com.mojang.blaze3d.pipeline.RenderTarget;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.debug.DebugScreenEntries;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;
import org.lwjgl.sdl.SDLVideo;

/**
 * Drives the local player through the galaxy: polls the bridge, re-aims the gravity frame
 * before each physics tick, and reports the player's galaxy pose after it.
 */
public final class GalaxyCraftClient implements ClientModInitializer {
    private static BridgeClient bridge;
    private static OverlayExporter exporter;
    private static final InputInjector input = new InputInjector();
    private static GravityFrame frame;
    private static long frameId;
    /** The host's scene the frame was anchored in: poses from an older scene do not count. */
    private static int frameScene;
    /** Ticks left to wait for the ground under a freshly linked player (then let go anyway). */
    private static final int SETTLE_TICKS = 60;
    private static final double SETTLE_DEPTH_BLOCKS = 6;
    /** Mario mode: the frame turns at most this much per tick (a box planet's edge in ~0.5 s). */
    private static final double MAX_TURN_PER_TICK = Math.toRadians(9);
    private static int settleTicks;
    private static boolean holding;
    /** Mario mode this tick: the player is carried to Mario, SMG2 owns the movement. */
    private static boolean following;
    /** The host's last emulated frame this mod rendered after; waits stop short of a stalled host. */
    private static long hostFrame = -1;
    private static final long FRAME_WAIT_MS = 25;
    /** Where the player goes at the start of its own tick (after its old position is saved). */
    private static Vector3d followTarget;
    /** F5 went past THIRD_PERSON_FRONT: SMG2's own camera (Minecraft only draws the HUD). */
    private static boolean galaxyView;
    /** /fly: the player flies on its own like in creative, with the galaxy's +Y as up; Mario waits. */
    private static boolean flying;
    private static final Vector3d GALAXY_UP = new Vector3d(0, 1, 0);
    /** The camera as last drawn, galaxy space: what SMG2's camera copies outside the Galaxy view. */
    private static Vector3d camOffsetGal, camLookGal, camUpGal;
    /** Ticks left holding SMG2's + button (the pause menu's SMG2 Menu): long enough for the game to see it. */
    private static int plusTicks;
    private static final int PLUS_TICKS = 4;

    @Override
    public void onInitializeClient() {
        bridge = new BridgeClient(Path.of(Layout.SHM_PATH), () -> System.nanoTime() / 1_000_000L,
                new BridgeClient.PartListener() {
                    @Override public void onUpsert(int partId, double[] mtx, byte[] kcl) {
                        try {
                            GalaxyCraft.FIELD.upsertPart(partId, mtx, kcl);
                        } catch (IllegalArgumentException e) {
                            GalaxyCraft.LOG.warn("Ignoring bad KCL for part {}: {}", partId, e.getMessage());
                            GalaxyCraft.FIELD.removePart(partId);
                        }
                    }

                    @Override public void onRemove(int partId) {
                        GalaxyCraft.FIELD.removePart(partId);
                    }

                    @Override public void onScene(int sceneId) {
                        GalaxyCraft.LOG.info("Galaxy scene {}", sceneId);
                        GalaxyCraft.FIELD.clear();
                        resetFrame();
                    }
                });
        ClientTickEvents.START_CLIENT_TICK.register(GalaxyCraftClient::beforeTick);
        ClientTickEvents.END_CLIENT_TICK.register(GalaxyCraftClient::afterTick);
        ClientTickEvents.END_CLIENT_TICK.register(PlanetEditorScreen::openIfRequested);
        ClientTickEvents.END_CLIENT_TICK.register(client -> { // after the chat that ran the command has closed
            if (settingsRequested && client.gui.screen() == null) {
                settingsRequested = false;
                client.gui.setScreen(new GalaxySettingsScreen(null));
            }
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> resetFrame());
        ClientLifecycleEvents.CLIENT_STARTED.register(client -> {
            if (Boolean.getBoolean("galaxycraft.hidden")) { // Dolphin shows the overlay instead
                client.options.pauseOnLostFocus = false;
                SDLVideo.SDL_HideWindow(client.getWindow().handle());
            }
        });
        GalaxyOptions.init();
        PauseMenu.register();
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, ctx) -> dispatcher.register(literal("fly").executes(c -> {
            toggleFlying();
            return 1;
        })));
        // /skin <account>: that account's skin on the character; /skin alone: Steve's again.
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, ctx) -> dispatcher.register(literal("skin")
                .executes(c -> {
                    GalaxyOptions.SKIN.set("");
                    return 1;
                })
                .then(argument("account", StringArgumentType.word()).executes(c -> {
                    String name = StringArgumentType.getString(c, "account");
                    c.getSource().sendFeedback(Component.literal("GalaxyCraft: looking up " + name + "'s skin..."));
                    if (name.equals(GalaxyOptions.SKIN.get())) SkinClient.wear(name, GalaxyCraftClient::say); // again
                    else GalaxyOptions.SKIN.set(name);
                    return 1;
                }))));
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, ctx) -> dispatcher.register(
                literal("galaxycraft").executes(c -> {
                    PlanetEditorScreen.requestOpen();
                    return 1;
                }).then(literal("settings").executes(c -> {
                    settingsRequested = true;
                    return 1;
                })).then(literal("status").executes(c -> {
                    c.getSource().sendFeedback(Component.literal(status(c.getSource().getPlayer())));
                    return 1;
                })).then(literal("planet")
                        .then(literal("spawn")
                                .executes(c -> planetCommand(c.getSource(), () -> PlanetClient.requestSpawn(PlanetClient.DEFAULT_RADIUS)))
                                .then(argument("radius", IntegerArgumentType.integer(VoxelPlanet.MIN_RADIUS, VoxelPlanet.MAX_RADIUS))
                                        .executes(c -> planetCommand(c.getSource(),
                                                () -> PlanetClient.requestSpawn(IntegerArgumentType.getInteger(c, "radius"))))))
                        .then(literal("add")
                                .executes(c -> planetCommand(c.getSource(), () -> PlanetClient.requestAdd(PlanetClient.DEFAULT_RADIUS)))
                                .then(argument("radius", IntegerArgumentType.integer(VoxelPlanet.MIN_RADIUS, VoxelPlanet.MAX_RADIUS))
                                        .executes(c -> planetCommand(c.getSource(),
                                                () -> PlanetClient.requestAdd(IntegerArgumentType.getInteger(c, "radius"))))))
                        .then(literal("tp").executes(c -> planetCommand(c.getSource(), PlanetClient::teleport)))
                        .then(literal("remove").executes(c -> planetCommand(c.getSource(), PlanetClient::remove)))
                        .executes(c -> planetCommand(c.getSource(), () -> {})))));
    }

    private static boolean settingsRequested;

    /** While a host is linked, Minecraft renders a transparent overlay and exports it. */
    public static boolean exportingOverlay() {
        return bridge != null && bridge.linked();
    }

    /** Render thread, before each frame: apply the host's keyboard and mouse. */
    public static void onFrameStart() {
        if (bridge == null) return;
        // One frame per emulated frame, right after it: SMG2 takes a new look every frame, not two
        // in one and none in the next as two free-running 60 Hz clocks drift past each other.
        if (following || walking() && bridge.gameLinked()) hostFrame = bridge.awaitFrame(hostFrame, FRAME_WAIT_MS);
        Minecraft mc = Minecraft.getInstance();
        float pt = mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
        // Minecraft movement: Mario goes where the player is, and Steve is drawn there outside first person.
        boolean walker = walking() && frame != null && mc.player != null && bridge.gameLinked();
        PlanetClient.frame(bridge, pt, walker ? frame.toGal(vec(mc.player.getPosition(pt))) : null,
                walker && view() != View.FIRST ? frame : null);
        SkinClient.frame(bridge);
        bridge.input().ifPresentOrElse(in -> input.apply(Minecraft.getInstance(), in), input::reset);
        bridge.pointer().ifPresent(p -> input.applyPointer(Minecraft.getInstance(), p));
        bridge.text().ifPresentOrElse(t -> input.applyText(Minecraft.getInstance(), t), input::resetText);
    }

    /** Render thread, after the GUI: publish the frame for Dolphin to composite. */
    public static void onFrameEnd(RenderTarget target) {
        if (bridge == null) return;
        bridge.segment().ifPresentOrElse(seg -> {
            if (exporter == null) exporter = new OverlayExporter(seg);
            exporter.capture(target);
        }, () -> exporter = null);
    }

    /** The local player's galaxy position, if linked and the frame is set up. */
    public static Optional<Vector3d> galaxyPos() {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null || frame == null || !bridge.linked()) return Optional.empty();
        return Optional.of(frame.toGal(vec(player.position())));
    }

    /** The galaxy's "up" at the player (opposite of its gravity), if linked. */
    public static Optional<Vector3d> galaxyUp() {
        if (Minecraft.getInstance().player == null || frame == null || !bridge.linked()) return Optional.empty();
        return Optional.of(frame.upGal());
    }

    /** Where a look of this yaw and pitch points in the galaxy, if linked. */
    public static Optional<Vector3d> galaxyLook(float yaw, float pitch) {
        if (frame == null || !bridge.linked()) return Optional.empty();
        return Optional.of(frame.dirToGal(LookMath.direction(yaw, pitch)));
    }

    /** The gravity frame (tests). */
    public static GravityFrame frame() {
        return frame;
    }

    public static boolean linked() {
        return bridge.linked();
    }

    /** Minecraft movement chosen (the player walks on its own, Mario goes with it). */
    public static boolean walking() {
        return GalaxyOptions.MOVEMENT.get() == Movement.MINECRAFT;
    }

    /** F6: Mario's movement or Minecraft's. */
    static void toggleMovement() {
        GalaxyOptions.MOVEMENT.set(GalaxyOptions.MOVEMENT.get().other());
        say("movement: " + GalaxyOptions.MOVEMENT.get().label() + " (F6 to switch)");
    }

    public static boolean flying() {
        return flying;
    }

    /** /fly: free flight on or off. */
    static void toggleFlying() {
        flying = !flying;
        say(flying ? "flying (/fly again to stop)" : walking() ? "walking" : "back with Mario");
    }

    /**
     * Minecraft movement: the player to a galaxy point (a teleport), held there until the ground
     * under it has its collision.
     */
    static void moveTo(Vector3d gal) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null || frame == null || following) return;
        Vector3d mc = frame.toMc(gal);
        player.setPos(mc.x, mc.y, mc.z);
        player.setDeltaMovement(Vec3.ZERO);
        player.setOldPosAndRot();
        settleTicks = SETTLE_TICKS;
    }

    /** SMG2's + button, held for a moment: its own pause menu opens (or closes). */
    static void pressPlus() {
        plusTicks = PLUS_TICKS;
    }

    /** A line in the chat, from GalaxyCraft. */
    static void say(String text) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player != null) player.sendSystemMessage(Component.literal("GalaxyCraft: " + text));
    }

    private static void resetFrame() {
        frame = null;
        camOffsetGal = camLookGal = camUpGal = null;
        GalaxyCraft.FIELD.setFrame(null);
    }

    private static void beforeTick(Minecraft client) {
        bridge.poll();
        LocalPlayer player = client.player;
        if (player == null) return;
        following = false;
        followTarget = null;
        if (!bridge.gameLinked()) {
            // Host gone, or the game handed back to the Wii Remote (Dolphin's link toggle): stay
            // frozen in the last frame instead of falling through a galaxy nobody updates.
            hold(player, frame != null);
            return;
        }
        Optional<Seqlock.WorldState> world = bridge.world();
        if (world.isEmpty()) return;
        Vector3d gravity = world.get().gravity();
        Vector3d pos = vec(player.position());
        if (frame == null) {
            // Wait for the host to say where the player is, somewhere with gravity: SMG2's title
            // screen has a Mario too, but nothing to stand on.
            hold(player, true);
            if (!world.get().anchor() || !world.get().hasGravity()) return;
            frame = new GravityFrame(world.get().queryPos(), pos, gravity);
            frameScene = world.get().sceneId();
            settleTicks = SETTLE_TICKS;
            GalaxyCraft.LOG.info("Linked to galaxy at {}", world.get().queryPos());
        } else {
            // Look and velocity are left alone in Minecraft space, so they turn with the frame
            // (parallel transport): walking keeps hugging the planet, as Mario's momentum does.
            if (flying) {
                // Flying: up turns (smoothly) to the galaxy's +Y and stays there, whatever pulls.
                Vector3d up = GravityFrame.limitTurn(frame.upGal(), GALAXY_UP, MAX_TURN_PER_TICK);
                frame.update(up.negate(), pos);
            } else if (world.get().follow() && world.get().hasGravity() && !walking()) {
                // Nobody walks by Minecraft's physics here, so the frame may lag the gravity a
                // little: the camera's up turns smoothly instead of snapping at planet edges.
                Vector3d up = GravityFrame.limitTurn(frame.upGal(), new Vector3d(gravity).normalize().negate(),
                        MAX_TURN_PER_TICK);
                frame.update(up.negate(), pos);
            } else {
                // Minecraft movement: the player walks by Minecraft's physics, so its up is the
                // gravity's right away (gravity where Mario is, and Mario goes where it is).
                frame.update(gravity, pos);
            }
        }
        following = world.get().follow() && !flying && !walking();
        fly(player);
        if (following) {
            // Mario mode: SMG2 moves Mario (played on the emulated Wii Remote); the player is
            // carried along at his feet, never walking or falling by Minecraft's physics. The move
            // waits for the player's tick, after its old position is saved: Steve is drawn
            // in between and his legs swing with Mario's steps.
            Vector3d target = Follow.target(frame, world.get().queryPos());
            Optional<Vector3d> rebased = frame.rebase(target);
            if (rebased.isPresent()) {
                Vector3d np = rebased.get();
                player.setPos(np.x, np.y, np.z);
                player.setOldPosAndRot();
            } else {
                followTarget = target;
            }
        } else {
            frame.rebase(vec(player.position())).ifPresent(np -> {
                player.setPos(np.x, np.y, np.z);
                player.setOldPosAndRot();
            });
        }
        GalaxyCraft.FIELD.setFrame(frame);
        PlanetClient.tick(player, bridge, frame, world.get());
        HeldClient.tick(player, bridge, world.get().sceneId());
        if (following) {
            settleTicks = 0;
            hold(player, true);
            return;
        }

        // A new link resends the scene's collision, which can take a few ticks: hold the player
        // until there is ground under them, or they fall through it before it exists.
        if (settleTicks > 0) {
            Vec3 feet = player.position();
            settleTicks = GalaxyCraft.FIELD.hasGroundBelow(new double[] {feet.x, feet.y, feet.z},
                    SETTLE_DEPTH_BLOCKS) ? 0 : settleTicks - 1;
        }
        hold(player, settleTicks > 0);
    }

    /** Creative flight while /fly is on (set every tick: the server may resend the abilities). */
    private static void fly(LocalPlayer player) {
        var abilities = player.getAbilities();
        if (flying) {
            abilities.mayfly = true;
            abilities.flying = true;
            player.resetFallDistance();
        } else if (abilities.flying || abilities.mayfly) {
            abilities.flying = false;
            abilities.mayfly = false;
        }
    }

    /** Freezes the player in place (no gravity, no momentum, no fall damage pending) or lets go. */
    private static void hold(LocalPlayer player, boolean on) {
        if (on) {
            player.setNoGravity(true);
            player.setDeltaMovement(Vec3.ZERO);
            player.resetFallDistance();
        } else if (holding) {
            player.setNoGravity(false);
            player.resetFallDistance();
        }
        holding = on;
    }

    private static void afterTick(Minecraft client) {
        if (plusTicks > 0) plusTicks--;
        sendPose(client);
    }

    /** Start of the local player's own tick: Mario mode moves the player here. */
    public static void onPlayerTick(LocalPlayer player) {
        if (followTarget != null) {
            player.setPos(followTarget.x, followTarget.y, followTarget.z);
            followTarget = null;
        }
    }

    /** F5: Minecraft's camera type after current; in Mario mode FRONT goes on to the Galaxy view. */
    public static CameraType cycleCamera(CameraType current) {
        View next = View.of(current.ordinal(), galaxyView).next(following);
        galaxyView = next == View.GALAXY;
        return CameraType.values()[next.cameraTypeOrdinal()];
    }

    public static View view() {
        return View.of(Minecraft.getInstance().options.getCameraType().ordinal(), galaxyView && following);
    }

    /** Render thread, the camera placed: SMG2 copies it (outside the Galaxy view), sent right away. */
    public static void onCameraAligned(Vector3d cameraMc, Vector3d forwardMc, Vector3d upMc, Vector3d feetMc,
            float partialTicks) {
        if (frame == null || !bridge.linked()) return;
        if (flying) {
            // SMG2 places its camera from Mario's feet: the offset is from him, however far.
            Optional<Seqlock.WorldState> w = bridge.world();
            camOffsetGal = w.isPresent() ? frame.toGal(cameraMc).sub(w.get().queryPos()) : null;
            camLookGal = frame.dirToGal(forwardMc, partialTicks);
            camUpGal = frame.dirToGal(upMc, partialTicks);
        } else if (view() != View.GALAXY) {
            camOffsetGal = CameraMath.offsetGal(frame, cameraMc, feetMc, partialTicks);
            camLookGal = frame.dirToGal(forwardMc, partialTicks);
            camUpGal = frame.dirToGal(upMc, partialTicks);
        }
        sendPose(Minecraft.getInstance());
    }

    private static void sendPose(Minecraft client) {
        LocalPlayer player = client.player;
        if (player == null || frame == null || !bridge.linked()) return;
        float eye = (float) (player.getEyeHeight() / GravityFrame.SCALE);
        Vector3d look = camLookGal != null ? camLookGal
                : frame.dirToGal(LookMath.direction(player.getYRot(), player.getXRot()));
        Vector3d up = camUpGal != null ? camUpGal : frame.upGal();
        Vector3d offset = camOffsetGal != null ? camOffsetGal : frame.upGal().mul(eye);
        bridge.sendPlayer(new Seqlock.PlayerOut(++frameId, frame.toGal(vec(player.position())), look, up,
                client.options.fov().get().floatValue(), eye, player.onGround(), offset, view().protocolId(), frameScene,
                PlanetClient.itemActive(player), client.gui.screen() != null, flying,
                client.debugEntries.isCurrentlyEnabled(DebugScreenEntries.ENTITY_HITBOXES), walking(), plusTicks > 0));
    }

    public static void camLog(Vector3d eyeMc, Vector3d backMc, double dist, double hit) {
        LocalPlayer p = Minecraft.getInstance().player;
        String mario = bridge.world().map(w -> {
            Vector3d eyeGal = frame == null ? new Vector3d() : frame.toGal(eyeMc);
            Vector3d feetGal = frame == null ? new Vector3d() : frame.toGal(vec(p.position()));
            return String.format("marioToSteveFeet=%.1f eyeAboveMario=%.1f mario=%.0f,%.0f,%.0f", feetGal.distance(w.queryPos()),
                    new Vector3d(eyeGal).sub(w.queryPos()).dot(frame.upGal()), w.queryPos().x, w.queryPos().y, w.queryPos().z);
        }).orElse("-");
        System.out.printf("[camlog] t=%d dist=%.2f hit=%.3f yaw=%.1f pitch=%.1f %s%n", System.nanoTime() / 1000000,
                dist, hit, p.getYRot(), p.getXRot(), mario);
    }

    private static int planetCommand(net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource source,
            Runnable action) {
        action.run();
        source.sendFeedback(Component.literal("GalaxyCraft: " + PlanetClient.status()));
        return 1;
    }

    private static String status(LocalPlayer player) {
        if (!bridge.linked()) return "GalaxyCraft: not linked (is fake_galaxy.py or Dolphin running?)";
        if (frame == null || player == null) return "GalaxyCraft: linked, waiting for player";
        Vector3d gal = frame.toGal(vec(player.position()));
        Vector3d up = frame.upGal();
        return String.format("GalaxyCraft: linked | galaxy pos (%.0f, %.0f, %.0f) | up (%.2f, %.2f, %.2f) | on ground %s",
                gal.x, gal.y, gal.z, up.x, up.y, up.z, player.onGround());
    }

    private static Vector3d vec(Vec3 v) {
        return new Vector3d(v.x, v.y, v.z);
    }
}
