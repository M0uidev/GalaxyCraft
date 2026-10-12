package dev.moui.galaxycraft.toad;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.trading.ItemCost;
import net.minecraft.world.item.trading.Merchant;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;

/**
 * A Toad as Minecraft's {@link Merchant}: no entity behind it (Toads are records), only its offers and
 * the player trading. MerchantMenu takes any Merchant, so the vanilla trading screen is the Toad's.
 */
public final class ToadMerchant implements Merchant {
    /** Uses of one offer before the Toad is out of it (it is not restocked). */
    static final int MAX_USES = 16;

    private final MerchantOffers offers;
    private Player trading;

    private ToadMerchant(MerchantOffers offers) {
        this.offers = offers;
    }

    /** A merchant with what a type sells in a system of the tier; null if it sells nothing there (no empty screen). */
    public static ToadMerchant of(ToadType type, int tier) {
        MerchantOffers out = new MerchantOffers();
        for (ToadOffers.Offer o : ToadOffers.upTo(type, tier)) {
            Item sell = item(o.sell());
            if (sell == null) continue;
            for (ToadOffers.Price p : o.prices()) {
                Item pay = item(p.item());
                if (pay == null) continue;
                out.add(new MerchantOffer(new ItemCost(pay, p.count()), new ItemStack(sell, o.sellCount()), MAX_USES, 0, 0f));
            }
        }
        return out.isEmpty() ? null : new ToadMerchant(out);
    }

    private static Item item(String id) {
        Identifier key = Identifier.tryParse(id);
        return key == null || !BuiltInRegistries.ITEM.containsKey(key) ? null : BuiltInRegistries.ITEM.getValue(key);
    }

    /** The screen's title. */
    public static Component title(ToadType type) {
        return Component.translatable("toad.name." + type.id());
    }

    @Override public void setTradingPlayer(Player player) {
        trading = player;
    }

    @Override public Player getTradingPlayer() {
        return trading;
    }

    @Override public MerchantOffers getOffers() {
        return offers;
    }

    @Override public void overrideOffers(MerchantOffers o) {}

    @Override public void notifyTrade(MerchantOffer offer) {
        offer.increaseUses();
    }

    @Override public void notifyTradeUpdated(ItemStack stack) {}

    @Override public int getVillagerXp() {
        return 0;
    }

    @Override public void overrideXp(int xp) {}

    @Override public boolean showProgressBar() {
        return false;
    }

    @Override public SoundEvent getNotifyTradeSound() {
        return SoundEvents.VILLAGER_YES;
    }

    @Override public boolean isClientSide() {
        return false;
    }

    @Override public boolean stillValid(Player player) {
        return trading == player;
    }
}
