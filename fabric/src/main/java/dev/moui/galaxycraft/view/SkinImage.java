package dev.moui.galaxycraft.view;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Optional;

/**
 * A Minecraft account's skin as the game draws it: Mojang's answers read (where the skin is and
 * whether its arms are slim), and the image made the 64x64 one Minecraft draws, as its own
 * skin downloader does (old 64x32 skins get their left limbs mirrored from the right ones).
 */
public final class SkinImage {
    public static final int SIZE = 64;

    /** Where an account's skin is, and the model it is drawn on. */
    public record Textures(String url, boolean slim) {}

    private SkinImage() {}

    /** The account id in api.mojang.com's answer for a name ({"id": ..., "name": ...}). */
    public static Optional<String> profileId(String json) {
        try {
            JsonElement id = JsonParser.parseString(json).getAsJsonObject().get("id");
            return id == null ? Optional.empty() : Optional.of(id.getAsString());
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    /** The skin in sessionserver's answer for a profile: its "textures" property, base64 JSON. */
    public static Optional<Textures> textures(String json) {
        try {
            for (JsonElement p : JsonParser.parseString(json).getAsJsonObject().getAsJsonArray("properties")) {
                JsonObject prop = p.getAsJsonObject();
                if (!"textures".equals(prop.get("name").getAsString())) continue;
                String decoded = new String(Base64.getDecoder().decode(prop.get("value").getAsString()), StandardCharsets.UTF_8);
                JsonObject skin = JsonParser.parseString(decoded).getAsJsonObject().getAsJsonObject("textures")
                        .getAsJsonObject("SKIN");
                if (skin == null) return Optional.empty(); // the account wears a default skin
                JsonObject meta = skin.getAsJsonObject("metadata");
                boolean slim = meta != null && meta.has("model") && "slim".equals(meta.get("model").getAsString());
                return Optional.of(new Textures(skin.get("url").getAsString(), slim));
            }
        } catch (RuntimeException e) {
            // not an answer we can read
        }
        return Optional.empty();
    }

    /**
     * A skin image (ARGB, row 0 at the top) as the 64x64 one Minecraft draws: an old 64x32 one
     * gets its lower half, the inner layer is made opaque. Null if it is not a skin's size.
     */
    public static int[] normalize(int width, int height, int[] argb) {
        if (width != SIZE || (height != SIZE && height != SIZE / 2) || argb.length < width * height) return null;
        int[] px = new int[SIZE * SIZE];
        System.arraycopy(argb, 0, px, 0, width * height);
        if (height == SIZE / 2) {
            // SkinTextureDownloader.processLegacySkin: the left leg and arm from the right ones.
            copy(px, 4, 16, 16, 32, 4, 4);
            copy(px, 8, 16, 16, 32, 4, 4);
            copy(px, 0, 20, 24, 32, 4, 12);
            copy(px, 4, 20, 16, 32, 4, 12);
            copy(px, 8, 20, 8, 32, 4, 12);
            copy(px, 12, 20, 16, 32, 4, 12);
            copy(px, 44, 16, -8, 32, 4, 4);
            copy(px, 48, 16, -8, 32, 4, 4);
            copy(px, 40, 20, 0, 32, 4, 12);
            copy(px, 44, 20, -8, 32, 4, 12);
            copy(px, 48, 20, -16, 32, 4, 12);
            copy(px, 52, 20, -8, 32, 4, 12);
            hatHack(px);
        }
        opaque(px, 0, 0, 32, 16);
        opaque(px, 0, 16, 64, 32);
        opaque(px, 16, 48, 48, 64);
        return px;
    }

    /** NativeImage.copyRect(x, y, dx, dy, w, h, mirrorX = true, mirrorY = false). */
    private static void copy(int[] px, int x, int y, int dx, int dy, int w, int h) {
        for (int j = 0; j < h; j++)
            for (int i = 0; i < w; i++)
                px[(y + dy + j) * SIZE + x + dx + (w - 1 - i)] = px[(y + j) * SIZE + x + i];
    }

    /** An old skin's hat with no see-through pixel was never meant to be drawn (Notch's hack). */
    private static void hatHack(int[] px) {
        for (int y = 0; y < 16; y++)
            for (int x = 32; x < 64; x++)
                if ((px[y * SIZE + x] >>> 24) < 128) return;
        for (int y = 0; y < 16; y++)
            for (int x = 32; x < 64; x++) px[y * SIZE + x] &= 0x00FFFFFF;
    }

    private static void opaque(int[] px, int x0, int y0, int x1, int y1) {
        for (int y = y0; y < y1; y++)
            for (int x = x0; x < x1; x++) px[y * SIZE + x] |= 0xFF000000;
    }
}
