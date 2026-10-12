package dev.moui.galaxycraft.input;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Default keys of the mod's own actions (the Controls screen lists and remaps them), and the keys
 * Minecraft itself binds by default, so a test can tell the two apart. Key names as Minecraft's translation
 * keys spell them without "key.keyboard." ("f6", "left.shift"), lowercase, so nothing of Minecraft loads.
 */
public final class ModKeyDefaults {
    /** Action -> default key (Controls category "Super Minecraft Galaxy"; the music player's keys are in MusicKeys). */
    public static final Map<String, String> MOD = new LinkedHashMap<>();
    /** Vanilla's default keys by action, for the clash test. */
    public static final Map<String, String> VANILLA = new LinkedHashMap<>();

    static {
        MOD.put("map", "n");
        MOD.put("warp", "k");
        MOD.put("movement", "f6");
        MOD.put("teleport", "p");
        MOD.put("ship_down", "z");
        MOD.put("music.open", "m");

        VANILLA.put("forward", "w");
        VANILLA.put("left", "a");
        VANILLA.put("back", "s");
        VANILLA.put("right", "d");
        VANILLA.put("jump", "space");
        VANILLA.put("sneak", "left.shift");
        VANILLA.put("sprint", "left.control");
        VANILLA.put("inventory", "e");
        VANILLA.put("drop", "q");
        VANILLA.put("swap_offhand", "f");
        VANILLA.put("chat", "t");
        VANILLA.put("command", "slash");
        VANILLA.put("player_list", "tab");
        VANILLA.put("advancements", "l");
        VANILLA.put("social", "j");
        VANILLA.put("load_hotbar", "c");
        VANILLA.put("save_hotbar", "x");
        VANILLA.put("hide_gui", "f1");
        VANILLA.put("screenshot", "f2");
        VANILLA.put("debug", "f3");
        VANILLA.put("perspective", "f5");
        VANILLA.put("fullscreen", "f11");
    }

    private ModKeyDefaults() {}
}
