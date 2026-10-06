package dev.moui.galaxycraft.client;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import dev.moui.galaxycraft.shadow.ShadowWorld;
import dev.moui.galaxycraft.voxel.CellSpace;
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
 * its particles/*.json, shown by age; a block's pieces are bits of its side. The client adds its
 * own too, for blocks the player breaks and places (crack, burst, puff).
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

    /**
     * Minecraft's crack particle (ParticleEngine.crack), every tick a block is being broken: a small
     * piece of it off a random spot of the side hit (face, a cell side), drifting off it.
     */
    void crack(VoxelPlanet p, int cell, int face, BlockState block) {
        if (live.size() >= MAX) return;
        double[] o = p.info(cell).outline();
        int[] step = CellSpace.STEP[face];
        double[] at = new double[3], v = new double[3];
        for (int a = 0; a < 3; a++) {
            // On the side, 0.1 in from its edges and 0.1 out of it, as Minecraft puts it.
            at[a] = step[a] == 0 ? o[a] + 0.1 + random.nextDouble() * Math.max(0, o[a + 3] - o[a] - 0.2)
                    : step[a] > 0 ? o[a + 3] + 0.1 : o[a] - 0.1;
            v[a] = gauss(0.02) + step[a] * 0.02;
        }
        v[1] += 0.02;
        piece(p, cell, at, v, block, 0.6);
    }

    /** A block's pieces flying apart as Minecraft's break effect throws them (level event 2001). */
    void burst(VoxelPlanet p, int cell, BlockState block) {
        for (int i = 0; i < 12 && live.size() < MAX; i++) {
            double fx = random.nextDouble(), fy = random.nextDouble(), fz = random.nextDouble();
            piece(p, cell, new double[] {fx, fy, fz}, new double[] {(fx - 0.5) * 0.15, fy * 0.15 + 0.05, (fz - 0.5) * 0.15},
                    block, 1);
        }
    }

    /** A block just placed: a few small pieces of it puff off its sides (GalaxyCraft's, Minecraft makes none). */
    void puff(VoxelPlanet p, int cell, BlockState block) {
        double[] o = p.info(cell).outline();
        for (int i = 0; i < 8 && live.size() < MAX; i++) {
            int face = random.nextInt(6);
            if (CellSpace.STEP[face][1] < 0) face = random.nextInt(6); // fewer from underneath
            int[] step = CellSpace.STEP[face];
            double[] at = new double[3], v = new double[3];
            for (int a = 0; a < 3; a++) {
                at[a] = step[a] == 0 ? o[a] + random.nextDouble() * (o[a + 3] - o[a]) : step[a] > 0 ? o[a + 3] + 0.05 : o[a] - 0.05;
                v[a] = gauss(0.015) + step[a] * 0.04;
            }
            piece(p, cell, at, v, block, 0.7);
        }
    }

    /** A piece of block at model point at of cell, moving by v (model space, blocks a tick), scale times its size. */
    private void piece(VoxelPlanet p, int cell, double[] at, double[] v, BlockState block, double scale) {
        Vector3d pos = CellSpace.point(p.grid, cell, at[0], at[1], at[2]);
        Vector3d vel = CellSpace.point(p.grid, cell, at[0] + v[0], at[1] + v[1], at[2] + v[2]).sub(pos);
        double life = 4 / (random.nextDouble() * 0.9 + 0.1);
        live.add(new Live(pos, vel, null, block, random.nextInt(16), (0.1 + random.nextDouble() * 0.1) * scale, 0.04, 0.98,
                (int) Math.min(life, 30)));
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
