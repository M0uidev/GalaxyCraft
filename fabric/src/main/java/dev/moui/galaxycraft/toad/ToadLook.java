package dev.moui.galaxycraft.toad;

/**
 * What tells one type of Toad from another at a glance: its cap's colour (RGBA, multiplied over
 * the white cap), its vest's (RGB), the thing it carries (a KinopioGoods arc, without .arc) and the
 * joint it is on, and the animations (the Pickel ones have the arm that holds it).
 */
public record ToadLook(int cap, int vest, String goods, int joint, String idle, String talk) {
    /** Kinopio's joints that things are carried on: the body, the left arm (the Pickel's) and the head. */
    public static final int BODY = 1, ARM = 5, HEAD = 8;

    public static ToadLook of(ToadType type) {
        return switch (type) {
            case GUIDE -> new ToadLook(0xFFF2A0FF, 0xFFAA10, "KinopioGoodsGlasses", HEAD, "Wait", "Talk");
            case MINER -> new ToadLook(0xC87838FF, 0x7A4A22, "KinopioGoodsPickel", ARM, "WaitPickel", "TalkPickel");
            case EXPLORER -> new ToadLook(0x4CC24CFF, 0x2E7D32, "KinopioGoodsRucksack", BODY, "Wait", "Talk");
            case TECHNICIAN -> new ToadLook(0x4C8CFFFF, 0x1E4FB4, "KinopioGoodsSnorkel", HEAD, "Wait", "Talk");
            case MAILMAN -> new ToadLook(0xE24B4BFF, 0xB71C1C, "KinopioGoodsMailBag", BODY, "Wait", "Talk");
        };
    }
}
