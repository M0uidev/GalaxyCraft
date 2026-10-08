package dev.moui.galaxycraft.gametest;

import com.mojang.blaze3d.platform.InputConstants;
import dev.moui.galaxycraft.client.McWorldgen;
import dev.moui.galaxycraft.client.PickerScreen;
import dev.moui.galaxycraft.client.PlanetClient;
import dev.moui.galaxycraft.voxel.CubeSphere;
import dev.moui.galaxycraft.voxel.gen.PlanetGenerator;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;
import javax.imageio.ImageIO;
import dev.moui.galaxycraft.client.McBlocks;
import dev.moui.galaxycraft.client.PlanetEditorScreen;
import dev.moui.galaxycraft.voxel.PlanetBlueprint;
import dev.moui.galaxycraft.voxel.VoxelPlanet;
import java.util.List;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

/**
 * The planet editor without the game, only with -Dgalaxycraft.editor=true
 * (./gradlew runClientGameTest -PgalaxycraftEditor): /galaxycraft opens it, and a blueprint of
 * Minecraft's blocks builds a planet with them, top to bottom. Its block picker finds a block by
 * the name the inventory gives it. Generated planets are built with Minecraft's real noises and
 * biome table; maps of their tops (the cube unfolded) are saved next to the screenshots.
 */
