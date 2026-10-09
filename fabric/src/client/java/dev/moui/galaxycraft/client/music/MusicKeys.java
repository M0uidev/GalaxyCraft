package dev.moui.galaxycraft.client.music;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.resources.Identifier;

/** Keys of the music player: M opens it; the rest are unbound until the player sets them. */
public final class MusicKeys {
    private static final KeyMapping.Category CATEGORY = KeyMapping.Category.register(Identifier.parse("galaxycraft:music"));
    private static final KeyMapping OPEN = key("open", InputConstants.KEY_M);
    private static final KeyMapping PLAY_PAUSE = key("play_pause", InputConstants.UNKNOWN.getValue());
    private static final KeyMapping NEXT = key("next", InputConstants.UNKNOWN.getValue());
    private static final KeyMapping PREVIOUS = key("previous", InputConstants.UNKNOWN.getValue());

    private MusicKeys() {}

    private static KeyMapping key(String name, int code) {
        return KeyMappingHelper.registerKeyMapping(new KeyMapping("key.galaxycraft.music." + name, code, CATEGORY));
    }

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            while (OPEN.consumeClick()) if (mc.gui.screen() == null) SoundtrackScreen.open();
            while (PLAY_PAUSE.consumeClick()) MusicService.pause(!MusicService.paused());
            while (NEXT.consumeClick()) MusicService.next();
            while (PREVIOUS.consumeClick()) MusicService.previous();
        });
    }
}
