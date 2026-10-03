package dev.moui.galaxycraft.client;

import com.mojang.blaze3d.platform.NativeImage;
import dev.moui.galaxycraft.GalaxyCraft;
import dev.moui.galaxycraft.bridge.BridgeClient;
import dev.moui.galaxycraft.proto.Layout;
import dev.moui.galaxycraft.view.HeldItem;
import dev.moui.galaxycraft.voxel.BlockInfo;
import dev.moui.galaxycraft.voxel.CubeSphere;
import dev.moui.galaxycraft.voxel.ModelQuad;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.ItemTags;
import net.minecraft.tags.TagKey;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;

/**
 * What the player holds, told to the game so Steve holds it too (HeldItem): a block whose item is
 * the block itself by the faces of its model (top, sides, bottom, overlays and tints included);
 * anything else by its sprite (the texture of its item model's particle, which for a flat item is
 * the item's own: flowers, doors, torches), held as a tool if it is one.
 */
final class HeldClient {
    private static final List<TagKey<Item>> TOOLS =
            List.of(ItemTags.PICKAXES, ItemTags.AXES, ItemTags.SHOVELS, ItemTags.HOES, ItemTags.SWORDS);
    private static final HeldItem link = new HeldItem();
    /** Payloads by item: the sprite is read from the resources once. */
    private static final Map<Item, byte[]> payloads = new HashMap<>();

    private HeldClient() {}

    /** Client tick, linked to the game: sends what is held if the game does not have it yet. */
    static void tick(LocalPlayer player, BridgeClient bridge, int sceneId) {
        ItemStack stack = player.getMainHandItem();
        byte[] held = stack.isEmpty() ? HeldItem.none() : payloads.computeIfAbsent(stack.getItem(), i -> payload(stack));
        if (link.due(held, sceneId, bridge.hostPid()) && bridge.send(Layout.MSG_HELD, held))
            link.sent(held, sceneId, bridge.hostPid());
    }

    private static byte[] payload(ItemStack stack) {
        Identifier name = spriteName(stack);
        McBlocks blocks = PlanetClient.blocks();
        // An item drawn flat in Minecraft has its own texture under item/ (or is a plant's block texture).
        boolean flat = name == null || name.getPath().startsWith("item/");
        boolean cube = false; // no faces of its own on the cell's sides, but a full block (a chest is not)
        if (stack.getItem() instanceof BlockItem bi && blocks != null && !flat) {
            BlockInfo info = blocks.info(McBlocks.id(bi.getBlock().defaultBlockState()));
            int[] top = face(blocks, info, CubeSphere.TOP), side = face(blocks, info, CubeSphere.I_MINUS);
            int[] bottom = face(blocks, info, CubeSphere.BOTTOM);
            if (top != null && side != null && bottom != null) return HeldItem.block(top, side, bottom);
            cube = info.fullCollision();
        }
        int kind = cube ? HeldItem.CUBE
                : TOOLS.stream().anyMatch(stack::is) ? HeldItem.TOOL
                : HeldItem.ITEM;
        int[] argb = name == null ? null : sprite(name, stack);
        return argb == null ? HeldItem.none() : HeldItem.sprite(kind, argb);
    }

    /**
     * A face of a block as its model draws it on that side of the cell: each of its quads there,
     * tinted, over the last (grass: dirt, then the tinted overlay). Null if it draws nothing there.
     */
    private static int[] face(McBlocks blocks, BlockInfo info, int side) {
        int[] out = null;
        for (ModelQuad q : info.quads()) {
            if (q.cull() != side) continue;
            if (out == null) out = new int[HeldItem.SPRITE * HeldItem.SPRITE];
            int[] tile = blocks.tileImage(q.tile());
            for (int i = 0; i < out.length; i++) out[i] = over(tint(tile[i], q.tint()), out[i]);
        }
        return out;
    }

    private static int tint(int argb, int rgb) {
        int r = (argb >> 16 & 0xFF) * (rgb >> 16 & 0xFF) / 255, g = (argb >> 8 & 0xFF) * (rgb >> 8 & 0xFF) / 255;
        int b = (argb & 0xFF) * (rgb & 0xFF) / 255;
        return argb & 0xFF000000 | r << 16 | g << 8 | b;
    }

    /** src over dst (straight alpha). */
    private static int over(int src, int dst) {
        int sa = src >>> 24, da = dst >>> 24;
        if (sa == 255 || da == 0) return src;
        if (sa == 0) return dst;
        int a = sa + da * (255 - sa) / 255;
        int[] c = new int[3];
        for (int k = 0; k < 3; k++) {
            int shift = 16 - 8 * k;
            c[k] = ((src >> shift & 0xFF) * sa + (dst >> shift & 0xFF) * da * (255 - sa) / 255) / a;
        }
        return a << 24 | c[0] << 16 | c[1] << 8 | c[2];
    }

    /** The sprite of the stack's item model's particle, or null if it has none. */
    private static Identifier spriteName(ItemStack stack) {
        Minecraft mc = Minecraft.getInstance();
        ItemStackRenderState state = new ItemStackRenderState();
        mc.getItemModelResolver().updateForLiving(state, stack, ItemDisplayContext.THIRD_PERSON_RIGHT_HAND, mc.player);
        var particle = state.isEmpty() ? null : state.pickParticleMaterial(RandomSource.create(0));
        return particle == null ? null : particle.sprite().contents().name();
    }

    /** A sprite's 16×16 image (the first frame if animated), or null if it cannot be read. */
    private static int[] sprite(Identifier name, ItemStack stack) {
        Minecraft mc = Minecraft.getInstance();
        Identifier file = name.withPath(p -> "textures/" + p + ".png");
        try (InputStream in = mc.getResourceManager().open(file); NativeImage img = NativeImage.read(in)) {
            int size = img.getWidth(); // frames stack downward: the first is the top square
            int[] argb = new int[HeldItem.SPRITE * HeldItem.SPRITE];
            for (int y = 0; y < HeldItem.SPRITE; y++)
                for (int x = 0; x < HeldItem.SPRITE; x++)
                    argb[y * HeldItem.SPRITE + x] = img.getPixel(x * size / HeldItem.SPRITE, y * size / HeldItem.SPRITE);
            return argb;
        } catch (IOException e) {
            GalaxyCraft.LOG.warn("No sprite for {} in Steve's hand: {}", stack, e.toString());
            return null;
        }
    }
}
