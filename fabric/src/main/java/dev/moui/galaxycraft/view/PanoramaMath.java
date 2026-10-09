package dev.moui.galaxycraft.view;

import java.awt.image.BufferedImage;
import org.joml.Vector3d;

/**
 * A panorama is six square shots at 90 degrees, in Minecraft's panorama_0..5 order: front, right,
 * back, left, up, down (Minecraft.grabPanoramixScreenshot's). Directions are in a frame whose
 * forward is -Z, up +Y and right +X, so right = look x up.
 */
public final class PanoramaMath {
    public static final int FACES = 6;
    private static final Vector3d F = new Vector3d(0, 0, -1), R = new Vector3d(1, 0, 0), Y = new Vector3d(0, 1, 0);
    /** Per face: look, up. The up and down faces keep the player's heading (the image's top is back / forward). */
    private static final Vector3d[][] BASIS = {
        {F, Y}, {R, Y}, {neg(F), Y}, {neg(R), Y}, {Y, neg(F)}, {neg(Y), F},
    };

    private PanoramaMath() {}

    private static Vector3d neg(Vector3d v) {
        return new Vector3d().sub(v); // not negate(): that makes -0.0
    }

    /** Where face {@code face} looks, in the frame of the player's heading. */
    public static Vector3d look(int face) {
        return new Vector3d(BASIS[face][0]);
    }

    public static Vector3d up(int face) {
        return new Vector3d(BASIS[face][1]);
    }

    /** The largest square, centred, of an image taller than or as tall as it is wide or the reverse. */
    public static BufferedImage centreSquare(BufferedImage img) {
        int s = Math.min(img.getWidth(), img.getHeight());
        return img.getSubimage((img.getWidth() - s) / 2, (img.getHeight() - s) / 2, s, s);
    }

    /** The six faces as one equirectangular image (width = 2 x height), the front in the middle. */
    public static BufferedImage equirect(BufferedImage[] faces, int height) {
        BufferedImage out = new BufferedImage(height * 2, height, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < height; y++) {
            double lat = Math.PI / 2 - Math.PI * (y + 0.5) / height;
            for (int x = 0; x < height * 2; x++) {
                double lon = Math.PI * 2 * (x + 0.5) / (height * 2) - Math.PI;
                double cl = Math.cos(lat);
                out.setRGB(x, y, sample(faces, Math.sin(lon) * cl, Math.sin(lat), -Math.cos(lon) * cl));
            }
        }
        return out;
    }

    /** The colour seen along (x, y, z): the face it leaves through, bilinear. */
    static int sample(BufferedImage[] faces, double x, double y, double z) {
        int face = 0;
        double best = -1, dot = 0;
        for (int i = 0; i < FACES; i++) {
            Vector3d l = BASIS[i][0];
            double d = x * l.x + y * l.y + z * l.z;
            if (d > best) {
                best = d;
                face = i;
            }
        }
        Vector3d l = BASIS[face][0], u = BASIS[face][1];
        Vector3d r = new Vector3d(l).cross(u);
        dot = best;
        double fu = (x * r.x + y * r.y + z * r.z) / dot, fv = (x * u.x + y * u.y + z * u.z) / dot;
        BufferedImage img = faces[face];
        int s = img.getWidth();
        double px = (fu + 1) / 2 * s - 0.5, py = (1 - fv) / 2 * s - 0.5;
        int x0 = (int) Math.floor(px), y0 = (int) Math.floor(py);
        double tx = px - x0, ty = py - y0;
        int a = texel(img, x0, y0), b = texel(img, x0 + 1, y0), c = texel(img, x0, y0 + 1), d = texel(img, x0 + 1, y0 + 1);
        int rgb = 0;
        for (int shift = 16; shift >= 0; shift -= 8) {
            double top = ((a >> shift) & 255) * (1 - tx) + ((b >> shift) & 255) * tx;
            double bottom = ((c >> shift) & 255) * (1 - tx) + ((d >> shift) & 255) * tx;
            rgb |= (int) Math.round(top * (1 - ty) + bottom * ty) << shift;
        }
        return rgb;
    }

    private static int texel(BufferedImage img, int x, int y) {
        int s = img.getWidth();
        return img.getRGB(Math.max(0, Math.min(s - 1, x)), Math.max(0, Math.min(s - 1, y)));
    }
}
