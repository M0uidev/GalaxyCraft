package dev.moui.galaxycraft.client;

import dev.moui.galaxycraft.toad.ToadType;
import dev.moui.galaxycraft.toad.ToadVoicePlan;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.sounds.SoundEvents;

/**
 * A Toad's voice, one letter at a time. THE seam: today a pitched Minecraft sound (a villager's
 * "hmm" made higher or lower by the Toad's type and the letter, ToadVoicePlan); SMG2's own
 * Kinopio voice would be played from here once the launcher exports it from the disc's AudioRes
 * (the wave banks and AFC decoder are the work, see the dialogue spec).
 */
final class ToadVoice {
    private ToadVoice() {}

    /** The letter c at this index of the page's text is being typed. */
    static void speak(ToadType type, char c, int index) {
        if (!ToadVoicePlan.blip(c, index)) return;
        Minecraft.getInstance().getSoundManager().play(
                SimpleSoundInstance.forUI(SoundEvents.VILLAGER_AMBIENT, ToadVoicePlan.pitch(type, c), 0.35f));
    }
}
