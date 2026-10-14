package dev.moui.galaxycraft.gametest;

import dev.moui.galaxycraft.net.GuestFolder;
import dev.moui.galaxycraft.net.MpServer;
import dev.moui.galaxycraft.voxel.GalaxyCatalog;
import dev.moui.galaxycraft.voxel.GalaxySave;
import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

/**
 * Multiplayer's connection, in the real game and without Dolphin, only with -Dgalaxycraft.mp=true
 * (./gradlew runClientGameTest -PgalaxycraftMp): this client hosts a world open to LAN and a second,
 * real Minecraft client (started with `gradlew runClient`) joins it by direct connection. The guest
 * must be told the host's galaxy, write it to its own folder and answer; then a guest of another
 * protocol version must be sent away with a reason. Prints "[GalaxyCraft mp] PASS" or throws.
 * What cannot be tried here: the players seeing each other (it needs both games' Dolphin).
 */
public final class MpProbe implements FabricClientGameTest {
    private static final long GUEST_WAIT_MS = 6 * 60_000;

    @Override
    public void runTest(ClientGameTestContext ctx) {
        if (!Boolean.getBoolean("galaxycraft.mp")) return;
        Path fabricDir = Path.of(System.getProperty("galaxycraft.repoRoot")).resolve("fabric");
        Path guestHome = fabricDir.resolve("build").resolve("mp-guest-data");
        deleteTree(guestHome);
        try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
            ctx.waitTicks(20);
            int port = freePort();
            GalaxySave.Galaxy[] hosted = {null};
            UUID[] worldId = {null};
            boolean published = sp.getServer().computeOnServer(server -> {
                try {
                    GalaxySave save = GalaxySave.of(server.getWorldPath(LevelResource.ROOT));
                    var entry = new GalaxyCatalog.Entry(0, 0, 0, 0, 48, GalaxyCatalog.Kind.GENERATED, "random", null, 7L);
                    save.writeGalaxy(new GalaxySave.Galaxy(1, GalaxyCatalog.Options.defaults(42), List.of(entry)));
                    hosted[0] = save.galaxy().orElseThrow();
                    worldId[0] = save.worldId();
                } catch (IOException e) {
                    throw new IllegalStateException(e);
                }
                server.setUsesAuthentication(false); // the probe's clients have no Mojang session
                return server.publishServer(MinecraftServer.MultiplayerScope.LAN, false, port);
            });
            if (!published) throw new AssertionError("the world could not be opened to LAN on port " + port);
            log("hosting on port " + port + ", galaxy " + worldId[0]);

            // 1. A guest of the same version enters the host's galaxy.
            Process guest = launchGuest(fabricDir, guestHome, "Guest1", port, null);
            try {
                waitFor(ctx, () -> MpServer.entered() >= 1, "the guest to answer Hello");
                int players = sp.getServer().computeOnServer(s -> s.getPlayerList().getPlayerCount());
                if (players != 2) throw new AssertionError("expected the host and one guest, found " + players);
                Path dataDir = guestHome.resolve("galaxycraft");
                var cached = GalaxySave.of(GuestFolder.of(dataDir, worldId[0].toString())).galaxy();
                if (cached.isEmpty() || !cached.get().equals(hosted[0]))
                    throw new AssertionError("the guest did not keep the host's galaxy in its folder: " + cached);
                log("guest entered and cached the host's galaxy");
            } finally {
                stop(guest);
            }
            waitFor(ctx, () -> sp.getServer().computeOnServer(s -> s.getPlayerList().getPlayerCount()) == 1, "the guest's leaving");

            // 2. A guest of another protocol version is sent away.
            int kicksBefore = MpServer.kicks();
            Process old = launchGuest(fabricDir, guestHome, "Guest2", port, String.valueOf(dev.moui.galaxycraft.net.Protocol.VERSION - 1));
            try {
                waitFor(ctx, () -> MpServer.kicks() > kicksBefore, "the out-of-date guest to be sent away");
                log("out-of-date guest was sent away");
            } finally {
                stop(old);
            }
            if (MpServer.entered() != 1) throw new AssertionError("the out-of-date guest counted as entered");
            log("PASS");
        } catch (RuntimeException e) {
            log("FAIL " + e);
            throw e;
        }
    }

    private static void log(String s) {
        System.out.println("[GalaxyCraft mp] " + s);
    }

    private static int freePort() {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** `gradlew runClient` with the guest's name and the host's address; its data folder apart from the player's. */
    private static Process launchGuest(Path fabricDir, Path guestHome, String name, int port, String version) {
        String script = System.getProperty("os.name").startsWith("Windows") ? "gradlew.bat" : "./gradlew";
        List<String> cmd = new java.util.ArrayList<>(List.of(script, "runClient", "--console=plain",
                "--args=--username " + name + " --quickPlayMultiplayer localhost:" + port));
        cmd.add("-PgalaxycraftHidden"); // no window on the player's desktop
        // Gradle clears the host test's run folder (its world) before any client run: not this one. And the guest has its own game folder.
        cmd.add("-x");
        cmd.add("deleteGameTestRunDir");
        cmd.add("-PgalaxycraftGameDir=" + guestHome.resolve("minecraft"));
        try { // a new game folder starts with Minecraft's accessibility screen, which would hold the quick join back
            java.nio.file.Files.createDirectories(guestHome.resolve("minecraft"));
            java.nio.file.Files.writeString(guestHome.resolve("minecraft").resolve("options.txt"), "onboardAccessibility:false\n");
        } catch (IOException e) {
            throw new IllegalStateException("could not prepare the guest's folder: " + e, e);
        }
        if (version != null) cmd.add("-PmpGuestVersion=" + version);
        ProcessBuilder pb = new ProcessBuilder(cmd).directory(fabricDir.toFile()).redirectErrorStream(true);
        pb.environment().put("XDG_DATA_HOME", guestHome.toString());
        pb.redirectOutput(fabricDir.resolve("build").resolve("mp-" + name + ".log").toFile());
        try {
            return pb.start();
        } catch (IOException e) {
            throw new IllegalStateException("could not start the guest: " + e, e);
        }
    }

    /** Ends the guest's Gradle and the game it started. */
    private static void stop(Process p) {
        p.descendants().forEach(ProcessHandle::destroy);
        p.destroy();
    }

    private static void waitFor(ClientGameTestContext ctx, BooleanSupplier done, String what) {
        long end = System.currentTimeMillis() + GUEST_WAIT_MS;
        while (!done.getAsBoolean()) {
            if (System.currentTimeMillis() > end) throw new AssertionError("timed out waiting for " + what);
            ctx.waitTicks(20);
        }
    }

    private static void deleteTree(Path dir) {
        if (!java.nio.file.Files.exists(dir)) return;
        try (var walk = java.nio.file.Files.walk(dir)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        } catch (IOException ignored) { /* a leftover is overwritten */ }
    }
}
