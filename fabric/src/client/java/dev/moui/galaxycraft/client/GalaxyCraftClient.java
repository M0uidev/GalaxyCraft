package dev.moui.galaxycraft.client;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal;

import dev.moui.galaxycraft.GalaxyCraft;
import dev.moui.galaxycraft.bridge.BridgeClient;
import dev.moui.galaxycraft.gravity.Follow;
import dev.moui.galaxycraft.gravity.GravityFrame;
import dev.moui.galaxycraft.gravity.LookMath;
import dev.moui.galaxycraft.proto.Layout;
import dev.moui.galaxycraft.proto.Seqlock;
import dev.moui.galaxycraft.view.CameraMath;
import dev.moui.galaxycraft.view.View;
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
    /** Where the player goes at the start of its own tick (after its old position is saved). */
    private static Vector3d followTarget;
    /** F5 went past THIRD_PERSON_FRONT: SMG2's own camera (Minecraft only draws the HUD). */
    private static boolean galaxyView;
    /** The camera as last drawn, galaxy space: what SMG2's camera copies outside the Galaxy view. */
    private static Vector3d camOffsetGal, camLookGal, camUpGal;

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
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> resetFrame());
        ClientLifecycleEvents.CLIENT_STARTED.register(client -> {
            if (Boolean.getBoolean("galaxycraft.hidden")) { // Dolphin shows the overlay instead
                client.options.pauseOnLostFocus = false;
                SDLVideo.SDL_HideWindow(client.getWindow().handle());
            }
        });
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, ctx) -> dispatcher.register(
                literal("galaxycraft").then(literal("status").executes(c -> {
                    c.getSource().sendFeedback(Component.literal(status(c.getSource().getPlayer())));
                    return 1;
                })).then(literal("planet")
                        .then(literal("spawn").executes(c -> planetCommand(c.getSource(), PlanetClient::requestSpawn)))
                        .then(literal("tp").executes(c -> planetCommand(c.getSource(), PlanetClient::teleport)))
                        .then(literal("remove").executes(c -> planetCommand(c.getSource(), PlanetClient::remove)))
                        .executes(c -> planetCommand(c.getSource(), () -> {})))));
    }

    /** While a host is linked, Minecraft renders a transparent overlay and exports it. */
    public static boolean exportingOverlay() {
        return bridge != null && bridge.linked();
    }

    /** Render thread, before each frame: apply the host's keyboard and mouse. */
    public static void onFrameStart() {
        if (bridge == null) return;
        bridge.input().ifPresentOrElse(in -> input.apply(Minecraft.getInstance(), in), input::reset);
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

    public static boolean linked() {
        return bridge.linked();
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
            if (world.get().follow() && world.get().hasGravity()) {
                // Nobody walks by Minecraft's physics here, so the frame may lag the gravity a
                // little: the camera's up turns smoothly instead of snapping at planet edges.
                Vector3d up = GravityFrame.limitTurn(frame.upGal(), new Vector3d(gravity).normalize().negate(),
                        MAX_TURN_PER_TICK);
                frame.update(up.negate(), pos);
            } else {
                frame.update(gravity, pos);
            }
        }
        following = world.get().follow();
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
        if (view() != View.GALAXY) {
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
                PlanetClient.itemActive(player)));
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
