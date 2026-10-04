package dev.moui.galaxycraft.client;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import dev.moui.galaxycraft.shadow.ShadowWorld;
import dev.moui.galaxycraft.voxel.PlanetDrops;
import dev.moui.galaxycraft.voxel.VoxelPlanet;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import net.minecraft.client.Minecraft;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.Vector3d;

/**
 * Particles Minecraft made in the shadow (ShadowWorld.pollParticle: a mob's last poof, an
 * explosion, a broken block's pieces, hits), moved on the planet like Minecraft moves them and
 * drawn by the game facing the camera (EntityClient). Each is its particle type's sprites from
 * its particles/*.json, shown by age; a block's pieces are bits of its side.
 */
final class ParticleClient {
    static final int MAX = 400;

    /** A live particle: planet space, blocks and blocks per tick. */
    static final class Live {
        final Vector3d pos, vel;
        final List<Identifier> frames; // null for a block's piece
        final BlockState block;
        final int bit; // which 4x4 bit of the block's side
        final double size, gravity, drag;
        final int life;
        int age;

        Live(Vector3d pos, Vector3d vel, List<Identifier> frames, BlockState block, int bit, double size, double gravity,
                double drag, int life) {
            this.pos = pos;
            this.vel = vel;
            this.frames = frames;
            this.block = block;
            this.bit = bit;
            this.size = size;
            this.gravity = gravity;
            this.drag = drag;
            this.life = life;
        }

        /** The sprite now, by age (Minecraft's sprite sets). */
        Identifier frame() {
            return frames.get(Math.min(frames.size() - 1, age * (frames.size() - 1) / Math.max(1, life)));
        }
    }

    private final List<Live> live = new ArrayList<>();
    private final Map<Identifier, List<Identifier>> sprites = new HashMap<>();
    private final Random random = new Random();

    List<Live> all() {
        return live;
    }

    /** Client tick: new ones from the shadow, then each moves (p: the running planet, or null). */
    void tick(VoxelPlanet p) {
        for (ShadowWorld.Particle s; (s = ShadowWorld.pollParticle()) != null; )
            if (p != null && s.planet() == p && live.size() < MAX) spawn(s.options(), s.pos(), s.vel());
        if (p == null) {
            live.clear();
            return;
        }
        live.removeIf(l -> ++l.age >= l.life);
        for (Live l : live) {
            l.vel.mul(l.drag);
            if (l.gravity > 0) l.vel.fma(-l.gravity, new Vector3d(l.pos).normalize());
            Vector3d to = new Vector3d(l.pos).add(l.vel);
            if (l.gravity > 0 && PlanetDrops.blocked(p, to)) l.vel.zero();
            else l.pos.set(to);
        }
    }

    private void spawn(ParticleOptions options, Vector3d pos, Vector3d vel) {
        if (options instanceof BlockParticleOption b) {
            double life = 4 / (random.nextDouble() * 0.9 + 0.1);
            live.add(new Live(pos, vel, null, b.getState(), random.nextInt(16), 0.1 + random.nextDouble() * 0.1, 0.04, 0.98,
                    (int) Math.min(life, 30)));
            return;
        }
        Identifier type = BuiltInRegistries.PARTICLE_TYPE.getKey(options.getType());
        if (type == null) return;
        String name = type.getPath();
        if (name.equals("explosion_emitter")) { // a big explosion: a cluster of them
            for (int i = 0; i < 6; i++)
                spawn(net.minecraft.core.particles.ParticleTypes.EXPLOSION, new Vector3d(pos).add(gauss(1.5), gauss(1.5), gauss(1.5)),
                        new Vector3d());
            return;
        }
        List<Identifier> frames = sprites.computeIfAbsent(type, this::frames);
        if (frames.isEmpty()) return;
        double size, drag = 0.96;
        int life;
        switch (name) {
            case "explosion" -> {
                size = 2 * (2 - random.nextDouble());
                life = 6 + random.nextInt(4);
                drag = 0;
            }
            case "poof", "large_smoke", "cloud" -> {
                size = 0.4 + random.nextDouble() * 0.4;
                life = (int) Math.min(40, 8 / (random.nextDouble() * 0.8 + 0.2));
                drag = 0.9;
            }
            default -> {
                size = 0.15 + random.nextDouble() * 0.15;
                life = (int) Math.min(30, 4 / (random.nextDouble() * 0.9 + 0.1)) + 4;
            }
        }
        live.add(new Live(pos, vel, frames, null, 0, size, 0, drag, life));
    }

    private double gauss(double s) {
        return random.nextGaussian() * s;
    }

    /** A particle type's sprites: textures/particle/*.png as its particles/*.json lists them. */
    private List<Identifier> frames(Identifier type) {
        List<Identifier> out = new ArrayList<>();
        Identifier json = type.withPath(p -> "particles/" + p + ".json");
        try (Reader r = new InputStreamReader(Minecraft.getInstance().getResourceManager().open(json), StandardCharsets.UTF_8)) {
            for (JsonElement t : JsonParser.parseReader(r).getAsJsonObject().getAsJsonArray("textures")) {
                Identifier tex = Identifier.parse(t.getAsString());
                out.add(tex.withPath(p -> "textures/particle/" + p + ".png"));
            }
        } catch (Exception e) {
            // no sprites of its own (it is drawn some other way): not shown
        }
        return out;
    }
}
