package dev.moui.galaxycraft.gametest;

import dev.moui.galaxycraft.client.McBlocks;
import dev.moui.galaxycraft.client.MpLive;
import dev.moui.galaxycraft.client.ShadowLink;
import dev.moui.galaxycraft.shadow.LiveBlocks;
import dev.moui.galaxycraft.shadow.ShadowColumns;
import dev.moui.galaxycraft.shadow.ShadowMap;
import dev.moui.galaxycraft.shadow.ShadowWorld;
import dev.moui.galaxycraft.voxel.PlanetSession;
import dev.moui.galaxycraft.voxel.VoxelPlanet;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BannerBlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import net.minecraft.world.level.block.entity.SignTextSlot;
import org.joml.Vector3d;

/**
 * What a guest sees of the host's live blocks (with ShadowProbe, -PgalaxycraftBlocks): a sign, a banner and a chest on
 * a planet that runs only for a guest. The host's wire lists them, the message to the guest carries them, and
 * the guest builds block entities that read like the host's (the sign's text, the banner, a lid that opens);
 * later only what changes is sent, and a broken block leaves.
 */
public final class LiveWireProbe implements FabricClientGameTest {
    private static final int INDEX = 5;
    private PlanetSession a, b;
    private McBlocks blocks;
    private UUID guest;
    private final List<String> fails = new ArrayList<>();

