package dev.moui.galaxycraft.client;

import com.mojang.blaze3d.platform.InputConstants;
import dev.moui.galaxycraft.input.ModKeyDefaults;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.resources.Identifier;

/**
 * The mod's own actions as Minecraft key mappings (Options > Controls > Super Minecraft Galaxy):
 * remappable, and the screen flags a clash with another key. Defaults are in {@link ModKeyDefaults},
 * which a test keeps clear of Minecraft's own and of the music player's. Mario's stick keys
 * (WASD, Space, Shift, Ctrl) are the game's and are not here.
 */
public final class ModKeys {
    private static final KeyMapping.Category CATEGORY = KeyMapping.Category.register(Identifier.parse("galaxycraft:general"));

    /** Opens the galaxy map. */
    static final KeyMapping MAP = key("map");
    /** Warps to the star in the crosshair (the map when there is none). */
    static final KeyMapping WARP = key("warp");
    /** Switches Mario's movement and Minecraft's. */
    static final KeyMapping MOVEMENT = key("movement");
    /** Teleports Mario to the planet in focus. */
    static final KeyMapping TELEPORT = key("teleport");
    /** Flying a ship: down, and docking on a landing pad. */
    static final KeyMapping SHIP_DOWN = key("ship_down");
    /** Flying a ship: hold to see the whole panel (modules and numbers). */
    static final KeyMapping SHIP_INFO = key("ship_info");

    private ModKeys() {}

    private static KeyMapping key(String action) {
        int code = InputConstants.getKey("key.keyboard." + ModKeyDefaults.MOD.get(action)).getValue();
        return KeyMappingHelper.registerKeyMapping(new KeyMapping("key.galaxycraft.general." + action, code, CATEGORY));
    }

    /** What the player has the key on, for messages ("K", "F6"). */
    static String label(KeyMapping k) {
        return k.getTranslatedKeyMessage().getString();
    }

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            while (MAP.consumeClick()) if (mc.gui.screen() == null) Warp.map(mc);
            while (WARP.consumeClick()) if (mc.gui.screen() == null) Warp.key(mc);
            while (MOVEMENT.consumeClick()) if (mc.gui.screen() == null) GalaxyCraftClient.toggleMovement();
        });
    }
}
