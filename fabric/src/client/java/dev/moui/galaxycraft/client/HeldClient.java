package dev.moui.galaxycraft.client;

import com.mojang.blaze3d.platform.NativeImage;
import dev.moui.galaxycraft.GalaxyCraft;
import dev.moui.galaxycraft.bridge.BridgeClient;
import dev.moui.galaxycraft.proto.Layout;
import dev.moui.galaxycraft.view.HeldItem;
import dev.moui.galaxycraft.voxel.Material;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.ItemTags;
import net.minecraft.tags.TagKey;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;

/**
 * What the player holds, told to the game so Steve holds it too (HeldItem): a block of the planet
 * by its atlas tiles; anything else by its sprite (the texture of its model's particle, which for
 * a flat item is the item's own), held as a tool if it is one.
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
        Material m = Material.ofItem(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
        if (m != null && m.solid()) return HeldItem.block(m.top, m.side, m.bottom);
        int kind = stack.getItem() instanceof BlockItem ? HeldItem.CUBE
                : TOOLS.stream().anyMatch(stack::is) ? HeldItem.TOOL
                : HeldItem.ITEM;
        int[] argb = sprite(stack);
        return argb == null ? HeldItem.none() : HeldItem.sprite(kind, argb);
    }

    /** The 16×16 sprite of the stack's model (the first frame if animated), or null if it has none. */
    private static int[] sprite(ItemStack stack) {
        Minecraft mc = Minecraft.getInstance();
        ItemStackRenderState state = new ItemStackRenderState();
        mc.getItemModelResolver().updateForLiving(state, stack, ItemDisplayContext.THIRD_PERSON_RIGHT_HAND, mc.player);
        var particle = state.isEmpty() ? null : state.pickParticleMaterial(RandomSource.create(0));
        if (particle == null) return null;
        Identifier name = particle.sprite().contents().name();
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
