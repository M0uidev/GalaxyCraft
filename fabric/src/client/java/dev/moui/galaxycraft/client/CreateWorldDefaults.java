package dev.moui.galaxycraft.client;

import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.resources.Identifier;

/**
 * A new world starts as a GalaxyCraft one: world type GalaxyCraft (Minecraft's void with its
 * start platform: the world is the galaxy's planets, and nothing of an overworld lies around the
 * player), commands allowed (/gamemode, /galaxycraft). The player can still change both.
 */
final class CreateWorldDefaults {
    static final Identifier PRESET = Identifier.fromNamespaceAndPath("galaxycraft", "galaxy");
    /** Screens already set (init runs again on resize, and must not undo the player's choices). */
    private static final Set<CreateWorldScreen> SET = Collections.newSetFromMap(new WeakHashMap<>());

    private CreateWorldDefaults() {}

    static void register() {
        ScreenEvents.AFTER_INIT.register((mc, screen, w, h) -> {
            if (screen instanceof CreateWorldScreen create && SET.add(create)) apply(create.getUiState());
        });
    }

    private static void apply(WorldCreationUiState ui) {
        ui.getNormalPresetList().stream()
                .filter(e -> e.preset().unwrapKey().map(k -> k.identifier().equals(PRESET)).orElse(false))
                .findFirst().ifPresent(ui::setWorldType);
        ui.setAllowCommands(true);
    }
}
