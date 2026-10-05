package dev.moui.galaxycraft.view;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class SkinImageTest {
    private static String session(String textures) {
        String value = Base64.getEncoder().encodeToString(textures.getBytes(StandardCharsets.UTF_8));
        return "{\"id\":\"069a79f444e94726a5befca90e38aaf5\",\"name\":\"Notch\",\"properties\":[{\"name\":\"textures\",\"value\":\""
                + value + "\"}]}";
    }

    @Test void readsMojangsAnswers() {
        assertEquals("069a79f444e94726a5befca90e38aaf5",
                SkinImage.profileId("{\"id\":\"069a79f444e94726a5befca90e38aaf5\",\"name\":\"Notch\"}").orElseThrow());
        assertTrue(SkinImage.profileId("not json").isEmpty());
        var wide = SkinImage.textures(session("{\"textures\":{\"SKIN\":{\"url\":\"http://textures.minecraft.net/texture/abc\"}}}"));
        assertEquals(new SkinImage.Textures("http://textures.minecraft.net/texture/abc", false), wide.orElseThrow());
        var slim = SkinImage.textures(session(
                "{\"textures\":{\"SKIN\":{\"url\":\"http://t/x\",\"metadata\":{\"model\":\"slim\"}}}}"));
        assertTrue(slim.orElseThrow().slim());
        assertTrue(SkinImage.textures(session("{\"textures\":{}}")).isEmpty()); // a default skin
    }

    @Test void modernSkinsOnlyGetTheirInnerLayerOpaque() {
        int[] px = new int[64 * 64];
        px[0] = 0x00123456;          // head, inner layer: made opaque
        px[40] = 0x00ABCDEF;         // hat: stays see-through
        int[] out = SkinImage.normalize(64, 64, px);
        assertEquals(0xFF123456, out[0]);
        assertEquals(0x00ABCDEF, out[40]);
    }

    @Test void oldSkinsGetTheirLeftLimbsMirrored() {
        int[] px = new int[64 * 32];
        px[20 * 64 + 4] = 0xFF00FF00; // right leg's front, leftmost column
        int[] out = SkinImage.normalize(64, 32, px);
        assertEquals(64 * 64, out.length);
        // Left leg's front (x 20..23, y 52..63): mirrored, so the rightmost column.
        assertEquals(0xFF00FF00, out[52 * 64 + 23]);
        assertNull(SkinImage.normalize(32, 32, new int[32 * 32]));
    }
}
