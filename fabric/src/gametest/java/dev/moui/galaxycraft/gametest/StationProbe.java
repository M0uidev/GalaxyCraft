package dev.moui.galaxycraft.gametest;

import dev.moui.galaxycraft.client.McBlocks;
import dev.moui.galaxycraft.client.ShadowLink;
import dev.moui.galaxycraft.client.StationClient;
import dev.moui.galaxycraft.shadow.ShadowMap;
import dev.moui.galaxycraft.shadow.ShadowWorld;
import dev.moui.galaxycraft.station.PackedStationItem;
import dev.moui.galaxycraft.station.StationBlocks;
import dev.moui.galaxycraft.voxel.FlatGrid;
import dev.moui.galaxycraft.voxel.Material;
import dev.moui.galaxycraft.voxel.PlanetSession;
import dev.moui.galaxycraft.voxel.Station;
import dev.moui.galaxycraft.voxel.StationShape;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import org.joml.Quaterniond;
import org.joml.Vector3d;

/**
 * A station's whole life without the game (-PgalaxycraftStation): a Station Core makes the slab,
 * wheat grows on it (Minecraft runs it in the shadow), a chest keeps diamonds, building outward
 * regrows its grid, the core never breaks, packing makes an item, and unfolding it far off brings
 * everything back. Prints "[GalaxyCraft station] PASS" or FAIL with what went wrong.
 */
public final class StationProbe implements FabricClientGameTest {
    /** Galaxy units per block in a station's session. */
    private static final double UNITS = 80;
    private final List<String> fails = new ArrayList<>();
    private McBlocks blocks;
    private PlanetSession s;
    private ShadowLink link;
    private Vector3d mario;