public final class PlanetEditorProbe implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext ctx) {
        if (!Boolean.getBoolean("galaxycraft.editor")) return;
        String[] picked = {null};
        try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
            ctx.waitTicks(20);
            ctx.runOnClient(mc -> mc.player.connection.sendCommand("galaxycraft"));
            ctx.waitTicks(5);
            boolean open = ctx.computeOnClient(mc -> mc.gui.screen() instanceof PlanetEditorScreen);
            if (!open) throw new AssertionError("/galaxycraft did not open the editor");
            ctx.takeScreenshot("galaxycraft-planet-editor");
            // The block picker: "+ Layer" opens it, typing narrows it by name, Enter adds the first.
            ctx.runOnClient(mc -> mc.gui.setScreen(PickerScreen.blocks(mc.gui.screen(), "", v -> picked[0] = v)));
            ctx.waitTicks(2);
            ctx.getInput().typeChars("oak log");
            ctx.waitTicks(2);
            ctx.takeScreenshot("galaxycraft-block-picker");
            ctx.getInput().pressKey(InputConstants.KEY_RETURN);
            ctx.waitTicks(2);
            if (!"minecraft:oak_log".equals(picked[0])) throw new AssertionError("picked " + picked[0]);
            if (!ctx.computeOnClient(mc -> mc.gui.screen() instanceof PlanetEditorScreen))
                throw new AssertionError("the picker did not return to the editor");
            String result = ctx.computeOnClient(mc -> {
                McBlocks blocks = McBlocks.create(mc);
                PlanetBlueprint bp = new PlanetBlueprint("probe", 20, 8, List.of(
                        new PlanetBlueprint.Layer("minecraft:sand", 2), new PlanetBlueprint.Layer("minecraft:oak_log[axis=y]", 1)));
                VoxelPlanet p = bp.build(blocks);
                int d = p.depth;
                return blocks.name(p.get(p.grid.index(0, 3, 3, d - 1))) + "|" + blocks.name(p.get(p.grid.index(0, 3, 3, d - 3)))
                        + "|" + blocks.name(p.get(p.grid.index(0, 3, 3, 0)));
            });
            if (!result.equals("minecraft:sand|minecraft:oak_log[axis=y]|minecraft:bedrock"))
                throw new AssertionError("built " + result);
            // Generated planets.
            ctx.runOnClient(mc -> mc.gui.setScreen(new PlanetEditorScreen(PlanetBlueprint.standard("Dunes", 32)
                    .withMode(PlanetBlueprint.Mode.GENERATED).withBiome(42, "minecraft:desert", 0))));
            ctx.waitTicks(2);
            ctx.takeScreenshot("galaxycraft-planet-editor-generated");
            ctx.runOnClient(mc -> mc.gui.setScreen(PickerScreen.biomes(mc.gui.screen(), dev.moui.galaxycraft.voxel.gen.LegacyBiome.land(), "", v -> picked[0] = v)));
            ctx.waitTicks(2);
            ctx.getInput().typeChars("snow");
            ctx.waitTicks(2);
            ctx.takeScreenshot("galaxycraft-biome-picker");
            ctx.getInput().pressKey(InputConstants.KEY_RETURN);
            ctx.waitTicks(2);
            if (picked[0] == null || !picked[0].contains("snow")) throw new AssertionError("picked biome " + picked[0]);
            // Every biome's vegetation grown first, on the server's thread (here the server only runs
            // while the test lets it: waiting on it from the client's thread would hang).
            String grown = sp.getServer().computeOnServer(server -> {
                McWorldgen wg = PlanetClient.worldgen();
                if (wg == null) return "no worldgen";
                int things = 0, empty = 0;
                for (String b : dev.moui.galaxycraft.voxel.gen.LegacyBiome.all()) {
                    int t = wg.vegetation().patches(b).stream().mapToInt(p -> p.things().size()).sum();
                    things += t;
                    if (t == 0) empty++;
                }
                return dev.moui.galaxycraft.voxel.gen.LegacyBiome.all().size() + " biomes, " + things + " things, " + empty + " biomes with none";
            });
            System.out.println("PlanetEditorProbe vegetation: " + grown);
            String gen = ctx.computeOnClient(mc -> {
                McBlocks blocks = McBlocks.create(mc);
                McWorldgen wg = PlanetClient.worldgen();
                StringBuilder out = new StringBuilder();
                for (Object[] c : new Object[][] {{"minecraft:desert", 32, 0, false}, {"minecraft:windswept_hills", 64, 0, false},
                        {"minecraft:plains", 96, 96, true}, {PlanetBlueprint.RANDOM, 48, -1, true}, {"minecraft:ocean", 48, 0, true},
                        {"minecraft:forest", 256, 0, true}}) {
                    PlanetBlueprint g = PlanetBlueprint.standard("gen", (int) c[1]).withAir(24)
                            .withMode(PlanetBlueprint.Mode.GENERATED).withBiome(7, (String) c[0], (int) c[2]).withWater((boolean) c[3]).withUnderground(50, true, 100).withPlants(100);
                    long t0 = System.nanoTime();
                    VoxelPlanet p = g.build(blocks, wg);
                    long ms = (System.nanoTime() - t0) / 1_000_000;
                    Map<String, Integer> tops = new TreeMap<>();
                    int lowest = Integer.MAX_VALUE, highest = 0;
                    CubeSphere grid = p.sphere();
                    int n = grid.n;
                    BufferedImage img = new BufferedImage(4 * n, 3 * n, BufferedImage.TYPE_INT_RGB);
                    int[][] at = {{2, 1}, {0, 1}, {1, 0}, {1, 2}, {1, 1}, {3, 1}}; // where each face goes (columns, rows of n)
                    for (int f = 0; f < 6; f++)
                        for (int i = 0; i < n; i++)
                            for (int j = 0; j < n; j++) {
                                int c0 = grid.index(f, i, j, 0), k = grid.layers - 1;
                                while (k > 0 && (p.get(c0 + k) == 0 || blocks.name(p.get(c0 + k)).startsWith("minecraft:snow["))) k--;
                                if (!blocks.name(p.get(c0)).equals("minecraft:bedrock")) throw new AssertionError(c[0] + ": no bedrock");
                                String top = blocks.name(p.get(c0 + k));
                                tops.merge(top.replaceAll("\\[.*", ""), 1, Integer::sum);
                                lowest = Math.min(lowest, k);
                                highest = Math.max(highest, k);
                                net.minecraft.world.level.block.state.BlockState st = blocks.state(p.get(c0 + k));
                                int rgb = st == null ? 0xFF00FF : st.getBlock().defaultMapColor().col;
                                double shade = 0.55 + 0.45 * k / grid.layers;
                                int r = (int) ((rgb >> 16 & 255) * shade), gr = (int) ((rgb >> 8 & 255) * shade), b = (int) ((rgb & 255) * shade);
                                img.setRGB(at[f][0] * n + i, at[f][1] * n + j, r << 16 | gr << 8 | b);
                            }
                    if ((boolean) c[3] && p.fluids().tick()) throw new AssertionError(c[0] + ": its still water ticks");
                    try {
                        ImageIO.write(img, "png", Path.of("screenshots", "galaxycraft-gen-" + ((String) c[0]).replace("minecraft:", "") + "-" + c[1] + ".png").toFile());
                    } catch (java.io.IOException e) {
                        throw new AssertionError(e);
                    }
                    // A slice through the center (the plane z = 0): caves and ores by their map colors.
                    int R = (int) c[1] + 26;
                    BufferedImage cut = new BufferedImage(2 * R, 2 * R, BufferedImage.TYPE_INT_RGB);
                    Map<String, Integer> under = new TreeMap<>();
                    for (int px = 0; px < 2 * R; px++)
                        for (int py = 0; py < 2 * R; py++) {
                            int cell = grid.cellAt(new org.joml.Vector3d(px - R + 0.5, R - py - 0.5, 0.25));
                            int id = cell < 0 ? 0 : p.get(cell);
                            net.minecraft.world.level.block.state.BlockState st = id == 0 ? null : blocks.state(id);
                            cut.setRGB(px, py, st == null ? 0x101018 : st.getBlock().defaultMapColor().col);
                        }
                    int hollow = 0, solid = 0;
                    for (int cell = 0; cell < grid.cellCount(); cell++) {
                        int kk = grid.k(cell);
                        if (kk > 0 && kk < p.depth - 4) {
                            if (p.get(cell) == 0) hollow++;
                            else solid++;
                        }
                        String name = blocks.name(p.get(cell));
                        if (name.contains("_ore")) under.merge(name.replace("minecraft:", ""), 1, Integer::sum);
                    }
                    try {
                        ImageIO.write(cut, "png", Path.of("screenshots", "galaxycraft-cut-" + ((String) c[0]).replace("minecraft:", "") + "-" + c[1] + ".png").toFile());
                    } catch (java.io.IOException e) {
                        throw new AssertionError(e);
                    }
                    out.append("  caves ").append(100 * hollow / Math.max(1, hollow + solid)).append("% of the deep ground, ores ").append(under).append('\n');
                    out.append(c[0]).append(" r").append(c[1]).append(" size ").append(c[2]).append(": ").append(ms).append(" ms, k ")
                            .append(lowest).append("..").append(highest).append(" of ").append(grid.layers).append(", depth ").append(p.depth)
                            .append(", tops ").append(tops).append('\n');
                }
                return out.toString();
            });
            System.out.println("PlanetEditorProbe generated:\n" + gen);
            if (!gen.lines().filter(l -> l.startsWith("minecraft:desert")).allMatch(l -> l.contains("minecraft:sand=")))
                throw new AssertionError("the desert is not sand");
            if (gen.lines().filter(l -> l.startsWith("minecraft:plains")).allMatch(l -> l.split("=").length < 3))
                throw new AssertionError("several biomes gave one top");
            if (!gen.lines().filter(l -> l.startsWith("minecraft:plains") || l.startsWith("minecraft:warm_ocean"))
                    .allMatch(l -> l.contains("minecraft:water=")))
                throw new AssertionError("no water where there should be");
            System.out.println("PlanetEditorProbe passed: " + result);
        }
    }
}
