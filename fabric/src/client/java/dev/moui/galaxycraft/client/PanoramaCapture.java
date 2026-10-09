package dev.moui.galaxycraft.client;

import dev.moui.galaxycraft.GalaxyCraft;
import dev.moui.galaxycraft.view.PanoramaMath;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import javax.imageio.ImageIO;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import org.joml.Vector3d;

/**
 * F3+F2 or /panorama: six shots from the player's eyes, 90 degrees each (the camera each is sent
 * to SMG2 through the pose, GalaxyCraftClient.sendPose), saved as Minecraft's panorama_0..5.png and
 * as one equirectangular picture. The player stands still and the HUD is hidden meanwhile.
 */
public final class PanoramaCapture {
    /** The face being shot (PanoramaMath's order), -1 when no capture runs. */
    private static volatile int face = -1;
    /** Where the player looked when it began: the front face looks that way, level. */
    private static volatile float yaw;

    private static final long FIRST_SETTLE_MS = 1500, SETTLE_MS = 900, SHOT_WAIT_MS = 8000;
    private static final int EQUIRECT_MAX_HEIGHT = 2048;
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH.mm.ss");

    private PanoramaCapture() {}

    static boolean active() {
        return face >= 0;
    }

    /** The camera of the face being shot, in Minecraft space: {look, up}. */
    static Vector3d[] camera() {
        double a = Math.toRadians(yaw);
        Vector3d forward = new Vector3d(-Math.sin(a), 0, Math.cos(a)); // Minecraft yaw 0 looks south (+Z)
        Vector3d y = new Vector3d(0, 1, 0);
        Vector3d right = new Vector3d(forward).cross(y);
        return new Vector3d[] {map(PanoramaMath.look(face), forward, right), map(PanoramaMath.up(face), forward, right)};
    }

    /** A vector of PanoramaMath's frame (right +X, up +Y, forward -Z) in the player's heading. */
    private static Vector3d map(Vector3d v, Vector3d forward, Vector3d right) {
        return new Vector3d(right).mul(v.x).fma(v.y, new Vector3d(0, 1, 0)).fma(-v.z, forward);
    }

    /** True if a capture began (vanilla's F3+F2 must then not run). */
    public static boolean start(Minecraft mc) {
        if (!GalaxyCraftClient.exportingOverlay() || mc.level == null || mc.player == null) return false;
        if (active()) return true;
        if (mc.gui.screen() != null) {
            mc.showDebugChat(Component.literal("Panorama: close the menu first"));
            return true;
        }
        yaw = mc.player.getYRot();
        File gameDir = mc.gameDirectory;
        List<Path> dolphinDirs = HostScreenshot.dolphinDirs(gameDir);
        boolean hudWasHidden = mc.gui.hud.isHidden();
        if (!hudWasHidden) mc.gui.hud.toggle();
        mc.showDebugChat(Component.literal("Panorama: hold still..."));
        Thread t = new Thread(() -> run(mc, gameDir, dolphinDirs, hudWasHidden), "galaxycraft-panorama");
        t.setDaemon(true);
        t.start();
        return true;
    }

    private static void run(Minecraft mc, File gameDir, List<Path> dolphinDirs, boolean hudWasHidden) {
        Component result;
        try {
            Path dir = gameDir.toPath().resolve("screenshots").resolve("panorama_" + LocalDateTime.now().format(STAMP));
            Files.createDirectories(dir);
            BufferedImage[] faces = new BufferedImage[PanoramaMath.FACES];
            String run = Long.toString(System.nanoTime());
            for (int i = 0; i < faces.length; i++) {
                face = i;
                Thread.sleep(i == 0 ? FIRST_SETTLE_MS : SETTLE_MS);
                String name = "pano_" + run + "_" + i;
                if (!HostScreenshot.send(name)) throw new IOException("Dolphin is out of reach");
                Path shot = HostScreenshot.await(name, dolphinDirs, SHOT_WAIT_MS);
                if (shot == null) throw new IOException("Dolphin did not save face " + i);
                BufferedImage img = ImageIO.read(shot.toFile());
                Files.deleteIfExists(shot);
                if (img == null) throw new IOException("unreadable shot " + i);
                faces[i] = PanoramaMath.centreSquare(img);
                ImageIO.write(faces[i], "png", dir.resolve("panorama_" + i + ".png").toFile());
            }
            face = -1; // the player may move again while the picture is stitched
            int height = Math.min(faces[0].getWidth() * 2, EQUIRECT_MAX_HEIGHT);
            ImageIO.write(PanoramaMath.equirect(faces, height), "png", dir.resolve("equirect.png").toFile());
            File folder = dir.toFile();
            result = Component.literal("Panorama saved: ").append(Component.literal(folder.getName()).withStyle(s ->
                    s.withUnderlined(true).withClickEvent(new ClickEvent.OpenFile(folder.getAbsoluteFile()))));
        } catch (IOException | InterruptedException | RuntimeException e) {
            GalaxyCraft.LOG.warn("[Panorama] {}", e.toString());
            result = Component.literal("Panorama failed: " + e.getMessage());
        } finally {
            face = -1;
        }
        Component message = result;
        mc.execute(() -> {
            if (mc.gui.hud.isHidden() != hudWasHidden) mc.gui.hud.toggle();
            mc.showDebugChat(message);
        });
    }
}
