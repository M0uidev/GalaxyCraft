package dev.moui.galaxycraft.client;

import dev.moui.galaxycraft.voxel.GalaxyCatalog;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.IntConsumer;
import java.util.function.IntFunction;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.components.tabs.GridLayoutTab;
import net.minecraft.client.gui.layouts.GridLayout;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.network.chat.Component;

/**
 * Create World's GalaxyCraft tab: how many planets the world's galaxy has, how big the others
 * are, what the first one is (generated with a biome, or one of the player's blueprints) and how
 * far apart they lie. The world's seed (the World tab's) places them. The choice goes to
 * {@link PendingGalaxy}; the galaxy is made on the world's first visit.
 */
final class GalaxyTab extends GridLayoutTab {
    /** The biomes the first planet can be all of: Random (several, Auto), then 1.7's land biomes. */
    static final List<String> BIOMES = java.util.stream.Stream.concat(java.util.stream.Stream.of("random"),
            dev.moui.galaxycraft.voxel.gen.LegacyBiome.land().stream()).toList();
    /** Each screen's choice: init runs again on resize and must not lose it. */
    private static final Map<CreateWorldScreen, GalaxyCatalog.Options> CHOSEN = Collections.synchronizedMap(new WeakHashMap<>());
    private static final int W = 150;

    private final CreateWorldScreen screen;
    private int count, min, max, firstRadius;
    private boolean blueprint;
    private String biome, blueprintName;
    private GalaxyCatalog.Spacing spacing;

    GalaxyTab(CreateWorldScreen screen) {
        super(Component.literal("Galaxy"));
        this.screen = screen;
        GalaxyCatalog.Options o = CHOSEN.getOrDefault(screen, GalaxyCatalog.Options.defaults(0));
        count = o.count();
        min = o.minRadius();
        max = o.maxRadius();
        blueprint = o.first().isBlueprint();
        biome = o.first().biome() == null ? "random" : o.first().biome();
        blueprintName = o.first().name();
        firstRadius = o.first().radius() > 0 ? o.first().radius() : 48;
        spacing = o.spacing();
        List<String> names = new ArrayList<>();
        try {
            names.addAll(PlanetClient.blueprints.list());
        } catch (java.io.IOException e) {
            // No blueprints folder yet: none to choose.
        }
        if (blueprintName == null || !names.contains(blueprintName)) blueprintName = names.isEmpty() ? null : names.getFirst();
        if (names.isEmpty()) blueprint = false;

        GridLayout.RowHelper rows = layout.rowSpacing(6).columnSpacing(8).createRowHelper(2);
        rows.addChild(new Slider("Planets", 1, GalaxyCatalog.MAX, count, v -> "Planets: " + v, v -> count = v));
        rows.addChild(CycleButton.<GalaxyCatalog.Spacing>builder(s -> Component.literal(switch (s) {
                    case NEAR -> "Near";
                    case NORMAL -> "Normal";
                    case FAR -> "Far";
                } + " (" + s.blocks(GalaxyCatalog.LAYOUT) + " blocks)"), spacing).withValues(GalaxyCatalog.Spacing.values())
                .create(0, 0, W, 20, Component.literal("Spacing"), (b, v) -> changed(() -> spacing = v)));
        rows.addChild(new Slider("Others' radius from", GalaxyCatalog.MIN_RADIUS, GalaxyCatalog.MAX_RADIUS, min,
                v -> "Others from: " + v, v -> min = v));
        rows.addChild(new Slider("Others' radius to", GalaxyCatalog.MIN_RADIUS, GalaxyCatalog.MAX_RADIUS, max,
                v -> "Others to: " + v, v -> max = v));
        CycleButton<String> biomeButton = CycleButton.<String>builder(b -> Component.literal(pretty(b)), biome).withValues(BIOMES)
                .create(0, 0, W, 20, Component.literal("Biome"), (b, v) -> changed(() -> biome = v));
        CycleButton<String> blueprintButton = CycleButton.<String>builder(Component::literal, blueprintName == null ? "-" : blueprintName)
                .withValues(names.isEmpty() ? List.of("-") : names)
                .create(0, 0, W, 20, Component.literal("Blueprint"), (b, v) -> changed(() -> blueprintName = v));
        Slider radius = new Slider("First radius", GalaxyCatalog.MIN_RADIUS, GalaxyCatalog.MAX_RADIUS, firstRadius,
                v -> "First's radius: " + v, v -> firstRadius = v);
        CycleButton<Boolean> kind = CycleButton.<Boolean>builder(b -> Component.literal(b ? "Blueprint" : "Generated"), blueprint)
                .withValues(names.isEmpty() ? List.of(false) : List.of(false, true))
                .create(0, 0, W, 20, Component.literal("First planet"), (b, v) -> changed(() -> {
                    blueprint = v;
                    biomeButton.active = radius.active = !v;
                    blueprintButton.active = v;
                }));
        rows.addChild(kind);
        rows.addChild(radius);
        rows.addChild(biomeButton);
        rows.addChild(blueprintButton);
        biomeButton.active = radius.active = !blueprint;
        blueprintButton.active = blueprint;
        rows.addChild(new StringWidget(2 * W + 8, 9, Component.literal("Planets are placed by the World tab's seed."),
                Minecraft.getInstance().font), 2);
        changed(() -> {});
    }

    private static String pretty(String biome) {
        if (biome.equals("random")) return "Random";
        String s = biome.substring(biome.indexOf(':') + 1).replace('_', ' ');
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private void changed(Runnable r) {
        r.run();
        GalaxyCatalog.First first = blueprint && blueprintName != null ? GalaxyCatalog.First.blueprint(blueprintName)
                : GalaxyCatalog.First.generated(biome, firstRadius);
        GalaxyCatalog.Options o = new GalaxyCatalog.Options(count, Math.min(min, max), Math.max(min, max), first, spacing, 0);
        CHOSEN.put(screen, o);
        PendingGalaxy.set(o);
    }

    private final class Slider extends AbstractSliderButton {
        private final int lo, hi;
        private final IntFunction<String> label;
        private final IntConsumer to;

        Slider(String name, int lo, int hi, int n, IntFunction<String> label, IntConsumer to) {
            super(0, 0, W, 20, Component.literal(name), (n - lo) / (double) (hi - lo));
            this.lo = lo;
            this.hi = hi;
            this.label = label;
            this.to = to;
            updateMessage();
        }

        private int n() {
            return lo + (int) Math.round(value * (hi - lo));
        }

        @Override protected void updateMessage() {
            setMessage(Component.literal(label.apply(n())));
        }

        @Override protected void applyValue() {
            changed(() -> to.accept(n()));
        }
    }
}
