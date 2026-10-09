package dev.moui.galaxycraft.gametest;

import dev.moui.galaxycraft.client.GalaxyOptions;
import dev.moui.galaxycraft.client.music.MusicService;
import dev.moui.galaxycraft.music.Mixer;
import dev.moui.galaxycraft.music.SourceMode;
import dev.moui.galaxycraft.music.Source;
import dev.moui.galaxycraft.music.Track;
import dev.moui.galaxycraft.music.Want;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.minecraft.sounds.SoundSource;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

/**
 * The soundtrack without the game (-PgalaxycraftSoundtrack): two synthetic songs (a 440 Hz space
 * tone, a 660 Hz planet tone) in a scratch folder, no audio line (the probe renders the mixer
 * itself). In a world with no planet around it checks that automatic music picks the space song,
 * that Minecraft's own songs are found and decode, that a station's pick plays at once, that
 * silence is silent and that Music Source changes the pool. Prints "[GalaxyCraft soundtrack] PASS"
 * or FAIL with what went wrong.
 */
public final class SoundtrackProbe implements FabricClientGameTest {
    private final List<String> fails = new ArrayList<>();

    @Override
    public void runTest(ClientGameTestContext ctx) {
        if (!Boolean.getBoolean("galaxycraft.soundtrack")) return;
        Path dir = Path.of(System.getProperty("galaxycraft.soundtrackDir"));
        try {
            Files.createDirectories(dir);
            writeTone(dir.resolve("space.ast"), 440);
            writeTone(dir.resolve("planet.ast"), 660);
            Files.writeString(dir.resolve("tracks.tsv"),
                    "s1\tSynthetic Space\tspace.ast\tsmg2\tspace\t\ttrue\np1\tSynthetic Planet\tplanet.ast\tsmg2\tplanet\t\ttrue\n");
            Files.deleteIfExists(dir.resolve("minecraft.tsv"));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        GalaxyOptions.MUSIC_CROSSFADE.set(0);
        GalaxyOptions.MUSIC_DWELL.set(0);
        GalaxyOptions.MUSIC_COOLDOWN.set(0);
        try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
            ctx.waitTicks(10);
            log("sliders: master " + ctx.computeOnClient(mc -> mc.options.getSoundSourceVolume(SoundSource.MASTER))
                    + ", music " + ctx.computeOnClient(mc -> mc.options.getSoundSourceVolume(SoundSource.MUSIC)));
            ctx.runOnClient(mc -> { // the harness mutes the game: the probe listens at full volume
                mc.options.getSoundSourceOptionInstance(SoundSource.MASTER).set(1.0);
                mc.options.getSoundSourceOptionInstance(SoundSource.MUSIC).set(1.0);
            });
            ctx.waitTicks(5);

            // 1. Automatic music, no planet around: the space song, at 440 Hz.
            check(MusicService.shouldReplaceVanilla(), "vanilla music is silenced while automatic music is on");
            check(name(ctx).equals("s1"), "in open space the space song plays (" + name(ctx) + ")");
            check(freq(ctx) == 440, "and sounds like 440 Hz (" + freq(ctx) + ")");

            // 2. Minecraft's songs are found.
            List<Track> mc = ctx.computeOnClient(mc2 -> MusicService.tracks().stream().filter(t -> t.source() == Source.MINECRAFT).toList());
            log("Minecraft songs found: " + mc.size());
            check(!mc.isEmpty(), "Minecraft's music/game songs are found through the resource manager");
            if (!mc.isEmpty()) {
                Track t = mc.get(0);
                ctx.runOnClient(mc2 -> MusicService.playNow(t));
                float peak = peak(ctx, 3);
                log("Minecraft song " + t.title() + " peak " + peak);
                check(peak > 0.02f, "a Minecraft song decodes and is audible (peak " + peak + ")");
                check(MusicService.pinned(), "a pick pins the music");
                ctx.runOnClient(mc2 -> MusicService.auto());
            }

            // 3. A station's pick plays at once: the planet song, 660 Hz.
            ctx.runOnClient(mc2 -> MusicService.stationPick("probe-station", Want.track("p1")));
            check(name(ctx).equals("p1"), "a station's track plays at once (" + name(ctx) + ")");
            check(freq(ctx) == 660, "and sounds like 660 Hz (" + freq(ctx) + ")");

            // 4. Silence is silent.
            ctx.runOnClient(mc2 -> MusicService.stationPick("probe-station", Want.SILENCE));
            check(name(ctx).equals("-"), "a station's silence plays nothing (" + name(ctx) + ")");
            check(peak(ctx, 1) < 1e-4f, "and is silent");

            // 5. Music Source: Minecraft only, in space, plays a Minecraft song.
            GalaxyOptions.MUSIC_SOURCE.set(SourceMode.MINECRAFT);
            ctx.runOnClient(mc2 -> MusicService.auto());
            ctx.waitTicks(5);
            String now = name(ctx);
            check(now.startsWith("mc:"), "Minecraft only plays Minecraft's songs even in space (" + now + ")");
            GalaxyOptions.MUSIC_SOURCE.set(SourceMode.SMG2);
            ctx.runOnClient(mc2 -> MusicService.auto());
            ctx.waitTicks(5);
            check(name(ctx).equals("s1"), "SMG2 only in space plays the space song (" + name(ctx) + ")");
        } catch (RuntimeException e) {
            fails.add("exception " + e);
            e.printStackTrace();
        }
        log(fails.isEmpty() ? "PASS" : "FAIL " + fails);
    }

