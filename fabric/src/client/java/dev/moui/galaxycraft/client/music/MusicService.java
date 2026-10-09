package dev.moui.galaxycraft.client.music;

import dev.moui.galaxycraft.client.GalaxyOptions;
import dev.moui.galaxycraft.music.Mixer;
import dev.moui.galaxycraft.music.Mood;
import dev.moui.galaxycraft.music.PcmSource;
import dev.moui.galaxycraft.music.Playlist;
import dev.moui.galaxycraft.music.ShuffleBag;
import dev.moui.galaxycraft.music.StationMusic;
import dev.moui.galaxycraft.music.SwitchGate;
import dev.moui.galaxycraft.music.Track;
import dev.moui.galaxycraft.music.Want;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundSource;

/** The soundtrack: ties the library, the gate, the mixer and the game together. Client thread only. */
public final class MusicService {
    private static final Mixer mixer = new Mixer();
    private static final JavaSoundSink sink = new JavaSoundSink(mixer);
    private static final MusicLibrary library = new MusicLibrary();
    private static final SwitchGate<Want> gate = new SwitchGate<>();
    private static final ShuffleBag bag = new ShuffleBag();
    private static final Random random = new Random();
    private static final AtomicBoolean ended = new AtomicBoolean();
    /** -Dgalaxycraft.soundtrackNull: no audio line (probes drive the mixer themselves). */
    private static final boolean NULL_DEVICE = Boolean.getBoolean("galaxycraft.soundtrackNull");
    private static StationMusic stations;
    private static boolean started, pinned, paused, queuedNext;
    private static Track playing;
    /** The id of the song last started: where Next and Previous go from, even after it ended. */
    private static String lastId;
    /** Songs asked of the mixer so far; the mixer's positions describe the new one only once it has applied that many. */
    private static int requested;
    private static float volumeSet = -1;
    private static double clock;
    private static long lastNanos;

    private MusicService() {}

    public static List<Track> tracks() { return library.tracks(); }
    public static Track playing() { return playing; }
    public static boolean pinned() { return pinned; }
    public static boolean paused() { return paused; }
    public static double positionSeconds() { return mixer.positionSeconds(); }
    public static double remainingSeconds() { return mixer.remainingSeconds(); }
    public static StationMusic stationMusic() { ensureStarted(); return stations; }

    /** The probes render the mixer by hand when there is no audio line. */
    public static Mixer mixer() { return mixer; }

    /** True while automatic music owns the music: vanilla's own stays quiet. */
    public static boolean shouldReplaceVanilla() { return started && GalaxyOptions.MUSIC_AUTO.get(); }

    private static void ensureStarted() {
        if (started) return;
        started = true;
        stations = new StationMusic(FabricLoader.getInstance().getConfigDir().resolve("galaxycraft-station-music.properties"));
        library.reload();
        mixer.onEnd(() -> ended.set(true));
        if (!NULL_DEVICE) sink.start();
    }

    public static void shutdown() {
        if (!started) return;
        mixer.stop(0);
        mixer.setPaused(false); // a pause must not outlive the world it was made in
        sink.stop();
        started = false;
        pinned = false;
        paused = false;
        queuedNext = false;
        ended.set(false);
        playing = null;
        lastId = null;
        volumeSet = -1;
        lastNanos = 0;
        gate.reset();
    }

