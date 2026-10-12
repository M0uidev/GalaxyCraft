package dev.moui.galaxycraft.toad;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class ToadOffersTest {
    @Test void nothingIsSoldAboveItsTierNorPaidAboveIt() {
        for (ToadType t : ToadType.values())
            for (int tier = 0; tier <= 5; tier++)
                for (ToadOffers.Offer o : ToadOffers.forType(t, tier)) {
                    assertEquals(tier, o.tier());
                    assertTrue(ItemTiers.tier(o.sell()) >= 0, "unknown item " + o.sell());
                    assertTrue(ItemTiers.tier(o.sell()) <= tier, o.sell() + " sold below its tier");
                    boolean near = false;
                    for (ToadOffers.Price p : o.prices()) {
                        int pt = ItemTiers.tier(p.item());
                        assertTrue(pt >= 0, "unknown item " + p.item());
                        assertTrue(pt <= tier, p.item() + " asked above tier " + tier);
                        near |= pt >= tier - 1;
                    }
                    assertTrue(near, "an offer must ask for something of its tier or the one before");
                }
    }

    @Test void everyTypeSellsSomethingAtTierZeroOrSaysNothing() {
        assertFalse(ToadOffers.forType(ToadType.GUIDE, 0).isEmpty());
        assertEquals(ToadOffers.forType(ToadType.GUIDE, 0), ToadOffers.forType(ToadType.GUIDE, -3));
        assertEquals(ToadOffers.forType(ToadType.MINER, 5), ToadOffers.forType(ToadType.MINER, 99));
    }

    @Test void anUnknownItemHasNoTier() {
        assertEquals(-1, ItemTiers.tier("minecraft:not_an_item"));
        assertEquals(0, ItemTiers.tier("minecraft:coal"));
        assertEquals(2, ItemTiers.tier("minecraft:diamond"));
    }

    @Test void upToCollectsEveryTierUpToTheOne() {
        var m1 = ToadOffers.upTo(ToadType.MINER, 1);
        assertEquals(ToadOffers.forType(ToadType.MINER, 0).size() + ToadOffers.forType(ToadType.MINER, 1).size(), m1.size());
        assertEquals(ToadOffers.forType(ToadType.MINER, 0), ToadOffers.upTo(ToadType.MINER, 0));
        assertTrue(ToadOffers.upTo(ToadType.TECHNICIAN, 1).isEmpty(), "nothing is sold below its tier: a Technician has nothing at tier 1");
        assertFalse(ToadOffers.upTo(ToadType.TECHNICIAN, 2).isEmpty());
    }
}
