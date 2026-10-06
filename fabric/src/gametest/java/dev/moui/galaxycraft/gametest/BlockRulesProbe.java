package dev.moui.galaxycraft.gametest;

import dev.moui.galaxycraft.client.McBlocks;
import dev.moui.galaxycraft.voxel.CellSpace;
import dev.moui.galaxycraft.voxel.CubeSphere;
import dev.moui.galaxycraft.voxel.PlanetSession;
import java.util.List;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import org.joml.Vector3d;

/**
 * Minecraft's blocks on a planet without the game, only with -Dgalaxycraft.blocks=true
 * (./gradlew runClientGameTest -PgalaxycraftBlocks): Minecraft's placement rules put blocks in a
 * row on a small planet, and each must be what Minecraft would make of it.
 */
public final class BlockRulesProbe implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext ctx) {
        if (!Boolean.getBoolean("galaxycraft.blocks")) return;
        try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
            ctx.waitTicks(20);
            String[] items = {"oak_stairs", "torch", "poppy", "glass", "oak_fence", "oak_fence", "oak_door", "oak_slab",
                    "oak_log", "furnace", "white_bed", "chest", "oak_leaves", "short_grass", "lantern", "ladder"};
            List<String> out = ctx.computeOnClient(mc -> {
                McBlocks blocks = McBlocks.create(mc);
                PlanetSession s = new PlanetSession(1);
                s.setBlocks(blocks);
                s.spawn(32, new Vector3d(), new Vector3d(0, 1, 0));
                var p = s.planet();
                Vector3d c = s.center();
                java.util.ArrayList<String> lines = new java.util.ArrayList<>();
                int[] cells = new int[items.length];
                for (int k = 0; k < items.length; k++) {
                    var item = BuiltInRegistries.ITEM.getValue(Identifier.withDefaultNamespace(items[k]));
                    // A row along the surface, a block apart; looking down and a little ahead (+x).
                    double a = (k - 8 + 0.5) / p.surface();
                    Vector3d up = new Vector3d(Math.sin(a), Math.cos(a), 0.02).normalize();
                    Vector3d ahead = new Vector3d(Math.cos(a), -Math.sin(a), 0);
                    Vector3d at = new Vector3d(up).mul(p.surface());
                    Vector3d eye = new Vector3d(up).mul(2.5).add(at).sub(new Vector3d(ahead).mul(0.6)).add(c);
                    Vector3d look = new Vector3d(up).negate().add(new Vector3d(ahead).mul(0.25)).normalize();
                    boolean ok = s.placeBlock(eye, look, blocks.placer(new ItemStack(item)), new Vector3d(0, -100, 0).add(c));
                    cells[k] = p.grid.cellAt(new Vector3d(up).mul(p.surface() + 0.5));
                    if (!ok) lines.add(items[k] + ": REFUSED");
                }
                // Read back once all are down: neighbors placed later have changed some (fences join).
                for (int k = 0; k < items.length; k++)
                    lines.add(items[k] + ": " + blocks.name(p.get(cells[k])) + " under "
                            + blocks.name(p.get(p.grid.neighbor(cells[k], CubeSphere.TOP))));
                return lines;
            });
            for (String l : out) System.out.println("[GalaxyCraft blocks] " + l);
            List<String> drawn = ctx.computeOnClient(BlockRulesProbe::drawn);
            for (String f : drawn) System.out.println("[GalaxyCraft blocks] FAIL " + f);
            System.out.println("[GalaxyCraft blocks] drawn " + (drawn.isEmpty() ? "PASS" : "FAIL"));
            List<String> fails = ctx.computeOnClient(BlockRulesProbe::walls);
            for (String f : fails) System.out.println("[GalaxyCraft blocks] FAIL " + f);
            System.out.println("[GalaxyCraft blocks] walls " + (fails.isEmpty() ? "PASS" : "FAIL"));
        }
    }

    /** Blocks Minecraft draws with a block entity renderer: each of their states. */
    private static final String[] DRAWN = {"chest", "trapped_chest", "ender_chest", "white_bed", "red_bed", "oak_sign",
            "oak_wall_sign", "oak_hanging_sign", "white_banner", "skeleton_skull", "player_head", "shulker_box",
            "red_shulker_box", "decorated_pot", "bell", "lectern", "enchanting_table", "conduit", "copper_chest"};

    /**
     * Every state of those blocks has faces (none invisible), each face's tile is inside the atlas,
     * and a chest's faces stay inside its block. The failures.
     */
    private static List<String> drawn(net.minecraft.client.Minecraft mc) {
        McBlocks blocks = McBlocks.create(mc);
        java.util.ArrayList<String> fails = new java.util.ArrayList<>();
        int tiles = blocks.atlasColumns() * blocks.atlasRows();
        for (String name : DRAWN) {
            var block = BuiltInRegistries.BLOCK.getValue(Identifier.withDefaultNamespace(name));
            if (block == net.minecraft.world.level.block.Blocks.AIR) {
                System.out.println("[GalaxyCraft blocks] (no " + name + " in this version)");
                continue;
            }
            int states = 0, quads = 0;
            for (var state : block.getStateDefinition().getPossibleStates()) {
                String text = net.minecraft.commands.arguments.blocks.BlockStateParser.serialize(state);
                var info = blocks.info(blocks.parse(text));
                states++;
                quads += info.quads().size();
                if (info.quads().isEmpty()) fails.add(text + " has no faces");
                for (var q : info.quads()) {
                    if (q.tile() < 0 || q.tile() >= tiles) fails.add(name + " face tile " + q.tile() + " outside the atlas");
                    if (name.endsWith("chest"))
                        for (float c : q.pos())
                            if (c < -0.01f || c > 1.01f) {
                                fails.add(name + " face outside its block: " + java.util.Arrays.toString(q.pos()));
                                break;
                            }
                }
            }
            float[] lo = {9, 9, 9}, hi = {-9, -9, -9};
            for (var q : blocks.info(blocks.parse("minecraft:" + name)).quads())
                for (int k = 0; k < 12; k++) {
                    lo[k % 3] = Math.min(lo[k % 3], q.pos()[k]);
                    hi[k % 3] = Math.max(hi[k % 3], q.pos()[k]);
                }
            System.out.println("[GalaxyCraft blocks] " + name + ": " + states + " states, " + quads / Math.max(1, states)
                    + " faces each, default from " + java.util.Arrays.toString(lo) + " to " + java.util.Arrays.toString(hi));
        }
        return fails;
    }

    /** Clicked onto a wall's side from each of its four ways (looking a little down), the wall's version. */
    private static final String[][] ON_WALLS = {{"torch", "minecraft:wall_torch["}, {"soul_torch", "minecraft:soul_wall_torch["},
            {"redstone_torch", "minecraft:redstone_wall_torch["}, {"skeleton_skull", "minecraft:skeleton_wall_skull["},
            {"player_head", "minecraft:player_wall_head["}, {"oak_sign", "minecraft:oak_wall_sign["},
            {"white_banner", "minecraft:white_wall_banner["}, {"dead_tube_coral_fan", "minecraft:dead_tube_coral_wall_fan["},
            {"ladder", "minecraft:ladder["}, {"lever", "minecraft:lever[face=wall,"}, {"stone_button", "minecraft:stone_button[face=wall,"}};

    /**
     * Torches, heads, signs and the like against a stone block's four sides, each facing away from
     * it; a hanging sign hung on it; heads on the floor turned to the look; a torch on the floor
     * standing. The failures, none if all is as Minecraft makes it.
     */
    private static List<String> walls(net.minecraft.client.Minecraft mc) {
        McBlocks blocks = McBlocks.create(mc);
        PlanetSession s = new PlanetSession(1);
        s.setBlocks(blocks);
        s.spawn(32, new Vector3d(), new Vector3d(0, 1, 0));
        var p = s.planet();
        var g = p.grid;
        java.util.ArrayList<String> fails = new java.util.ArrayList<>();
        Vector3d up = new Vector3d(0, Math.cos(8.0 / p.surface()), Math.sin(8.0 / p.surface()));
        int wall = g.cellAt(new Vector3d(up).mul(p.surface() + 0.5));
        p.set(wall, blocks.parse("minecraft:stone"));
        Vector3d nowhere = s.galOf(new Vector3d(0, -100, 0));
        int[] sides = {CubeSphere.I_MINUS, CubeSphere.I_PLUS, CubeSphere.J_MINUS, CubeSphere.J_PLUS};
        for (int side : sides) {
            int[] st = CellSpace.STEP[side];
            int n = g.neighbor(wall, side);
            String way = net.minecraft.core.Direction.values()[CellSpace.DIRECTION_OF_SIDE[side]].getSerializedName();
            Vector3d eye = s.galOf(CellSpace.point(g, wall, 0.5 + 1.6 * st[0], 0.95, 0.5 + 1.6 * st[2]));
            Vector3d look = s.galOf(CellSpace.point(g, wall, 0.5 + 0.5 * st[0], 0.4, 0.5 + 0.5 * st[2])).sub(eye).normalize();
            for (String[] w : ON_WALLS) {
                String got = place(s, blocks, w[0], eye, look, nowhere, n);
                if (!got.startsWith(w[1]) || !got.contains("facing=" + way)) fails.add(w[0] + " on the " + way + " side: " + got);
            }
            String hung = place(s, blocks, "oak_hanging_sign", eye, look, nowhere, n);
            if (!hung.startsWith("minecraft:oak_wall_hanging_sign[")) fails.add("oak_hanging_sign on the " + way + " side: " + hung);
        }
        // On the floor two blocks from the wall: a head turned to face the player (looking east 12, west 4: SkullBlock takes the yaw as it is), a torch standing.
        int floor = g.neighbor(g.neighbor(wall, CubeSphere.J_PLUS), CubeSphere.J_PLUS);
        int[][] looks = {{1, 12}, {-1, 4}};
        for (int[] l : looks) {
            Vector3d eye = s.galOf(CellSpace.point(g, floor, 0.5 - 1.2 * l[0], 1.9, 0.5));
            Vector3d look = s.galOf(CellSpace.point(g, floor, 0.5, 0.0, 0.5)).sub(eye).normalize();
            String got = place(s, blocks, "skeleton_skull", eye, look, nowhere, floor);
            if (!got.startsWith("minecraft:skeleton_skull[") || !got.matches(".*rotation=" + l[1] + "[],].*"))
                fails.add("skeleton_skull on the floor looking " + (l[0] > 0 ? "east" : "west") + ": " + got);
            got = place(s, blocks, "torch", eye, look, nowhere, floor);
            if (!got.equals("minecraft:torch")) fails.add("torch on the floor: " + got);
        }
        return fails;
    }

    /** The block item placed where eye looks, read back from cell and cleared again. */
    private static String place(PlanetSession s, McBlocks blocks, String name, Vector3d eye, Vector3d look, Vector3d feet, int cell) {
        var item = BuiltInRegistries.ITEM.getValue(Identifier.withDefaultNamespace(name));
        if (!s.placeBlock(eye, look, blocks.placer(new ItemStack(item)), feet)) return "REFUSED";
        String got = blocks.name(s.planet().get(cell));
        s.planet().set(cell, dev.moui.galaxycraft.voxel.Blocks.AIR);
        return got;
    }
}