    @Override
    public void runTest(ClientGameTestContext ctx) {
        if (!Boolean.getBoolean("galaxycraft.blocks")) return;
        try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
            ctx.waitTicks(20);
            ShadowLink[] link = new ShadowLink[1];
            int[] cells = ctx.computeOnClient(mc -> {
                blocks = McBlocks.create(mc);
                a = new PlanetSession(1);
                a.setBlocks(blocks);
                a.spawn(32, new Vector3d(), new Vector3d(0, 1, 0));
                b = new PlanetSession(1);
                b.setBlocks(blocks);
                b.spawn(24, new Vector3d(), new Vector3d(0, 1, 0));
                guest = mc.player.getUUID();
                link[0] = new ShadowLink(() -> a);
                return new int[] {place(b, "oak_sign", 2), place(b, "white_banner", 4), place(b, "chest", 6)};
            });
            VoxelPlanet pB = b.planet();
            Vector3d mario = new Vector3d(0, a.planet().surface() + 0.5, 0);
            for (int t = 0; t < 60; t++) {
                ctx.runOnClient(mc -> link[0].tick("probe", mario));
                ctx.waitTick();
            }
            Vector3d feetB = new Vector3d(0, pB.surface() + 0.5, 0);
            ctx.runOnClient(mc -> {
                ShadowWorld.runFor(pB, "mp1");
                pB.addObserver((cell, id) -> ShadowWorld.setQuiet(pB, cell, id));
                ShadowWorld.guest(guest, new ShadowWorld.MarioAt(pB, feetB, new Vector3d(0, -1, 0), guest));
            });
            run(ctx, 3);
            ctx.runOnClient(mc -> {
                ShadowMap m = ShadowWorld.mapOf(pB);
                if (m != null) ShadowWorld.follow(pB, guest, ShadowColumns.near(pB, m, feetB, 48), pB.grid.cellAt(feetB));
            });
            run(ctx, 80);

            // The sign gets its text and the chest opens, in the shadow.
            onShadow(ctx, pB, cells[0], be -> {
                if (be instanceof SignBlockEntity s)
                    s.setText(new SignText(List.of(Component.literal("Hola"), Component.literal("amigo"), Component.empty(), Component.empty()),
                            List.of(Component.empty(), Component.empty(), Component.empty(), Component.empty()), DyeColor.BLACK, false), SignTextSlot.FRONT);
            });
            onShadow(ctx, pB, cells[2], be -> be.triggerEvent(1, 1));
            run(ctx, 30);

            // (1) the host's wire has all three
            int wired = ctx.computeOnClient(mc -> ShadowWorld.liveWireOf(pB).size());
            System.out.println("[GalaxyCraft livewire] host wire: " + wired);
            if (wired != 3) fails.add("host wire has " + wired + " blocks, not 3");

            // (2) the first message tells all; the guest builds them
            ctx.runOnClient(mc -> MpLive.reset());
            List<byte[]> first = ctx.computeOnClient(mc -> MpLive.messages(pB, INDEX, Set.of(guest)).get(guest));
            if (first == null || first.isEmpty()) {
                fails.add("nothing was told");
            } else {
                System.out.println("[GalaxyCraft livewire] first message: " + first.size() + " bodies, " + first.get(0).length + " bytes");
                ctx.runOnClient(mc -> {
                    for (byte[] body : first) MpLive.receive(body, true);
                });
                run(ctx, 20);
                LiveBlocks.Snapshot snap = ctx.computeOnClient(mc -> MpLive.snapshot(INDEX, pB, blocks));
                int signs = 0, banners = 0, chests = 0;
                float open = 0;
                String text = "";
                if (snap != null)
                    for (LiveBlocks.Live l : snap.list()) {
                        if (l.entity() instanceof SignBlockEntity s) {
                            signs++;
                            text = s.getText(SignTextSlot.FRONT).getMessages(false).get(0).getString() + "/" + s.getText(SignTextSlot.FRONT).getMessages(false).get(1).getString();
                        } else if (l.entity() instanceof BannerBlockEntity) banners++;
                        else if (l.entity() instanceof ChestBlockEntity c) {
                            chests++;
                            open = c.getOpenNess(1f);
                        }
                    }
                System.out.println("[GalaxyCraft livewire] guest: signs=" + signs + " banners=" + banners + " chests=" + chests + " text=" + text + " open=" + open);
                if (signs != 1 || banners != 1 || chests != 1) fails.add("guest built " + signs + "/" + banners + "/" + chests);
                if (!text.equals("Hola/amigo")) fails.add("sign text " + text);
                if (open < 0.5f) fails.add("the chest lid is shut on the guest: " + open);
            }

            // (3) nothing changed: nothing is told (the chest lid has settled)
            run(ctx, 30);
            ctx.runOnClient(mc -> MpLive.messages(pB, INDEX, Set.of(guest))); // let the lid's last steps through
            run(ctx, 12);
            Map<UUID, List<byte[]>> quiet = ctx.computeOnClient(mc -> MpLive.messages(pB, INDEX, Set.of(guest)));
            System.out.println("[GalaxyCraft livewire] quiet: " + quiet.size());
            if (!quiet.isEmpty()) fails.add("told again though nothing changed");

            // (4) a changed sign is told alone; a broken banner leaves
            onShadow(ctx, pB, cells[0], be -> {
                if (be instanceof SignBlockEntity s)
                    s.setText(new SignText(List.of(Component.literal("Adios"), Component.empty(), Component.empty(), Component.empty()),
                            List.of(Component.empty(), Component.empty(), Component.empty(), Component.empty()), DyeColor.BLACK, false), SignTextSlot.FRONT);
            });
            ctx.runOnClient(mc -> pB.set(cells[1], 0));
            run(ctx, 12);
            List<byte[]> second = ctx.computeOnClient(mc -> MpLive.messages(pB, INDEX, Set.of(guest)).get(guest));
            var decoded = second == null || second.isEmpty() ? null : dev.moui.galaxycraft.net.LiveCodec.decode(second.get(0));
            System.out.println("[GalaxyCraft livewire] second: " + (decoded == null ? "none" : decoded.upserts().size() + " changed, " + decoded.removed().length + " gone, full=" + decoded.full()));
            if (decoded == null || decoded.full() || decoded.upserts().size() != 1 || decoded.removed().length != 1) fails.add("delta is not 1 changed + 1 gone");
            if (second != null) ctx.runOnClient(mc -> {
                for (byte[] body : second) MpLive.receive(body, true);
            });
            LiveBlocks.Snapshot snap2 = ctx.computeOnClient(mc -> MpLive.snapshot(INDEX, pB, blocks));
            boolean sign = false;
            int others = 0;
            if (snap2 != null)
                for (LiveBlocks.Live l : snap2.list()) {
                    if (l.entity() instanceof SignBlockEntity s) sign = s.getText(SignTextSlot.FRONT).getMessages(false).get(0).getString().equals("Adios");
                    else others++;
                }
            if (!sign) fails.add("the sign did not change on the guest");
            if (others != 1) fails.add("after the banner broke " + others + " other blocks are live, not 1 (the chest)");

            // (5) the guest leaves and comes back: told everything again
            ctx.runOnClient(mc -> MpLive.messages(pB, INDEX, Set.of()));
            List<byte[]> again = ctx.computeOnClient(mc -> MpLive.messages(pB, INDEX, Set.of(guest)).get(guest));
            var d3 = again == null || again.isEmpty() ? null : dev.moui.galaxycraft.net.LiveCodec.decode(again.get(0));
            if (d3 == null || !d3.full() || d3.upserts().size() != 2) fails.add("coming back is not a full message of 2: " + (d3 == null ? "none" : d3.upserts().size()));

            System.out.println("[GalaxyCraft livewire] " + (fails.isEmpty() ? "PASS" : "FAIL " + fails));
        }
    }

    private void onShadow(ClientGameTestContext ctx, VoxelPlanet p, int cell, java.util.function.Consumer<net.minecraft.world.level.block.entity.BlockEntity> what) {
        ctx.runOnClient(mc -> {
            var server = mc.getSingleplayerServer();
            server.execute(() -> {
                BlockPos pos = ShadowWorld.shadowPos(p, cell);
                var level = server.getLevel(ShadowWorld.KEY);
                var be = pos == null || level == null ? null : level.getBlockEntity(pos);
                if (be == null) fails.add("no block entity at cell " + cell);
                else what.accept(be);
            });
        });
        run(ctx, 2);
    }

    private int place(PlanetSession s, String name, int k) {
        var p = s.planet();
        var item = BuiltInRegistries.ITEM.getValue(Identifier.withDefaultNamespace(name));
        double ang = (k - 8 + 0.5) / p.surface();
        Vector3d up = new Vector3d(Math.sin(ang), Math.cos(ang), 0.02).normalize();
        Vector3d ahead = new Vector3d(Math.cos(ang), -Math.sin(ang), 0);
        Vector3d at = new Vector3d(up).mul(p.surface());
        Vector3d eye = new Vector3d(up).mul(2.5).add(at).sub(new Vector3d(ahead).mul(0.6)).add(s.center());
        Vector3d look = new Vector3d(up).negate().add(new Vector3d(ahead).mul(0.25)).normalize();
        if (!s.placeBlock(eye, look, blocks.placer(new ItemStack(item)), new Vector3d(0, -100, 0).add(s.center())))
            fails.add(name + " not placed");
        return p.grid.cellAt(new Vector3d(up).mul(p.surface() + 0.5));
    }

    /** Ticks: what the shadow changed goes to its planet. */
    private void run(ClientGameTestContext ctx, int ticks) {
        for (int t = 0; t < ticks; t++) {
            ctx.runOnClient(mc -> {
                for (ShadowWorld.Change c; (c = ShadowWorld.pollChange()) != null; )
                    if (c.planet() == b.planet()) b.planet().set(c.cell(), c.id());
                for (ShadowWorld.Drop d; (d = ShadowWorld.pollDrop()) != null; ) {}
                for (Runnable r; (r = ShadowWorld.pollClient()) != null; ) r.run();
                MpLive.tick();
            });
            ctx.waitTick();
        }
    }
}
