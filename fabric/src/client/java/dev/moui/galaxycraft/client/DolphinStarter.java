package dev.moui.galaxycraft.client;

import dev.moui.galaxycraft.GalaxyCraft;
import dev.moui.galaxycraft.play.PlayConfig;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import net.minecraft.client.Minecraft;

/**
 * Minecraft started by Mojang's launcher (profile "Super Minecraft Galaxy", -Dgalaxycraft.startDolphin=true):
 * starts Dolphin as play.json says, and closes each side with the other. Our own PLAY and tools/gxplay.sh
 * start both themselves and never set the flag.
 */
final class DolphinStarter {
    private static volatile Process dolphin;
    /** Dolphin could not start: our screen stays up (the title screen would replace it otherwise). */
    private static boolean needInstall;

    private DolphinStarter() {}

    static boolean wanted() {
        return Boolean.getBoolean("galaxycraft.startDolphin");
    }

    /** Starts Dolphin; false (and a screen saying what to do) if play.json is missing or Dolphin does not start. */
    static boolean start(Minecraft client) {
        Path file = PlayConfig.dataDir(System.getenv(), Map.of("user.home", System.getProperty("user.home", "")),
                System.getProperty("os.name", "").toLowerCase().contains("win")).resolve("play.json");
        Optional<PlayConfig> cfg = PlayConfig.read(file);
        if (cfg.isEmpty()) {
            GalaxyCraft.LOG.error("[Dolphin] {} is missing or unreadable: not starting Dolphin", file);
            needInstall = true;
            return false;
        }
        PlayConfig c = cfg.get();
        ProcessBuilder pb = new ProcessBuilder(c.command()).redirectErrorStream(true);
        if (c.cwd() != null) pb.directory(Path.of(c.cwd()).toFile());
        pb.environment().putAll(c.env());
        try {
            Process p = pb.start();
            dolphin = p;
            Runtime.getRuntime().addShutdownHook(new Thread(DolphinStarter::stop, "galaxycraft-dolphin-stop"));
            Thread log = new Thread(() -> {
                try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                    for (String line; (line = r.readLine()) != null; ) GalaxyCraft.LOG.info("[Dolphin] {}", line);
                } catch (Exception ignored) { /* closed */ }
            }, "galaxycraft-dolphin-log");
            log.setDaemon(true);
            log.start();
            // Dolphin closed (its window, or a crash): Minecraft goes too.
            p.onExit().thenRun(() -> {
                GalaxyCraft.LOG.info("[Dolphin] exited with {}", p.exitValue());
                if (dolphin == p) client.execute(client::stop);
            });
            return true;
        } catch (Exception e) {
            GalaxyCraft.LOG.error("[Dolphin] could not start {}", c.cmd(), e);
            needInstall = true;
            return false;
        }
    }

    /** Each tick: keeps the "press INSTALL" screen up while Dolphin could not start. */
    static void keepScreen(Minecraft client) {
        if (needInstall && !(client.gui.screen() instanceof NeedInstallScreen)) client.gui.setScreen(new NeedInstallScreen());
    }

    /** Dolphin and everything it started. */
    static void stop() {
        Process p = dolphin;
        dolphin = null;
        if (p == null || !p.isAlive()) return;
        p.descendants().forEach(ProcessHandle::destroy);
        p.destroy();
    }
}
