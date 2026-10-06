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
import dev.moui.galaxycraft.view.CameraDistance;
import dev.moui.galaxycraft.view.CameraMath;
import dev.moui.galaxycraft.view.IntroCamera;
import dev.moui.galaxycraft.view.View;
import dev.moui.galaxycraft.voxel.PlanetSession;
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
    /**
     * Minecraft movement starts this far above where Mario stands, blocks: the galaxy's collision
     * reaches Minecraft as boxes on a 1/8-block grid whose tops may stand above the true ground,
     * and a player that starts inside a box falls through it.
     */
    private static final double WALK_LIFT = 0.25;
    /** At most this far (blocks) the player is pushed up (Minecraft's step) or sideways out of the galaxy's boxes. */
    private static final double PUSH_UP_MAX = 0.6, PUSH_SIDE_MAX = 0.25;
    /**
     * Minecraft movement: Steve's width, a share of the planet's block where he is. Cells narrow
     * toward the core (half a block and less deep down), and a 0.6-wide box does not fit a hole
     * dug one block wide there: it wedges, or is pushed out through the floor. Only the box is
     * narrower, as Mario's radius is (VoxelPlanet), never wider than Minecraft's 0.6.
     */
    private static final double STEVE_WIDTH = 0.6;
    private static double walkWidth = STEVE_WIDTH;
    /** Last tick's following: the tick Mario lets go, the player is lifted (WALK_LIFT). */
    private static boolean wasFollowing;
    private static final Vector3d GALAXY_UP = new Vector3d(0, 1, 0);
    /** The camera as last drawn, galaxy space: what SMG2's camera copies outside the Galaxy view. */
    private static Vector3d camOffsetGal, camLookGal, camUpGal;
    /**
     * Until when (System.nanoTime) SMG2's + button is held (the pause menu's SMG2 Menu): a quarter
     * of a second of real time, which the game sees however fast or slow Minecraft ticks.
     */
    private static long plusUntil = System.nanoTime();
    private static final long PLUS_NANOS = 250_000_000L;

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
        // A voxel planet's chunks arrive unrotated, placed at its center: their faces collide exactly.
        GalaxyCraft.FIELD.setBlockParts(m -> m[0] == 1 && m[5] == 1 && m[10] == 1 && m[1] == 0 && m[2] == 0
                && m[4] == 0 && m[6] == 0 && m[8] == 0 && m[9] == 0 && PlanetClient.planets().stream().anyMatch(
                        s -> s != null && s.center() != null && s.center().distance(m[3], m[7], m[11]) < 1));
        GalaxyCraft.FIELD.setBlockSource((f, q, out) -> {
            for (PlanetSession s : PlanetClient.planets())
                if (s != null && s.planet() != null && s.center() != null)
                    dev.moui.galaxycraft.voxel.PlanetCollision.boxes(s.planet(), s::galOf, s::localOf, f, q, out);
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
        // On the client's thread: DISCONNECT may come from the network's when the connection drops.
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> client.execute(() -> GalaxyWorlds.joined(client)));
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> client.execute(() -> {
            PlanetClient.leaveWorld(bridge);
            resetFrame();
        }));
        ClientLifecycleEvents.CLIENT_STARTED.register(client -> {
            if (Boolean.getBoolean("galaxycraft.hidden")) { // Dolphin shows the overlay instead
                client.options.pauseOnLostFocus = false;
                SDLVideo.SDL_HideWindow(client.getWindow().handle());
            }
        });
        GalaxyOptions.init();
        PauseMenu.register();
        TitleMenu.register();
        CreateWorldDefaults.register();
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
        if (following || ownPhysics() && bridge.gameLinked()) hostFrame = bridge.awaitFrame(hostFrame, FRAME_WAIT_MS);
        Minecraft mc = Minecraft.getInstance();
        float pt = mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
        // Minecraft movement: Mario goes where the player is, and Steve is drawn there outside first person.
        boolean walker = ownPhysics() && frame != null && mc.player != null && bridge.gameLinked();
        // The elytra in Mario's modes: Mario himself flies there (in his Launch Star pose), not Steve.
        boolean marioFlies = walker && !walking() && mc.player.isFallFlying() && view() != View.FIRST;
        PlanetClient.frame(bridge, pt, walker ? path.at(pt) : null,
                walker && view() != View.FIRST && !marioFlies ? frame : null, marioFlies);
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

    /** Super Mario Galaxy 2 behind Minecraft's menus: booting, ready (in GalaxyCraftSpace), or no Dolphin. */
    static String smg2Status() {
        if (!bridge.linked()) return "Super Mario Galaxy 2: Dolphin is not running";
        return bridge.spaceReady() || dev.moui.galaxycraft.proto.Layout.SPACE_STAGE.equals(bridge.stage()) ? "Super Mario Galaxy 2: ready"
                : "Super Mario Galaxy 2: starting...";
    }

    public static boolean linked() {
        return bridge.linked();
    }

    /** Minecraft's physics chosen (the player walks on its own, Mario goes with it). */
    /** The player is linked to the galaxy (its frame is up: Mario has gravity and the player follows). */
    static boolean linkedToGalaxy() {
        return frame != null;
    }

    public static boolean walking() {
        return GalaxyOptions.MOVEMENT.get() == Movement.MINECRAFT;
    }

    /**
     * The player moves by Minecraft's own physics and Mario goes with it: Minecraft's movement, or
     * a flight (the elytra, from take-off until landed) in any movement mode.
     */
    public static boolean ownPhysics() {
        return walking() || Flight.active();
    }

    /** Third person's eased distance (blocks, before the player's scale) and when it was last eased. */
    private static double camDistance = Double.NaN;
    private static long camDistanceNanos;

    /**
     * Third person: the distance Minecraft asks for (4 blocks times the player's scale) becomes the
     * one chosen for where the player is (walking, gliding, in space), eased between them.
     */
    public static float thirdPersonDistance(float vanilla) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null || frame == null) return vanilla;
        int target = Flight.inVoid() ? GalaxyOptions.CAMERA_DISTANCE_SPACE.get()
                : player.isFallFlying() ? GalaxyOptions.CAMERA_DISTANCE_GLIDING.get() : GalaxyOptions.CAMERA_DISTANCE.get();
        long now = System.nanoTime();
        camDistance = Double.isNaN(camDistance) ? target
                : CameraDistance.approach(camDistance, target, Math.min(1, (now - camDistanceNanos) / 1e9));
        camDistanceNanos = now;
        return (float) (camDistance * vanilla / 4);
    }

    /** Out of every planet's gravity (tests). */
    public static boolean inVoid() {
        return Flight.inVoid();
    }

    /** Minecraft's feel chosen: SMG2 moves Mario at Minecraft's speeds, with its jump. */
    public static boolean mcFeel() {
        return GalaxyOptions.MOVEMENT.get() == Movement.MARIO_MC;
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
    public static void moveTo(Vector3d gal) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null || frame == null || !walking()) return;
        Vector3d mc = frame.toMc(gal);
        player.setPos(mc.x, mc.y + WALK_LIFT, mc.z);
        player.setDeltaMovement(Vec3.ZERO);
        player.setOldPosAndRot();
        settleTicks = SETTLE_TICKS;
    }

    /** SMG2's + button, held for a moment: its own pause menu opens (or closes). */
    static void pressPlus() {
        plusUntil = System.nanoTime() + PLUS_NANOS;
    }

    /** A line in the chat, from GalaxyCraft. */
    static void say(String text) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player != null) player.sendSystemMessage(Component.literal("GalaxyCraft: " + text));
    }

    private static void resetFrame() {
        frame = null;
        path.reset();
        camOffsetGal = camLookGal = camUpGal = null;
        Flight.reset();
        GalaxyCraft.FIELD.setFrame(null);
    }

    /** The player's galaxy path between ticks: where Mario is seated at each frame (Minecraft movement). */
    private static final dev.moui.galaxycraft.gravity.GalPath path = new dev.moui.galaxycraft.gravity.GalPath();

    private static void beforeTick(Minecraft client) {
        bridge.setInWorld(client.level != null && client.player != null);
        bridge.poll();
        EnteringScreen.tick(client);
        PlanetClient.flushDropAll(bridge);
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
            if (!world.get().anchor() || !world.get().hasGravity() || PlanetClient.waitingToLand()) {
                // GalaxyCraftSpace: no gravity until the world's planet is up and Mario is on it (any
                // gravity before is the last stage's Mario's).
                if (PlanetClient.galaxy() != null) PlanetClient.tick(player, bridge, null, world.get());
                return;
            }
            frame = new GravityFrame(world.get().queryPos(), pos, gravity);
            frameScene = world.get().sceneId();
            settleTicks = SETTLE_TICKS;
            GalaxyCraft.LOG.info("Linked to galaxy at {}", world.get().queryPos());
        } else {
            frame.startTick();
            Flight.beforeFrame(player, world.get(), frame, world.get().follow() && !flying && !ownPhysics());
            boolean space = ownPhysics() && Flight.space(player, world.get(), frame);
            // Look and velocity are left alone in Minecraft space, so they turn with the frame
            // (parallel transport): walking keeps hugging the planet, as Mario's momentum does.
            if (flying) {
                // Flying: up turns (smoothly) to the galaxy's +Y and stays there, whatever pulls.
                Vector3d up = GravityFrame.limitTurn(frame.upGal(), GALAXY_UP, MAX_TURN_PER_TICK);
                frame.update(up.negate(), pos);
            } else if (Flight.active() && !player.onGround()) {
                // Elytra: up turns to the gravity that pulls, at a flight's pace; in the void
                // (no gravity) it stays as it was.
                // The look stays where it was in the galaxy: flying into a planet's pull turns up, not
                // where the player faces.
                if (!space) keepLook(player, frame.update(Flight.upToward(frame, gravity).negate(), pos));
            } else if (world.get().follow() && world.get().hasGravity() && !ownPhysics()) {
                // Nobody walks by Minecraft's physics here, so the frame may lag the gravity a
                // little: the camera's up turns smoothly instead of snapping at planet edges.
                Vector3d up = GravityFrame.limitTurn(frame.upGal(), new Vector3d(gravity).normalize().negate(),
                        MAX_TURN_PER_TICK);
                frame.update(up.negate(), pos);
            } else if (space) {
                // Minecraft movement out in space: up stays as it was.
            } else {
                // Minecraft movement: the player walks by Minecraft's physics, so its up is the
                // gravity's right away (gravity where Mario is, and Mario goes where it is). On a
                // voxel planet, the blocks' own up where the player is: SMG2's gravity there can
                // lean off it (deep down, by the core), and leaning blocks collide as wider boxes
                // that close one-wide shafts and tunnels.
                Vector3d[] grid = planetGrid(player);
                frame.update(grid != null ? new Vector3d(grid[2]).negate() : gravity, pos);
            }
        }
        following = world.get().follow() && !flying && !ownPhysics();
        if (wasFollowing && !following && walking()) {
            player.setPos(player.getX(), player.getY() + WALK_LIFT, player.getZ());
            player.setDeltaMovement(Vec3.ZERO);
        }
        wasFollowing = following;
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
            // The old position moves by the same shift: drawing between ticks stays smooth (a
            // straight flight through space, up frozen, rebases every couple of seconds).
            frame.rebase(vec(player.position())).ifPresent(np -> {
                double dy = np.y - player.getY();
                player.setPos(np.x, np.y, np.z);
                player.yo += dy;
                player.yOld += dy;
            });
            if (ownPhysics() && !flying && !Flight.active()) alignToBlocks(player);
        }
        fitWidth(player, ownPhysics() && !flying && !following);
        GalaxyCraft.FIELD.setFrame(frame);
        if (ownPhysics() && !flying && !following) {
            // The galaxy's boxes turned and snapped with the frame; one overlapping the player
            // would let it through (Minecraft passes a box it starts in): out by the least move.
            var b = player.getBoundingBox();
            Vector3d out = GalaxyCraft.FIELD.pushOut(new double[] {b.minX, b.minY, b.minZ, b.maxX, b.maxY, b.maxZ},
                    PUSH_UP_MAX, PUSH_SIDE_MAX);
            if (out.lengthSquared() > 0) {
                player.setPos(player.getX() + out.x, player.getY() + out.y, player.getZ() + out.z);
                player.xo += out.x;
                player.yo += out.y;
                player.zo += out.z;
                player.xOld += out.x;
                player.yOld += out.y;
                player.zOld += out.z;
            }
        }
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
        // Minecraft's physics: no gravity in the void (nothing to settle on there), the wind far out.
        if (ownPhysics() && !flying && Flight.afterFrame(player, world.get(), frame)) {
            settleTicks = 0;
            if (holding) hold(player, false);
            player.setNoGravity(true);
        }
    }

    /** After a re-aim of the frame: the look turned with it, so it points where it did in the galaxy. */
    private static void keepLook(LocalPlayer player, GravityFrame.Update u) {
        if (!u.rotated()) return;
        double[] t = LookMath.turned(player.getYRot(), player.getXRot(), u.deltaMc());
        float yaw = (float) t[0], pitch = (float) t[1];
        // Last tick's look stays as it was (LookMath.keptOld): drawing turns the frame between ticks.
        float dy = yaw - player.getYRot();
        player.setYRot(yaw);
        player.setXRot(pitch);
        player.yHeadRot += dy;
        player.yBodyRot += dy;
    }

    /**
     * Minecraft movement on a planet: Minecraft's X and Z run along the blocks where the player
     * is, and their edges fall on whole blocks. The player's box is square to Minecraft's axes, so
     * otherwise it meets a planet's blocks at an angle and the 1/8 grid they are collided on
     * narrows a one-block tunnel below its width; a two-high one below its height. The look and
     * the momentum keep their direction in the galaxy.
     */
    private static void alignToBlocks(LocalPlayer player) {
        Vector3d[] grid = planetGrid(player);
        if (grid != null) {
            GravityFrame.Align a = frame.alignGrid(grid[0], grid[1], vec(player.position()));
            float turn = (float) Math.toDegrees(a.yaw());
            player.setYRot(player.getYRot() - turn);
            player.yRotO -= turn;
            player.yHeadRot -= turn;
            player.yHeadRotO -= turn;
            player.yBodyRot -= turn;
            player.yBodyRotO -= turn;
            Vector3d v = new org.joml.Quaterniond().rotationY(a.yaw()).transform(vec(player.getDeltaMovement()));
            player.setDeltaMovement(v.x, v.y, v.z);
            Vec3 d = new Vec3(a.shift().x, a.shift().y, a.shift().z);
            player.setPos(player.position().add(d));
            player.xo += d.x;
            player.yo += d.y;
            player.zo += d.z;
            player.xOld += d.x;
            player.yOld += d.y;
            player.zOld += d.z;
        }
    }

    /** Steve's collision width now (Minecraft space): narrower deep in a planet (STEVE_WIDTH). */
    public static double walkWidth() {
        return walkWidth;
    }

    /**
     * Minecraft movement: Steve's box as wide as the block he is in allows (STEVE_WIDTH of a
     * block, the narrower of its two sides at its bottom). Narrowing is at once; widening (climbing
     * out) waits until the wider box overlaps nothing, so it never pushes him into a wall.
     */
    private static void fitWidth(LocalPlayer player, boolean on) {
        double want = STEVE_WIDTH;
        Vector3d[] grid = on ? planetGrid(player) : null;
        if (grid != null) {
            double block = Math.min(grid[0].length(), grid[3].length()) * GravityFrame.SCALE;
            want = Math.min(STEVE_WIDTH, STEVE_WIDTH * block);
        }
        if (Math.abs(want - walkWidth) < 1e-4) return;
        if (on && want > walkWidth) {
            Vec3 p = player.position();
            double h = want / 2, top = player.getBoundingBox().maxY;
            if (!GalaxyCraft.FIELD.boxesFor(new double[] {p.x - h, p.y + 0.01, p.z - h, p.x + h, top, p.z + h}).isEmpty()) return;
        }
        walkWidth = want;
        player.setPos(player.getX(), player.getY(), player.getZ()); // its box, at the new width
    }

    /** The block grid of the planet the player is in (PlanetSession.gridAt), if any. */
    private static Vector3d[] planetGrid(LocalPlayer player) {
        Vector3d feetGal = frame.toGal(vec(player.position().add(0, 0.5, 0)));
        for (PlanetSession s : PlanetClient.planets()) {
            if (s == null || s.planet() == null) continue;
            Vector3d[] grid = s.gridAt(feetGal);
            if (grid != null) return grid;
        }
        return null;
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
        if (frame != null && client.player != null) path.tick(frame.toGal(vec(client.player.position())));
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
            intro();
        }
        sendPose(Minecraft.getInstance());
    }

    /** Entering a world: the camera's zoom from space down to the player's view (IntroCamera), ms. */
    private static final long INTRO_MS = 3000;
    /** When the zoom started (nanoTime; 0: none), and whether the GUI was hidden before it. */
    private static long introStart;
    private static boolean guiWasHidden;

    /** The zoom from space starts now (EnteringScreen, once Mario stands on the planet). */
    static void startIntro() {
        Minecraft mc = Minecraft.getInstance();
        if (introStart == 0) guiWasHidden = mc.gui.hud.isHidden();
        if (!mc.gui.hud.isHidden()) mc.gui.hud.toggle(); // no hand or hotbar in the shot
        introStart = System.nanoTime();
    }

    /** During the zoom: the camera this frame on its way in, instead of the player's own. */
    private static void intro() {
        if (introStart == 0) return;
        double t = (System.nanoTime() - introStart) / (INTRO_MS * 1e6);
        if (t >= 1 || frame == null) {
            introStart = 0;
            var hud = Minecraft.getInstance().gui.hud;
            if (hud.isHidden() != guiWasHidden) hud.toggle();
            return;
        }
        PlanetSession on = PlanetClient.focus();
        double radius = on.active() ? on.planet().surface() : 48;
        double far = (radius * 1.5 + 48) / GravityFrame.SCALE; // the planet whole in view, galaxy units
        IntroCamera.Pose p = IntroCamera.at(t, frame.upGal(), camOffsetGal, camLookGal, camUpGal, far);
        camOffsetGal = p.offset();
        camLookGal = p.look();
        camUpGal = p.up();
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
                client.debugEntries.isCurrentlyEnabled(DebugScreenEntries.ENTITY_HITBOXES), ownPhysics(), System.nanoTime() - plusUntil < 0, mcFeel()));
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
