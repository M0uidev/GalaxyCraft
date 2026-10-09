package dev.moui.galaxycraft.client;

import dev.moui.galaxycraft.GalaxyCraft;
import dev.moui.galaxycraft.play.PlayConfig;
import dev.moui.galaxycraft.proto.Layout;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;

/**
 * F2 while Dolphin shows the game: Minecraft's own framebuffer is blank (the game is Dolphin's),
 * so Dolphin takes the picture (its dev control channel, the "shot" command) and it is copied
 * into Minecraft's screenshots folder, with the usual chat message.
 */
public final class HostScreenshot {
    private static final long WAIT_MS = 8000;
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH.mm.ss");

    private HostScreenshot() {}

    /** True if Dolphin takes this screenshot (vanilla's must then not run). */
    public static boolean take(Minecraft mc) {
        if (!GalaxyCraftClient.exportingOverlay()) return false;
        String name = "mc_" + System.nanoTime();
        Path shmDir = Path.of(Layout.SHM_DIR);
        try {
            Path tmp = shmDir.resolve("galaxycraft_ctl.f2tmp");
            Files.writeString(tmp, "shot " + name + "\n");
            Files.move(tmp, shmDir.resolve("galaxycraft_ctl"), StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            GalaxyCraft.LOG.warn("[Screenshot] cannot reach Dolphin: {}", e.toString());
            return false;
        }
        File gameDir = mc.gameDirectory;
        List<Path> dolphinDirs = dolphinDirs(gameDir);
        Thread t = new Thread(() -> finish(mc, name, dolphinDirs, gameDir), "galaxycraft-screenshot");
        t.setDaemon(true);
        t.start();
        return true;
    }

    /** Where this Dolphin may keep its ScreenShots folder: play.json's -u, and next to Minecraft's folder. */
    private static List<Path> dolphinDirs(File gameDir) {
        List<Path> dirs = new ArrayList<>();
        Path play = PlayConfig.dataDir(System.getenv(), Map.of("user.home", System.getProperty("user.home", "")),
                System.getProperty("os.name", "").toLowerCase().contains("win")).resolve("play.json");
        PlayConfig.read(play).ifPresent(c -> {
            List<String> a = c.args();
            for (int i = 0; i + 1 < a.size(); i++) if (a.get(i).equals("-u")) dirs.add(Path.of(a.get(i + 1)));
        });
        File parent = gameDir.getAbsoluteFile().getParentFile();
        if (parent != null) {
            dirs.add(parent.toPath().resolve("dolphin"));
            dirs.add(parent.toPath().resolveSibling("galaxycraft-dev"));
        }
        return dirs;
    }

    private static void finish(Minecraft mc, String name, List<Path> dolphinDirs, File gameDir) {
        long deadline = System.currentTimeMillis() + WAIT_MS;
        try {
            while (System.currentTimeMillis() < deadline) {
                for (Path d : dolphinDirs) {
                    Path found = find(d.resolve("ScreenShots"), name + ".png");
                    if (found == null) continue;
                    Thread.sleep(150); // Dolphin has closed the file by then
                    Path out = target(gameDir.toPath().resolve("screenshots"));
                    Files.copy(found, out, StandardCopyOption.REPLACE_EXISTING);
                    Files.deleteIfExists(found);
                    File file = out.toFile();
                    Component link = Component.literal(file.getName()).withStyle(s ->
                            s.withUnderlined(true).withClickEvent(new ClickEvent.OpenFile(file.getAbsoluteFile())));
                    mc.execute(() -> mc.showDebugChat(Component.translatable("screenshot.success", link)));
                    return;
                }
                Thread.sleep(100);
            }
            mc.execute(() -> mc.showDebugChat(Component.translatable("screenshot.failure", "Dolphin did not save the picture")));
        } catch (IOException | InterruptedException e) {
            GalaxyCraft.LOG.warn("[Screenshot] {}", e.toString());
            mc.execute(() -> mc.showDebugChat(Component.translatable("screenshot.failure", e.toString())));
        }
    }

    private static Path find(Path root, String file) throws IOException {
        if (!Files.isDirectory(root)) return null;
        try (Stream<Path> s = Files.find(root, 3, (p, a) -> a.isRegularFile() && p.getFileName().toString().equals(file))) {
            return s.findFirst().orElse(null);
        }
    }

    private static Path target(Path dir) throws IOException {
        Files.createDirectories(dir);
        String stamp = LocalDateTime.now().format(STAMP);
        Path p = dir.resolve(stamp + ".png");
        for (int i = 1; Files.exists(p); i++) p = dir.resolve(stamp + "_" + i + ".png");
        return p;
    }
}
