package dev.moui.galaxycraft.toad;

/** What a Toad is: where it lives and what it sells. Data rows, one class for all. */
public enum ToadType {
    GUIDE("guide", "KinopioGoodsGlasses"),
    MINER("miner", "KinopioGoodsPickel"),
    EXPLORER("explorer", "KinopioGoodsRucksack"),
    TECHNICIAN("technician", "KinopioGoodsSnorkel"),
    MAILMAN("mailman", "KinopioGoodsMailBag");

    private final String id;
    private final String accessory;

    ToadType(String id, String accessory) {
        this.id = id;
        this.accessory = accessory;
    }

    public String id() {
        return id;
    }

    /** The disc arc of its accessory (without .arc), or null. */
    public String accessory() {
        return accessory;
    }
}
