package dev.moui.galaxycraft.client;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal;

import dev.moui.galaxycraft.GalaxyCraft;
import dev.moui.galaxycraft.bridge.BridgeClient;
import dev.moui.galaxycraft.gravity.Follow;
import dev.moui.galaxycraft.gravity.GravityFrame;
import dev.moui.galaxycraft.gravity.LookMath;
import dev.moui.galaxycraft.proto.Layout;
import dev.moui.galaxycraft.proto.Seqlock;
import java.nio.file.Path;
import java.util.Optional;
import com.mojang.blaze3d.pipeline.RenderTarget;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
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
    /** Ticks left to wait for the ground under a freshly linked player (then let go anyway). */
    private static final int SETTLE_TICKS = 60;
    private static final double SETTLE_DEPTH_BLOCKS = 6;
    private static int settleTicks;
    private static boolean holding;

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
                }))));
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
        GalaxyCraft.FIELD.setFrame(null);
    }

    private static void beforeTick(Minecraft client) {
        bridge.poll();
        LocalPlayer player = client.player;
        if (player == null) return;
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
            settleTicks = SETTLE_TICKS;
            GalaxyCraft.LOG.info("Linked to galaxy at {}", world.get().queryPos());
        } else {
            // Look and velocity are left alone in Minecraft space, so they turn with the frame
            // (parallel transport): walking keeps hugging the planet, as Mario's momentum does.
            frame.update(gravity, pos);
        }
        if (world.get().follow()) {
            // Mario mode: SMG2 moves Mario (played on the emulated Wii Remote); the player is
            // carried along at his feet, never walking or falling by Minecraft's physics.
            Vector3d target = Follow.target(frame, world.get().queryPos());
            player.setPos(target.x, target.y, target.z);
            player.setOldPosAndRot();
        }
        frame.rebase(vec(player.position())).ifPresent(np -> {
            player.setPos(np.x, np.y, np.z);
            player.setOldPosAndRot();
        });
        GalaxyCraft.FIELD.setFrame(frame);
        if (world.get().follow()) {
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
        LocalPlayer player = client.player;
        if (player == null || frame == null || !bridge.linked()) return;
        Vector3d look = LookMath.direction(player.getYRot(), player.getXRot());
        bridge.sendPlayer(new Seqlock.PlayerOut(++frameId, frame.toGal(vec(player.position())),
                frame.dirToGal(look), frame.upGal(), client.options.fov().get().floatValue(),
                (float) (player.getEyeHeight() / GravityFrame.SCALE), player.onGround()));
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
