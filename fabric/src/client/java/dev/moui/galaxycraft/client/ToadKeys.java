package dev.moui.galaxycraft.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.resources.Identifier;

/**
 * The keys of a Toad's dialogue box: Enter goes on, Up and Down move among the choices (1 to 4 are
 * Minecraft's hotbar keys, read as they are). Rebindable in Controls.
 */
final class ToadKeys {
    private static final KeyMapping.Category CATEGORY = KeyMapping.Category.register(Identifier.parse("galaxycraft:toad"));
    static final KeyMapping ADVANCE = key("advance", InputConstants.KEY_RETURN);
    static final KeyMapping UP = key("up", InputConstants.KEY_UP);
    static final KeyMapping DOWN = key("down", InputConstants.KEY_DOWN);

    private ToadKeys() {}

    private static KeyMapping key(String name, int code) {
        return KeyMappingHelper.registerKeyMapping(new KeyMapping("key.galaxycraft.toad." + name, code, CATEGORY));
    }

    /** Loads the class (registers the keys); call once at start. */
    static void register() {}
}