    @Override
    public void runTest(ClientGameTestContext ctx) {
        if (!Boolean.getBoolean("galaxycraft.station")) return;
        try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
            sp.getServer().runCommand("time set day");
            sp.getServer().runCommand("gamerule random_tick_speed 1000");
            ctx.waitTicks(20);
            boolean recipe = ctx.computeOnClient(mc -> mc.getSingleplayerServer().getRecipeManager()
                    .byKey(ResourceKey.create(Registries.RECIPE, Identifier.fromNamespaceAndPath("galaxycraft", "station_core"))).isPresent());
            if (!recipe) fails.add("no recipe");
            ctx.runOnClient(mc -> {
                blocks = McBlocks.create(mc);
                Path dir = Path.of(System.getProperty("galaxycraft.planetDir", "build/test-planets")).resolve("station-probe");
                deleteAll(dir);
                StationClient.testSetup(dir, "probe", blocks);
                link = new ShadowLink(() -> s);
                var server = mc.getSingleplayerServer();
                server.execute(() -> {
                    var p = server.getPlayerList().getPlayers().get(0);
                    p.setGameMode(GameType.SURVIVAL);
                    p.getInventory().clearContent();
                    p.getInventory().setItem(0, new ItemStack(StationBlocks.CORE_ITEM));
                });
            });
            ctx.waitTicks(5);

            // 1. The core in open space: the slab, the core in its middle.
            ctx.runOnClient(mc -> {
                s = StationClient.place(mc.player, InteractionHand.MAIN_HAND,
                        new StationClient.Spot(new Vector3d(0, 8000, 0), new Quaterniond()));
                if (s == null) return;
                mario = s.galOf(new Vector3d(0, 1, 0));
            });
            if (s == null) {
                finish("no station");
                return;
            }
            Station st = station(ctx);
            log("up: " + st.id + ", " + ctx.computeOnClient(mc -> st.blockCount()) + " blocks");
            if (ctx.computeOnClient(mc -> st.blockCount()) != 81) fails.add("starter not 81 blocks");
            if (!ctx.computeOnClient(mc -> StationClient.isCore(s, st.grid().cellOf(0, 0, 0)))) fails.add("no core");
            run(ctx, 10);
            int cores = ctx.computeOnClient(mc -> mc.player.getInventory().countItem(StationBlocks.CORE_ITEM));
            if (cores != 0) fails.add("core not used up");

            // 2. A farm: farmland, water beside it, wheat on it; Minecraft grows it.
            put(ctx, st, 2, 1, 0, "minecraft:farmland[moisture=7]");
            ctx.runOnClient(mc -> {
                int c = st.grid().cellOf(3, 1, 0);
                s.planet().set(c, Material.WATER);
                StationClient.afterPlace(s, c);
            });
            put(ctx, st, 2, 2, 0, "minecraft:wheat[age=0]");
            // 3. A chest with diamonds, in the shadow's block entity.
            put(ctx, st, 4, 1, 0, "minecraft:chest[facing=north]");
            run(ctx, 60);
            boolean stocked = withChest(ctx, st, chest -> chest.setItem(0, new ItemStack(Items.DIAMOND, 5)));
            if (!stocked) fails.add("no chest in the shadow");
            for (int t = 0; t < 1200 && !name(ctx, st, 2, 2, 0).contains("age=7"); t += 20) run(ctx, 20);
            log("wheat " + name(ctx, st, 2, 2, 0));
            if (!name(ctx, st, 2, 2, 0).contains("age=7")) fails.add("wheat did not grow");

            // 4. Building outward until the grid regrows: every block stays where it was.
            int before = StationClient.regrows();
            for (int x = 5; x < 60 && StationClient.regrows() == before; x++) put(ctx, st, x, 0, 0, "minecraft:stone");
            run(ctx, 20);
            log("regrows " + (StationClient.regrows() - before) + ", grid " + ctx.computeOnClient(mc -> st.grid().n));
            if (StationClient.regrows() == before) fails.add("never regrew");
            if (!name(ctx, st, 2, 2, 0).contains("wheat")) fails.add("wheat lost in the regrow");
            if (!name(ctx, st, 4, 1, 0).contains("chest")) fails.add("chest lost in the regrow");
            int[] count = new int[1];
            withChest(ctx, st, chest -> count[0] = chest.getItem(0).getCount());
            if (count[0] != 5) fails.add("diamonds after the regrow: " + count[0]);

            // 5. The core never breaks.
            ctx.runOnClient(mc -> ShadowWorld.destroy(s.planet(), st.grid().cellOf(0, 0, 0), mc.player.getUUID()));
            run(ctx, 20);
            if (!name(ctx, st, 0, 0, 0).contains("station_core")) fails.add("core broke: " + name(ctx, st, 0, 0, 0));

            // 6. Past the maximum width: taken back.
            boolean kept = ctx.computeOnClient(mc -> {
                st.bounds = new StationShape.Bounds(st.bounds.x1() - 255, st.bounds.y0(), st.bounds.z0(), st.bounds.x1(), st.bounds.y1(), st.bounds.z1());
                int c = st.grid().cellOf(st.bounds.x1() + 1, 0, 2);
                s.planet().set(c, blocks.parse("minecraft:stone"));
                return StationClient.afterPlace(s, c) || s.planet().get(c) != dev.moui.galaxycraft.voxel.Blocks.AIR;
            });
            if (kept) fails.add("a block past the maximum stayed");
            ctx.runOnClient(mc -> st.bounds = new StationShape.Bounds(-4, 0, -4, st.bounds.x1(), 2, 4));

            // 7. Pack up: out of space, an item in the inventory.
            ctx.runOnClient(mc -> StationClient.pack(s));
            run(ctx, 20);
            ItemStack packed = ctx.computeOnClient(mc -> {
                var inv = mc.player.getInventory();
                for (int i = 0; i < inv.getContainerSize(); i++) if (inv.getItem(i).is(StationBlocks.PACKED)) return inv.getItem(i).copy();
                return ItemStack.EMPTY;
            });
            log("packed: " + packed + " " + packed.getHoverName().getString());
            if (packed.isEmpty() || !PackedStationItem.id(packed).orElse("").equals(st.id)) fails.add("no packed item");
            if (!StationClient.sessions().isEmpty()) fails.add("still in space after packing");

            // 8. Unfolded 300 blocks off, turned: the farm, the chest and the row are there.
            ctx.runOnClient(mc -> {
                s = StationClient.unfold(mc.player, InteractionHand.MAIN_HAND, st.id,
                        new StationClient.Spot(new Vector3d(300 * UNITS, 8000, 0), new Quaterniond().rotateY(1)));
                if (s != null) mario = s.galOf(new Vector3d(0, 1, 0));
            });
            if (s == null) {
                finish("did not unfold");
                return;
            }
            Station back = station(ctx);
            run(ctx, 60);
            if (!name(ctx, back, 2, 2, 0).contains("age=7")) fails.add("wheat after unfolding: " + name(ctx, back, 2, 2, 0));
            if (!name(ctx, back, 6, 0, 0).contains("stone")) fails.add("row after unfolding");
            count[0] = 0;
            withChest(ctx, back, chest -> count[0] = chest.getItem(0).getCount());
            if (count[0] != 5) fails.add("diamonds after unfolding: " + count[0]);
            finish(null);
        }
    }

    private Station station(ClientGameTestContext ctx) {
        return ctx.computeOnClient(mc -> StationClient.of(s).orElseThrow());
    }

    /** A block at a station coordinate, as a placement would: the station grows (and regrows). */
    private void put(ClientGameTestContext ctx, Station st, int x, int y, int z, String state) {
        ctx.runOnClient(mc -> {
            int c = st.grid().cellOf(x, y, z);
            if (c < 0) {
                fails.add("no cell at " + x + "," + y + "," + z);
                return;
            }
            s.planet().set(c, blocks.parse(state));
            StationClient.afterPlace(s, c);
        });
        run(ctx, 1);
    }

    private String name(ClientGameTestContext ctx, Station st, int x, int y, int z) {
        return ctx.computeOnClient(mc -> {
            int c = st.grid().cellOf(x, y, z);
            return c < 0 ? "none" : blocks.name(s.planet().get(c));
        });
    }

    /** The chest at (4, 1, 0) in the shadow, on the server thread; false if there is none. */
    private boolean withChest(ClientGameTestContext ctx, Station st, java.util.function.Consumer<ChestBlockEntity> use) {
        boolean[] found = new boolean[1];
        ctx.runOnClient(mc -> {
            ShadowMap map = ShadowMap.of(s.planet(), "station-" + st.id);
            FlatGrid g = st.grid();
            int c = g.cellOf(4, 1, 0);
            BlockPos pos = new BlockPos(map.x(c), map.y(c), map.z(c));
            var server = mc.getSingleplayerServer();
            server.submit(() -> {
                var level = server.getLevel(ShadowWorld.KEY);
                if (level != null && level.getBlockEntity(pos) instanceof ChestBlockEntity chest) {
                    use.accept(chest);
                    found[0] = true;
                }
            }).join();
        });
        return found[0];
    }

    private void run(ClientGameTestContext ctx, int ticks) {
        for (int t = 0; t < ticks; t++) {
            ctx.runOnClient(mc -> {
                if (s != null && s.active()) link.tick("station-" + StationClient.of(s).map(x -> x.id).orElse(""), mario);
            });
            ctx.waitTick();
        }
    }

    private void finish(String fatal) {
        if (fatal != null) fails.add(fatal);
        log(fails.isEmpty() ? "PASS" : "FAIL " + fails);
    }

    private static void log(String s) {
        System.out.println("[GalaxyCraft station] " + s);
    }

    private static void deleteAll(Path dir) {
        try (var all = Files.walk(dir)) {
            all.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        } catch (java.io.IOException e) {
            // nothing there
        }
    }
}
