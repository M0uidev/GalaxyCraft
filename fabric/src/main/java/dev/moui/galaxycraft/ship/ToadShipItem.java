package dev.moui.galaxycraft.ship;

import java.util.Optional;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.Level;

/** An assembled ship, carried as an item: its modules are in its data. Used, it is put down. */
public final class ToadShipItem extends Item {
    static final String HULL = "hull", ENGINE = "engine", RUDDER = "rudder", CABIN = "cabin";

    public ToadShipItem(Properties properties) {
        super(properties);
    }

    public static ItemStack stack(ShipModules m) {
        ItemStack s = new ItemStack(ShipBlocks.TOAD_SHIP);
        CompoundTag tag = new CompoundTag();
        tag.putInt(HULL, m.hull());
        tag.putInt(ENGINE, m.engine());
        tag.putInt(RUDDER, m.rudder());
        tag.putInt(CABIN, m.cabin());
        s.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        return s;
    }

    /** The modules a ship item holds (repaired: an item from a damaged save is still a ship). */
    public static Optional<ShipModules> modules(ItemStack s) {
        if (!s.is(ShipBlocks.TOAD_SHIP)) return Optional.empty();
        CustomData d = s.get(DataComponents.CUSTOM_DATA);
        if (d == null) return Optional.of(new ShipModules(0, 1, 0, 1));
        CompoundTag t = d.copyTag();
        return Optional.of(new ShipModules(t.getIntOr(HULL, 0), t.getIntOr(ENGINE, 1),
                t.getIntOr(RUDDER, 0), t.getIntOr(CABIN, 1)).repaired());
    }

    @Override
    public InteractionResult use(Level level, Player player, InteractionHand hand) {
        if (!level.isClientSide()) return InteractionResult.PASS; // the client decides and asks the server to use it up
        return modules(player.getItemInHand(hand))
                .filter(m -> ShipHooks.get().place(player, hand, m))
                .isPresent() ? InteractionResult.SUCCESS : InteractionResult.PASS;
    }
}