    private static String name(ClientGameTestContext ctx) {
        return ctx.computeOnClient(mc -> MusicService.playing() == null ? "-" : MusicService.playing().id());
    }

    /** Renders seconds of the mixer by hand (the client thread; no audio line runs) and returns the peak. */
    private static float peak(ClientGameTestContext ctx, double seconds) {
        return ctx.computeOnClient(mc -> {
            float[] buf = new float[2 * 1024];
            float peak = 0;
            for (int i = 0; i < seconds * Mixer.RATE / 1024; i++) {
                MusicService.mixer().render(buf, 1024);
                for (float v : buf) {
                    if (Float.isNaN(v)) return Float.NaN;
                    peak = Math.max(peak, Math.abs(v));
                }
            }
            return peak;
        });
    }

    /** The tone's frequency, from the sign changes in 0.2 s of the left channel (rounded to 10 Hz); 0 if silent. */
    private static int freq(ClientGameTestContext ctx) {
        return ctx.computeOnClient(mc -> {
            float[] buf = new float[2 * 8820];
            MusicService.mixer().render(buf, 8820);
            int crossings = 0;
            float prev = 0;
            for (int i = 0; i < 8820; i++) {
                float v = buf[2 * i];
                if (prev < 0 && v >= 0) crossings++;
                prev = v;
            }
            return Math.round(crossings / 0.2f / 10f) * 10;
        });
    }

    /** A 3-second stereo sine as an .ast of 32 kHz PCM16, looping from 0.5 s. */
    private static void writeTone(Path file, double hz) throws IOException {
        int rate = 32000, frames = rate * 3, block = 4096;
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        for (int first = 0; first < frames; first += block) {
            int n = Math.min(block, frames - first);
            ByteBuffer b = ByteBuffer.allocate(0x20 + n * 4).order(ByteOrder.BIG_ENDIAN);
            b.putInt(0x424C434B).putInt(n * 2);
            b.position(0x20);
            for (int c = 0; c < 2; c++)
                for (int i = 0; i < n; i++) b.putShort((short) (Math.sin(2 * Math.PI * hz * (first + i) / rate) * 16000));
            body.write(b.array());
        }
        ByteBuffer h = ByteBuffer.allocate(0x40).order(ByteOrder.BIG_ENDIAN);
        h.putInt(0x5354524D).putInt(body.size()).putShort((short) 1).putShort((short) 16).putShort((short) 2)
                .putShort((short) 0xFFFF).putInt(rate).putInt(frames).putInt(rate / 2).putInt(frames).putInt(block * 2);
        ByteArrayOutputStream all = new ByteArrayOutputStream();
        all.write(h.array());
        all.write(body.toByteArray());
        Files.write(file, all.toByteArray());
    }

    private void check(boolean ok, String what) {
        log((ok ? "ok   " : "FAIL ") + what);
        if (!ok) fails.add(what);
    }

    private static void log(String s) {
        System.out.println("[GalaxyCraft soundtrack] " + s);
    }
}
