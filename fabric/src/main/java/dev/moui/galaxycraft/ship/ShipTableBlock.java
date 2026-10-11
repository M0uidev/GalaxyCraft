package dev.moui.galaxycraft.ship;

import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

/** Right click assembles a ship from the best module of each kind in the inventory. */
public final class ShipTableBlock extends Block {
    static final String TIER = "tier";

    public ShipTableBlock(Properties properties) {
        super(properties);
    }

    /** A module item's tier: 1 unless its data says otherwise, kept in 1..3. */
    static int tier(ItemStack s) {
        CustomData d = s.get(DataComponents.CUSTOM_DATA);
        int t = d == null ? 1 : d.copyTag().getIntOr(TIER, 1);
        return Math.max(1, Math.min(ShipModules.MAX_TIER, t));
    }

    /**
     * The ship the inventory's modules make, with one of each module used up; empty (nothing used)
     * if there is no engine or no cabin.
     */
    public static Optional<ShipModules> assemble(Inventory inv) {
        Item[] kinds = {ShipBlocks.HULL, ShipBlocks.ENGINE, ShipBlocks.RUDDER, ShipBlocks.CABIN};
        int[] tiers = new int[4];
        int[] slots = {-1, -1, -1, -1};
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            for (int k = 0; k < 4; k++)
                if (!s.isEmpty() && s.is(kinds[k]) && tier(s) > tiers[k]) {
                    tiers[k] = tier(s);
                    slots[k] = i;
                }
        }
        ShipModules m = new ShipModules(tiers[0], tiers[1], tiers[2], tiers[3]);
        if (!m.valid()) return Optional.empty();
        for (int k = 0; k < 4; k++) if (slots[k] >= 0) inv.removeItem(slots[k], 1);
        return Optional.of(m);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (level.isClientSide()) return InteractionResult.SUCCESS;
        Optional<ShipModules> m = assemble(player.getInventory());
        if (m.isEmpty()) {
            player.sendOverlayMessage(Component.translatable("message.galaxycraft.ship.needs_engine_cabin"));
            return InteractionResult.CONSUME;
        }
        ItemStack ship = ToadShipItem.stack(m.get());
        if (!player.getInventory().add(ship)) player.spawnAtLocation((net.minecraft.server.level.ServerLevel) level, ship);
        player.sendOverlayMessage(Component.translatable("message.galaxycraft.ship.assembled"));
        return InteractionResult.CONSUME;
    }
}
