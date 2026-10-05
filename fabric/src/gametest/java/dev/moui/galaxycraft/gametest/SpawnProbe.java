package dev.moui.galaxycraft.gametest;

import dev.moui.galaxycraft.client.McBlocks;
import dev.moui.galaxycraft.client.ShadowLink;
import dev.moui.galaxycraft.shadow.ShadowMap;
import dev.moui.galaxycraft.shadow.ShadowWorld;
import dev.moui.galaxycraft.voxel.PlanetSession;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.monster.Enemy;
import org.joml.Vector3d;

/**
 * Mobs on a planet as Minecraft spawns them, without the game (-PgalaxycraftSpawn): the shadow takes
 * the planet's biome, a new column gets its animals, and at night monsters come out in the dark
 * around Mario's stand-in (not within 24 blocks of him). Prints PASS or FAIL.
 */
public final class SpawnProbe implements FabricClientGameTest {
    private PlanetSession s;
    private ShadowLink link;
    private Vector3d mario;
    private final List<String> fails = new ArrayList<>();

    @Override
    public void runTest(ClientGameTestContext ctx) {
        if (!Boolean.getBoolean("galaxycraft.spawn")) return;
        try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
            sp.getServer().runCommand("difficulty normal");
            sp.getServer().runCommand("gamemode creative @a"); // monsters leave him be; they still spawn around him
            sp.getServer().runCommand("gamerule spawn_mobs true"); // the test world turns it off
            sp.getServer().runCommand("time set noon");
            ctx.waitTicks(20);
            ctx.runOnClient(mc -> {
                McBlocks blocks = McBlocks.create(mc);
                s = new PlanetSession(1);
                s.setBlocks(blocks);
                s.spawn(48, new Vector3d(), new Vector3d(0, 1, 0));
                link = new ShadowLink(() -> s);
                mario = new Vector3d(0, s.planet().surface() + 0.1, 0).add(s.center());
            });
            run(ctx, 200); // the shadow takes the planet in
            String biome = ctx.computeOnClient(mc -> {
                var level = mc.getSingleplayerServer().getLevel(ShadowWorld.KEY);
                var map = ShadowMap.of(s.planet(), "probe");
                int cell = s.cellAt(mario);
                return level.getBiome(new BlockPos(map.x(cell), map.y(cell), map.z(cell))).getRegisteredName();
            });
            System.out.println("[GalaxyCraft spawn] biome under Mario " + biome);
            if (!biome.equals("minecraft:plains")) fails.add("biome " + biome);
            System.out.println("[GalaxyCraft spawn] " + diagnose(ctx));
            int animals = count(ctx, true);
            System.out.println("[GalaxyCraft spawn] animals after the planet came in: " + animals);
            if (animals == 0) fails.add("no animals");
            // A torch beside Mario: its flame and smoke, as a client would make them.
            ctx.runOnClient(mc -> {
                var p = s.planet();
                int c = p.grid.neighbor(s.cellAt(mario), dev.moui.galaxycraft.voxel.CubeSphere.I_PLUS);
                p.set(c, p.blocks.parse("minecraft:torch"));
                while (ShadowWorld.pollParticle() != null) {}
            });
            run(ctx, 100);
            int flames = ctx.computeOnClient(mc -> {
                int n = 0;
                for (ShadowWorld.Particle q; (q = ShadowWorld.pollParticle()) != null; )
                    if (q.options().getType() == net.minecraft.core.particles.ParticleTypes.FLAME) n++;
                return n;
            });
            System.out.println("[GalaxyCraft spawn] flames from a torch in 5 s: " + flames);
            if (flames == 0) fails.add("no torch flames");
            int dayMonsters = count(ctx, false);
            System.out.println("[GalaxyCraft spawn] by day: " + ctx.computeOnClient(mc -> {
                java.util.Map<String, Integer> kinds = new java.util.TreeMap<>();
                for (var m : ShadowWorld.entities().list()) if (m instanceof Enemy) kinds.merge(m.getType().toShortString(), 1, Integer::sum);
                return kinds.toString();
            }));
            sp.getServer().runCommand("time set midnight");
            run(ctx, 1200);
            int night = count(ctx, false);
            System.out.println("[GalaxyCraft spawn] night: " + diagnose(ctx));
            String near = ctx.computeOnClient(mc -> {
                var e = ShadowWorld.entities();
                double min = Double.MAX_VALUE;
                for (var m : e.list()) if (m instanceof Enemy) min = Math.min(min, ShadowWorld.distanceToMario(m.getX(), m.getY(), m.getZ()));
                return String.format("%.1f", min);
            });
            System.out.println("[GalaxyCraft spawn] monsters by day " + dayMonsters + ", after a minute of night " + night
                    + " (nearest " + near + " blocks)");
            if (dayMonsters > 0) fails.add("monsters in daylight on plains: " + dayMonsters);
            if (night <= dayMonsters) fails.add("no monsters at night");
            if (night > 20) fails.add("too many monsters for the planet's ground near Mario: " + night);
            System.out.println("[GalaxyCraft spawn] " + (fails.isEmpty() ? "PASS" : "FAIL " + fails));
        }
    }

    private String diagnose(ClientGameTestContext ctx) {
        // On the server's thread (the client may not read the shadow); the server is paused between test ticks.
        java.util.concurrent.atomic.AtomicReference<String> out = new java.util.concurrent.atomic.AtomicReference<>("?");
        ctx.runOnClient(mc -> mc.getSingleplayerServer().execute(() -> out.set(diagnoseNow(mc))));
        run(ctx, 2);
        return out.get();
    }

    private String diagnoseNow(net.minecraft.client.Minecraft mc) {
        {
            var server = mc.getSingleplayerServer();
            var level = server.getLevel(ShadowWorld.KEY);
            var map = ShadowMap.of(s.planet(), "probe");
            int cell = s.cellAt(mario);
            BlockPos at = new BlockPos(map.x(cell), map.y(cell), map.z(cell));
            var cp = new net.minecraft.world.level.ChunkPos(at.getX() >> 4, at.getZ() >> 4);
            var chunk = level.getChunk(cp.x(), cp.z());
            return "proxy " + ShadowWorld.proxyState() + ", players " + level.players().size() + ", at " + at + " " + level.getBlockState(at)
                    + " below " + level.getBlockState(at.below()) + ", raw light " + level.getMaxLocalRawBrightness(at)
                    + " sky " + level.getBrightness(net.minecraft.world.level.LightLayer.SKY, at) + ", time " + level.getDefaultClockTime() + " darken " + level.getSkyDarken()
                    + ", monsters " + level.isSpawningMonsters() + ", difficulty " + level.getDifficulty()
                    + ", close " + level.getChunkSource().chunkMap.anyPlayerCloseEnoughForSpawning(cp)
                    + ", inhabited " + chunk.getInhabitedTime() + ", height " + chunk.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE, at.getX() & 15, at.getZ() & 15)
                    + ", entities " + (ShadowWorld.entities() == null ? -1 : ShadowWorld.entities().list().size());
        }
    }

    private int count(ClientGameTestContext ctx, boolean animals) {
        return ctx.computeOnClient(mc -> {
            var e = ShadowWorld.entities();
            if (e == null) return 0;
            int n = 0;
            for (var m : e.list()) if (animals ? m instanceof Animal : m instanceof Enemy) n++;
            return n;
        });
    }

    private void run(ClientGameTestContext ctx, int ticks) {
        for (int t = 0; t < ticks; t++) {
            ctx.runOnClient(mc -> {
                link.tick("probe", mario);
                ShadowWorld.mario(new ShadowWorld.MarioAt(s.planet(), s.localOf(mario), new Vector3d(1, 0, 0), mc.player.getUUID()));
            });
            ctx.waitTick();
        }
    }
}
