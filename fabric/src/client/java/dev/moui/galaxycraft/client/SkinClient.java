package dev.moui.galaxycraft.client;

import dev.moui.galaxycraft.GalaxyCraft;
import dev.moui.galaxycraft.bridge.BridgeClient;
import dev.moui.galaxycraft.proto.Layout;
import dev.moui.galaxycraft.view.EntityWire;
import dev.moui.galaxycraft.view.SkinImage;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import javax.imageio.ImageIO;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;

/**
 * /skin: a Minecraft account's skin on the player, looked up by name in Mojang's public API
 * (api.mojang.com for the account, sessionserver.mojang.com for its textures) and kept in
 * config/galaxycraft/skins so it is there again offline. It goes on Mario's model in SMG2
 * (Steve, as GXC_MSG_MARIO_SKIN for Dolphin) and on Steve drawn as Minecraft's player model
 * (Minecraft movement). An empty name puts Steve's own skin back.
 */
final class SkinClient {
    /** The skin worn: the account's name ("" Steve), 64x64 ARGB, and whether its arms are slim. */
    record Skin(String name, int[] argb, boolean slim) {}

    private static final Identifier STEVE = Identifier.withDefaultNamespace("textures/entity/player/wide/steve.png");
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8))
            .followRedirects(HttpClient.Redirect.NORMAL).build();
    private static volatile Skin current;
    /** Bumped by each new skin: what the game was sent last is compared with it. */
    private static volatile int version;
    private static int sentVersion = -1, sentHost = Integer.MIN_VALUE;
    private static int request;

    private SkinClient() {}

    /** The custom skin worn, or null for the player's own. */
    static Skin current() {
        return current;
    }

    /** Wears name's skin (Steve's if blank); done says how it went, on the client thread. */
    static void wear(String name, Consumer<String> done) {
        String n = name == null ? "" : name.strip();
        int mine = ++request;
        if (n.isEmpty()) {
            current = null;
            version++;
            done.accept("Back to your own skin");
            return;
        }
        CompletableFuture.supplyAsync(() -> fetch(n)).whenComplete((skin, err) -> Minecraft.getInstance().execute(() -> {
            if (mine != request) return; // another /skin since
            if (err != null || skin == null) {
                Throwable cause = err instanceof java.util.concurrent.CompletionException c && c.getCause() != null ? c.getCause() : err;
                done.accept(cause == null ? "No skin for " + n : cause.getMessage());
                return;
            }
            current = skin;
            version++;
            done.accept("Wearing " + skin.name() + "'s skin" + (skin.slim() ? " (slim arms)" : ""));
        }));
    }

    /** Render thread, each frame: the skin to Dolphin for Mario's model, once per skin and per host. */
    static void frame(BridgeClient bridge) {
        if (!bridge.linked()) return;
        int v = version, host = bridge.hostPid();
        if (v == sentVersion && host == sentHost) return;
        if (v == 0) { // never changed: the model keeps the skin it was built with
            sentVersion = v;
            sentHost = host;
            return;
        }
        Skin s = current;
        int[] argb = s != null ? s.argb() : steve();
        if (argb == null || bridge.send(Layout.MSG_MARIO_SKIN, EntityWire.skin(0, SkinImage.SIZE, SkinImage.SIZE, argb))) {
            sentVersion = v;
            sentHost = host;
        }
    }

    private static int[] steve;

    /** Steve's own skin from Minecraft's resources (null if it cannot be read). */
    private static int[] steve() {
        if (steve == null) steve = readSteve();
        return steve;
    }

    private static int[] readSteve() {
        try (InputStream in = Minecraft.getInstance().getResourceManager().open(STEVE);
                com.mojang.blaze3d.platform.NativeImage img = com.mojang.blaze3d.platform.NativeImage.read(in)) {
            int[] argb = new int[img.getWidth() * img.getHeight()];
            for (int y = 0; y < img.getHeight(); y++)
                for (int x = 0; x < img.getWidth(); x++) argb[y * img.getWidth() + x] = img.getPixel(x, y);
            return SkinImage.normalize(img.getWidth(), img.getHeight(), argb);
        } catch (Exception e) {
            GalaxyCraft.LOG.warn("No Steve skin: {}", e.toString());
            return null;
        }
    }

    // ---- lookup (a worker thread) ----

    private static Skin fetch(String name) {
        Path dir = cacheDir();
        String file = name.toLowerCase(Locale.ROOT);
        try {
            String profile = get("https://api.mojang.com/users/profiles/minecraft/" + URLEncoder.encode(name, StandardCharsets.UTF_8));
            if (profile == null) throw new IllegalStateException("No Minecraft account is named " + name);
            String id = SkinImage.profileId(profile).orElseThrow(() -> new IllegalStateException("Mojang's answer for " + name + " is unreadable"));
            String session = get("https://sessionserver.mojang.com/session/minecraft/profile/" + id);
            SkinImage.Textures t = session == null ? null : SkinImage.textures(session).orElse(null);
            if (t == null) throw new IllegalStateException(name + " wears a default skin");
            byte[] png = bytes(t.url());
            Skin skin = decode(name, png, t.slim());
            Files.createDirectories(dir);
            Files.write(dir.resolve(file + ".png"), png);
            Files.writeString(dir.resolve(file + ".txt"), t.slim() ? "slim" : "wide");
            return skin;
        } catch (IOException | InterruptedException | IllegalStateException e) {
            // Offline, or Mojang is down: the copy from last time, if there is one.
            try {
                Path png = dir.resolve(file + ".png");
                if (Files.isRegularFile(png)) {
                    Path model = dir.resolve(file + ".txt");
                    boolean slim = Files.isRegularFile(model) && Files.readString(model).strip().equals("slim");
                    GalaxyCraft.LOG.info("Skin of {} from the cache ({})", name, e.getMessage());
                    return decode(name, Files.readAllBytes(png), slim);
                }
            } catch (IOException cached) {
                // fall through to the first error
            }
            throw new IllegalStateException(e instanceof IllegalStateException ? e.getMessage()
                    : "Could not reach Mojang for " + name + "'s skin: " + e.getMessage(), e);
        }
    }

    private static Skin decode(String name, byte[] png, boolean slim) throws IOException {
        BufferedImage img = ImageIO.read(new ByteArrayInputStream(png));
        if (img == null) throw new IOException("the skin is not an image");
        int[] argb = img.getRGB(0, 0, img.getWidth(), img.getHeight(), null, 0, img.getWidth());
        int[] skin = SkinImage.normalize(img.getWidth(), img.getHeight(), argb);
        if (skin == null) throw new IOException("the skin is " + img.getWidth() + "x" + img.getHeight() + ", not 64x64");
        return new Skin(name, skin, slim);
    }

    /** The body of a 200 answer; null for "no such thing" (204, 404). */
    private static String get(String url) throws IOException, InterruptedException {
        HttpResponse<String> r = HTTP.send(request(url), HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() == 204 || r.statusCode() == 404) return null;
        if (r.statusCode() != 200) throw new IOException("HTTP " + r.statusCode() + " from " + URI.create(url).getHost());
        return r.body();
    }

    private static byte[] bytes(String url) throws IOException, InterruptedException {
        HttpResponse<byte[]> r = HTTP.send(request(url), HttpResponse.BodyHandlers.ofByteArray());
        if (r.statusCode() != 200) throw new IOException("HTTP " + r.statusCode() + " for the skin");
        return r.body();
    }

    private static HttpRequest request(String url) {
        return HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10)).header("User-Agent", "GalaxyCraft").GET().build();
    }

    private static Path cacheDir() {
        return net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir().resolve("galaxycraft").resolve("skins");
    }
}
