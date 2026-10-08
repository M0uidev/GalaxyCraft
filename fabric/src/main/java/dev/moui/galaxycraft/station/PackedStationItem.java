package dev.moui.galaxycraft.station;

import java.util.Optional;
import java.util.function.Consumer;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.level.Level;

/**
 * A station packed up: the item holds only its id (its file holds the rest), its name and size.
 * Used in open space it unfolds there.
 */
public final class PackedStationItem extends Item {
    static final String ID = "station", SIZE = "size", BLOCKS = "blocks";

    public PackedStationItem(Properties properties) {
        super(properties);
    }

    /** The item of a packed station. */
    public static ItemStack stack(String id, String name, int sx, int sy, int sz, int blocks) {
        ItemStack s = new ItemStack(StationBlocks.PACKED);
        CompoundTag tag = new CompoundTag();
        tag.putString(ID, id);
        tag.putIntArray(SIZE, new int[] {sx, sy, sz});
        tag.putInt(BLOCKS, blocks);
        s.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        s.set(DataComponents.CUSTOM_NAME, Component.literal(name));
        return s;
    }

    /** The station a packed item holds. */
    public static Optional<String> id(ItemStack s) {
        CustomData d = s.get(DataComponents.CUSTOM_DATA);
        return d == null ? Optional.empty() : d.copyTag().getString(ID);
    }

    @Override
    public InteractionResult use(Level level, Player player, InteractionHand hand) {
        if (!level.isClientSide()) return InteractionResult.PASS;
        Optional<String> id = id(player.getItemInHand(hand));
        return id.isPresent() && StationHooks.get().unfold(player, hand, id.get()) ? InteractionResult.SUCCESS : InteractionResult.PASS;
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, TooltipDisplay display, Consumer<Component> out, TooltipFlag flag) {
        CustomData d = stack.get(DataComponents.CUSTOM_DATA);
        if (d == null) return;
        CompoundTag tag = d.copyTag();
        tag.getIntArray(SIZE).filter(a -> a.length == 3).ifPresent(a -> out.accept(
                Component.translatable("item.galaxycraft.packed_station.size", a[0], a[2], a[1]).withStyle(ChatFormatting.GRAY)));
        tag.getInt(BLOCKS).ifPresent(n -> out.accept(Component.translatable("item.galaxycraft.packed_station.blocks", n).withStyle(ChatFormatting.GRAY)));
    }
}