    /** Every client tick, in a world. */
    public static void tick() {
        ensureStarted();
        long now = System.nanoTime();
        clock += lastNanos == 0 ? 0 : (now - lastNanos) / 1e9;
        lastNanos = now;
        Minecraft mc = Minecraft.getInstance();
        // getFinalSoundSourceVolume already multiplies the Music slider by the Master slider
        float volume = GalaxyOptions.MUSIC_VOLUME.get() / 100f * mc.options.getFinalSoundSourceVolume(SoundSource.MUSIC);
        if (volume != volumeSet) { // only a change is queued: with no audio line nothing would ever drain them
            volumeSet = volume;
            mixer.setVolume(volume);
        }
        if (paused) return;

        if (ended.getAndSet(false)) { // a non-looping song ended with nothing queued after it
            queuedNext = false;
            playing = null;
            if (pinned) advance(1);
            else playFor(gate.current());
        }
        // start the next song early so a song without a loop point crossfades into the next
        double left = mixer.remainingSeconds();
        boolean settled = mixer.playsApplied() == requested; // `left` is the new song's once the mixer has it
        if (!queuedNext && settled && playing != null && left != Double.POSITIVE_INFINITY && left <= GalaxyOptions.MUSIC_CROSSFADE.get()) {
            queuedNext = true;
            if (pinned) advance(1);
            else playFor(gate.current());
        }
        if (pinned || !GalaxyOptions.MUSIC_AUTO.get()) return;
        Want wanted = GalaxyOptions.MUSIC_SOURCE.get().apply(MusicSensor.wanted(stations));
        Want go = gate.update(clock, wanted, GalaxyOptions.MUSIC_DWELL.get(), GalaxyOptions.MUSIC_COOLDOWN.get());
        if (go != null) playFor(go);
    }

    /** Starts what a want calls for: a song of its pool, one song, or silence. */
    private static void playFor(Want w) {
        if (w == null) return;
        switch (w.kind()) {
            case SILENCE -> silence();
            case TRACK -> library.tracks().stream().filter(t -> t.id().equals(w.trackId())).findFirst()
                    .ifPresentOrElse(t -> { if (!start(t)) silence(); }, MusicService::silence);
            default -> {
                List<Track> pool = GalaxyOptions.MUSIC_SOURCE.get().pool(library.tracks(), w);
                for (int tries = 0; tries < 5 && !pool.isEmpty(); tries++) {
                    Track t = bag.next(pool, random);
                    if (start(t)) return;
                }
                silence(); // nothing playable: silence, no retry loop
            }
        }
    }

    private static void silence() {
        mixer.stop(GalaxyOptions.MUSIC_CROSSFADE.get());
        playing = null;
        queuedNext = false;
    }

    private static boolean start(Track t) {
        PcmSource src = library.open(t);
        if (src == null) return false;
        mixer.play(src, GalaxyOptions.MUSIC_CROSSFADE.get());
        requested++;
        playing = t;
        lastId = t.id();
        queuedNext = false;
        var player = Minecraft.getInstance().player;
        if (player != null) player.sendOverlayMessage(Component.translatable("music.galaxycraft.now_playing", t.title()));
        return true;
    }

    // ---- the player's choices ----

    public static void playNow(Track t) {
        ensureStarted();
        pinned = true;
        paused = false;
        mixer.setPaused(false);
        start(t);
    }

    /** Back to automatic music: the gate starts over, the place decides. */
    public static void auto() {
        pinned = false;
        gate.reset();
    }

    public static void pause(boolean p) {
        paused = p;
        mixer.setPaused(p);
    }

    public static void next() { advance(1); }
    public static void previous() { advance(-1); }

    /** The next/previous enabled song of the library (by order), stepping over any that will not open; pins. */
    private static void advance(int dir) {
        ensureStarted();
        pinned = true;
        for (Track t : Playlist.candidates(library.tracks(), lastId, dir)) if (start(t)) return;
    }

    public static void setMood(Track t, Mood m) { library.replace(t, t.withMood(m)); }
    public static void setEnabled(Track t, boolean on) { library.replace(t, t.withEnabled(on)); }

    /** A station's Music button: remembered, and played at once (a pick, so no cooldown). */
    public static void stationPick(String stationId, Want w) {
        ensureStarted();
        stations.set(stationId, w);
        pinned = false;
        Want eff = GalaxyOptions.MUSIC_SOURCE.get().apply(w);
        gate.force(eff, clock);
        playFor(eff);
    }
}
