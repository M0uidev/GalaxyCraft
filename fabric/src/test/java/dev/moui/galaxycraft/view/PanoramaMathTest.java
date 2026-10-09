package dev.moui.galaxycraft.view;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.image.BufferedImage;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

class PanoramaMathTest {
    private static final int[] COLORS = {0xFF0000, 0x00FF00, 0x0000FF, 0xFFFF00, 0xFF00FF, 0x00FFFF};

    private static BufferedImage[] solidFaces() {
        BufferedImage[] f = new BufferedImage[6];
        for (int i = 0; i < 6; i++) {
            f[i] = new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB);
            for (int y = 0; y < 8; y++) for (int x = 0; x < 8; x++) f[i].setRGB(x, y, COLORS[i]);
        }
        return f;
    }

    @Test void facesAreOrthonormalAndOrderedLikeMinecraft() {
        for (int i = 0; i < 6; i++) {
            assertEquals(1, PanoramaMath.look(i).length(), 1e-9);
            assertEquals(0, PanoramaMath.look(i).dot(PanoramaMath.up(i)), 1e-9);
        }
        assertEquals(new Vector3d(0, 0, -1), PanoramaMath.look(0));
        assertEquals(new Vector3d(1, 0, 0), PanoramaMath.look(1)); // right
        assertEquals(new Vector3d(0, 0, 1), PanoramaMath.look(2));
        assertEquals(new Vector3d(-1, 0, 0), PanoramaMath.look(3));
        assertEquals(new Vector3d(0, 1, 0), PanoramaMath.look(4));
        assertEquals(new Vector3d(0, -1, 0), PanoramaMath.look(5));
    }

    @Test void equirectShowsEachFaceWhereItBelongs() {
        BufferedImage e = PanoramaMath.equirect(solidFaces(), 64); // 128 x 64
        assertEquals(COLORS[0], e.getRGB(64, 32) & 0xFFFFFF);  // centre: front
        assertEquals(COLORS[1], e.getRGB(96, 32) & 0xFFFFFF);  // a quarter right: right
        assertEquals(COLORS[2], e.getRGB(1, 32) & 0xFFFFFF);   // edge: back
        assertEquals(COLORS[3], e.getRGB(32, 32) & 0xFFFFFF);  // a quarter left: left
        assertEquals(COLORS[4], e.getRGB(64, 1) & 0xFFFFFF);   // top: up
        assertEquals(COLORS[5], e.getRGB(64, 62) & 0xFFFFFF);  // bottom: down
    }

    @Test void gradientOnTheFrontFaceRunsLeftToRightAndTopToBottom() {
        BufferedImage[] f = solidFaces();
        for (int y = 0; y < 8; y++) for (int x = 0; x < 8; x++) f[0].setRGB(x, y, (x * 30) << 16 | (y * 30) << 8);
        BufferedImage e = PanoramaMath.equirect(f, 64);
        int left = e.getRGB(56, 32), right = e.getRGB(72, 32), top = e.getRGB(64, 24), bottom = e.getRGB(64, 40);
        assertTrue(((right >> 16) & 255) > ((left >> 16) & 255));
        assertTrue(((bottom >> 8) & 255) > ((top >> 8) & 255));
    }

    @Test void centreSquareOfAWideShot() {
        BufferedImage wide = new BufferedImage(16, 9, BufferedImage.TYPE_INT_RGB);
        wide.setRGB(7, 4, 0x123456); // the middle of a 16 x 9 image: column 7, row 4 is the square's middle
        BufferedImage sq = PanoramaMath.centreSquare(wide);
        assertEquals(9, sq.getWidth());
        assertEquals(9, sq.getHeight());
        assertEquals(0x123456, sq.getRGB(4, 4) & 0xFFFFFF);
    }
}
