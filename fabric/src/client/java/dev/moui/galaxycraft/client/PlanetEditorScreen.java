package dev.moui.galaxycraft.client;

import dev.moui.galaxycraft.GalaxyCraft;
import dev.moui.galaxycraft.voxel.PlanetBlueprint;
import dev.moui.galaxycraft.voxel.VoxelPlanet;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

/**
 * /galaxycraft: designs planets (name, radius, air above, layers from the surface down), saves
 * them as blueprints and loads them, which replaces this stage's planet with one built from it.
 * Saved blueprints are listed on the left: the name opens one here, Load builds it. A layer's
 * block is chosen from a searchable list ({@link BlockPickerScreen}) and its thickness on a slider
 * from 1 up to what the crust above the bedrock still has room for at this radius.
 */
public final class PlanetEditorScreen extends Screen {
    private static final int WHITE = 0xFFFFFFFF, GRAY = 0xFFA0A0A0, RED = 0xFFFF5555, GREEN = 0xFF55FF55;
    private static final int ROW = 22, LIST_W = 120, TOP = 70, MODE_W = 110;
    private static boolean openNextTick;

    /** One layer as edited. */
    private static final class Row {
        String block;
        int thickness;

        Row(String block, int thickness) {
            this.block = block;
            this.thickness = thickness;
        }
    }

    private String name;
    private int radius, air, biomeSize;
    private PlanetBlueprint.Mode mode;
    private String seed, biome;
    private final List<Row> rows = new ArrayList<>();
    private List<String> saved = List.of();
    private int layerScroll, listScroll;
    private String status = "";
    private int statusColor = WHITE;
    private final List<Thickness> sliders = new ArrayList<>();
    private Button addLayer;

    private PlanetEditorScreen() {
        this(PlanetBlueprint.standard("My planet", PlanetClient.DEFAULT_RADIUS));
    }

    /** The editor on that blueprint (tests). */
    public PlanetEditorScreen(PlanetBlueprint b) {
        super(Component.literal("GalaxyCraft planets"));
        edit(b);
        refreshSaved();
    }

    /** Opens the editor after the chat that ran the command has closed. */
    public static void requestOpen() {
        openNextTick = true;
    }

    public static void openIfRequested(Minecraft mc) {
        if (!openNextTick || mc.gui.screen() != null) return;
        openNextTick = false;
        // On the planet it had last (generated ones stay generated), or a new one.
        mc.gui.setScreen(new PlanetEditorScreen(PlanetClient.blueprints.readLast()
                .filter(b -> b.problem() == null).orElse(PlanetBlueprint.standard("My planet", PlanetClient.DEFAULT_RADIUS))));
    }

    private void edit(PlanetBlueprint b) {
        name = b.name();
        radius = Math.clamp(b.radius(), VoxelPlanet.MIN_RADIUS, VoxelPlanet.MAX_RADIUS);
        air = Math.clamp(b.air(), PlanetBlueprint.MIN_AIR, PlanetBlueprint.MAX_AIR);
        mode = b.mode();
        seed = Long.toString(b.seed());
        biome = b.biome();
        biomeSize = b.biomeSize();
        rows.clear();
        for (PlanetBlueprint.Layer l : b.layers()) rows.add(new Row(l.block(), l.thickness()));
        fit();
        layerScroll = 0;
    }

    /** Blocks above the bedrock that the layers share at this radius. */
    private int room() {
        return PlanetBlueprint.room(radius);
    }

    /** Cuts the layers, bottom up, until they fit in the crust. */
    private void fit() {
        int[] t = PlanetBlueprint.fit(rows.stream().mapToInt(r -> r.thickness).toArray(), room());
        for (int i = 0; i < t.length; i++) rows.get(i).thickness = t[i];
        for (Thickness s : sliders) s.sync();
    }

    private int used() {
        return rows.stream().mapToInt(r -> r.thickness).sum();
    }

    /** Whether any of layer r is above the bedrock (layers past the crust are not built). */
    private boolean reached(int r) {
        int above = 0;
        for (int i = 0; i < r; i++) above += rows.get(i).thickness;
        return above < room();
    }

    private boolean canAdd() {
        return rows.size() < PlanetBlueprint.MAX_LAYERS && rows.size() < room();
    }

    /** A layer's thickness: 1 up to the room the other layers leave, on a scale as long as the crust allows. */
    private final class Thickness extends AbstractSliderButton {
        private final Row row;

        Thickness(int x, int y, int w, Row row) {
            super(x, y, w, 20, Component.empty(), 0);
            this.row = row;
            sync();
        }

        private int top() {
            return Math.max(1, room() - (rows.size() - 1));
        }

        void sync() {
            value = top() <= 1 ? 0 : (Math.min(row.thickness, top()) - 1) / (double) (top() - 1);
            active = top() > 1;
            updateMessage();
        }

        @Override
        protected void updateMessage() {
            setMessage(Component.literal(row.thickness + (row.thickness == 1 ? " block" : " blocks")));
        }

        @Override
        protected void applyValue() {
            int cap = Math.max(1, room() - (used() - row.thickness));
            row.thickness = Math.min(cap, 1 + (int) Math.round(value * (top() - 1)));
            if (top() > 1) value = (row.thickness - 1) / (double) (top() - 1);
        }
    }

    /** An int on a slider (radius, air, biome size), moving by step. */
    private final class IntSlider extends AbstractSliderButton {
        private final java.util.function.IntFunction<String> label;
        private final int min, max, step;
        private final java.util.function.IntConsumer to;
        private int n;

        IntSlider(int x, int y, int w, String label, int min, int max, int n, java.util.function.IntConsumer to) {
            this(x, y, w, v -> label + ": " + v, min, max, 1, n, to);
        }

        IntSlider(int x, int y, int w, java.util.function.IntFunction<String> label, int min, int max, int step, int n,
                java.util.function.IntConsumer to) {
            super(x, y, w, 20, Component.empty(), (n - min) / (double) (max - min));
            this.label = label;
            this.min = min;
            this.max = max;
            this.step = step;
            this.n = n;
            this.to = to;
            updateMessage();
        }

        @Override
        protected void updateMessage() {
            setMessage(Component.literal(label.apply(n)));
        }

        @Override
        protected void applyValue() {
            n = min + (int) Math.round(value * (max - min) / step) * step;
            value = (n - min) / (double) (max - min);
            to.accept(n);
        }
    }

    private void refreshSaved() {
        try {
            saved = PlanetClient.blueprints.list();
        } catch (IOException e) {
            saved = List.of();
            say("Could not list the blueprints: " + e.getMessage(), RED);
        }
    }

    private void say(String text, int color) {
        status = text;
        statusColor = color;
    }

    private static final int PREVIEW_W = 16;

    @Override
    protected void init() {
        sliders.clear();
        addLayer = null;
        int x = LIST_W + 20, right = width - 10;
        // The saved blueprints.
        int listRows = Math.max(1, (height - 40 - 24) / ROW);
        listScroll = Math.clamp(listScroll, 0, Math.max(0, saved.size() - listRows));
        for (int i = 0; i < listRows && listScroll + i < saved.size(); i++) {
            String s = saved.get(listScroll + i);
            int y = 24 + i * ROW;
            addRenderableWidget(Button.builder(Component.literal(s), b -> open(s, false)).bounds(10, y, LIST_W - 34, 20).build());
            addRenderableWidget(Button.builder(Component.literal("Load"), b -> open(s, true)).bounds(10 + LIST_W - 32, y, 32, 20).build());
        }
        // The design.
        int w = right - x, nameW = Math.min(140, w * 2 / 5), radiusW = Math.min(120, (w - nameW - 8) * 3 / 5);
        box(x, 20, nameW, name, 64, v -> name = v);
        addRenderableWidget(new IntSlider(x + nameW + 4, 20, radiusW, "Radius", VoxelPlanet.MIN_RADIUS, VoxelPlanet.MAX_RADIUS, radius, v -> {
            radius = v;
            fit();
        })).setTooltip(Tooltip.create(Component.literal("Arrow keys move it one by one")));
        addRenderableWidget(new IntSlider(x + nameW + radiusW + 8, 20, Math.min(90, w - nameW - radiusW - 8), "Air",
                PlanetBlueprint.MIN_AIR, PlanetBlueprint.MAX_AIR, air, v -> air = v))
                .setTooltip(Tooltip.create(Component.literal("Room to build above the surface")));
        addRenderableWidget(Button.builder(Component.literal("Mode: " + (mode == PlanetBlueprint.Mode.LAYERS ? "Layers" : "Generated")), b -> {
            mode = mode == PlanetBlueprint.Mode.LAYERS ? PlanetBlueprint.Mode.GENERATED : PlanetBlueprint.Mode.LAYERS;
            if (mode == PlanetBlueprint.Mode.GENERATED && seed.equals("0")) seed = Long.toString(new java.util.Random().nextLong());
            rebuildWidgets();
        }).bounds(x, 44, MODE_W, 20).tooltip(Tooltip.create(Component.literal(
                "Layers: a smooth ball of your layers\nGenerated: terrain and blocks of a Minecraft biome"))).build());
        if (mode == PlanetBlueprint.Mode.LAYERS) layerWidgets(x, right);
        else generatedWidgets(x, right);
        // Actions.
        int bw = Math.min(70, (right - x - 16) / 5), by = height - 26;
        addRenderableWidget(Button.builder(Component.literal("New"), b -> {
            // A new one of the same kind: generated ones get a seed of their own.
            PlanetBlueprint.Mode kind = mode;
            edit(PlanetBlueprint.standard("My planet", PlanetClient.DEFAULT_RADIUS).withMode(kind)
                    .withBiome(kind == PlanetBlueprint.Mode.GENERATED ? new java.util.Random().nextLong() : 0, PlanetBlueprint.RANDOM, 0));
            say("", WHITE);
            rebuildWidgets();
        }).bounds(x, by, bw, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Save"), b -> save()).bounds(x + (bw + 4), by, bw, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Delete"), b -> delete()).bounds(x + 2 * (bw + 4), by, bw, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Spawn"), b -> spawn()).bounds(x + 3 * (bw + 4), by, bw, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Done"), b -> onClose()).bounds(x + 4 * (bw + 4), by, bw, 20).build());
    }

    private void layerWidgets(int x, int right) {
        int visible = Math.max(1, (height - TOP - 48) / ROW);
        layerScroll = Math.clamp(layerScroll, 0, Math.max(0, rows.size() - visible));
        int sliderW = 70, blockW = Math.max(80, right - x - 20 - sliderW - 4 - 54 - PREVIEW_W);
        for (int i = 0; i < visible && layerScroll + i < rows.size(); i++) {
            int r = layerScroll + i, y = TOP + i * ROW;
            Row row = rows.get(r);
            addRenderableWidget(Button.builder(Component.empty(), b -> minecraft.gui.setScreen(PickerScreen.blocks(this, row.block, v -> row.block = v)))
                    .bounds(x + 20, y, blockW, 20).tooltip(Tooltip.create(Component.literal(row.block + "\nClick to choose another block"))).build());
            sliders.add(addRenderableWidget(new Thickness(x + 24 + blockW, y, sliderW, row)));
            int bx = x + 28 + blockW + sliderW;
            addRenderableWidget(Button.builder(Component.literal("↑"), btn -> move(r, -1)).bounds(bx, y, 16, 20).build()).active = r > 0;
            addRenderableWidget(Button.builder(Component.literal("↓"), btn -> move(r, 1)).bounds(bx + 18, y, 16, 20).build()).active = r < rows.size() - 1;
            addRenderableWidget(Button.builder(Component.literal("✕"), btn -> {
                rows.remove(r);
                rebuildWidgets();
            }).bounds(bx + 36, y, 16, 20).build()).active = rows.size() > 1;
        }
        int addY = TOP + Math.min(visible, rows.size() - layerScroll) * ROW;
        addLayer = null;
        if (addY + 20 <= height - 48)
            addLayer = addRenderableWidget(Button.builder(Component.literal("+ Layer"), b -> minecraft.gui.setScreen(
                    PickerScreen.blocks(this, "", v -> {
                        rows.add(new Row(v, 1));
                        fit();
                        layerScroll = Math.max(0, rows.size() - visible);
                    }))).bounds(x + 20, addY, 60, 20).build());
    }

    private void generatedWidgets(int x, int right) {
        int w = Math.min(220, right - x - 20);
        addRenderableWidget(Button.builder(Component.empty(), b -> {
            McWorldgen gen = PlanetClient.worldgen();
            if (gen == null) say("Biomes need a single player world", RED);
            else minecraft.gui.setScreen(PickerScreen.biomes(this, gen.biomes().land(), biome, v -> biome = v));
        }).bounds(x + 40, TOP, w, 20).tooltip(Tooltip.create(Component.literal(biome + "\nClick to choose another biome"))).build());
        EditBox seedBox = box(x + 40, TOP + ROW, Math.min(140, w - 40), seed, 20, v -> seed = v);
        seedBox.setResponder(v -> {
            seed = v;
            seedBox.setTextColor(v.matches("-?\\d{0,18}") ? WHITE : RED);
        });
        addRenderableWidget(Button.builder(Component.literal("New"), b -> {
            seed = Long.toString(new java.util.Random().nextLong());
            rebuildWidgets();
        }).bounds(x + 44 + Math.min(140, w - 40), TOP + ROW, 34, 20).tooltip(Tooltip.create(Component.literal("Another seed"))).build());
        addRenderableWidget(new IntSlider(x + 40, TOP + 2 * ROW, w,
                v -> v == 0 ? "Biomes: one per planet" : "Biomes: about " + v + " blocks across",
                0, PlanetBlueprint.MAX_BIOME_SIZE, 16, biomeSize, v -> biomeSize = v))
                .setTooltip(Tooltip.create(Component.literal("One per planet: all of it the biome above\nMore: Minecraft's biomes mixed, this big")));
    }

    /** The biome's name, red when this game has no such land biome. */
    private boolean biomeKnown() {
        McWorldgen gen = PlanetClient.worldgen();
        return PlanetBlueprint.RANDOM.equals(biome) || gen == null || gen.biomes().span(biome) != null;
    }

    private EditBox box(int x, int y, int w, String value, int max, java.util.function.Consumer<String> to) {
        EditBox b = new EditBox(font, x, y, w, 20, Component.empty());
        b.setMaxLength(max);
        b.setValue(value);
        b.setResponder(to);
        return addRenderableWidget(b);
    }

    private void move(int r, int by) {
        rows.add(r + by, rows.remove(r));
        rebuildWidgets();
    }

    /** The name the inventory gives the block that text names, or the text if Minecraft has none by it. */
    private static String blockName(String text) {
        BlockState s = state(text);
        return s == null ? text : s.getBlock().getName().getString();
    }

    /** The block state that text names, or null if Minecraft has none by it. */
    static BlockState state(String text) {
        try {
            return BlockStateParser.parseForBlock(BuiltInRegistries.BLOCK, text, false).blockState();
        } catch (Exception e) {
            return null;
        }
    }

    /** The design as a blueprint, or null (and why, in the status line) if it is not one. */
    private PlanetBlueprint blueprint() {
        List<PlanetBlueprint.Layer> layers = new ArrayList<>();
        for (Row row : rows) {
            if (state(row.block) == null) {
                say("Unknown block: " + row.block, RED);
                return null;
            }
            layers.add(new PlanetBlueprint.Layer(row.block.trim(), row.thickness));
        }
        long s;
        try {
            s = Long.parseLong(seed.isEmpty() || seed.equals("-") ? "0" : seed);
        } catch (NumberFormatException e) {
            say("The seed must be a whole number", RED);
            return null;
        }
        if (mode == PlanetBlueprint.Mode.GENERATED && biomeSize == 0 && !biomeKnown()) {
            say("Unknown biome: " + biome, RED);
            return null;
        }
        PlanetBlueprint b = new PlanetBlueprint(name.trim(), radius, air, layers, mode, s, biome, biomeSize);
        String problem = b.problem();
        if (problem != null) {
            say(problem, RED);
            return null;
        }
        return b;
    }

    private void save() {
        PlanetBlueprint b = blueprint();
        if (b == null) return;
        try {
            PlanetClient.blueprints.write(b);
            say("Saved " + b.name(), GREEN);
        } catch (IOException e) {
            GalaxyCraft.LOG.warn("Could not save the blueprint {}: {}", b.name(), e.toString());
            say("Could not save: " + e.getMessage(), RED);
        }
        refreshSaved();
        rebuildWidgets();
    }

    private void delete() {
        try {
            if (!java.nio.file.Files.exists(PlanetClient.blueprints.file(name.trim()))) {
                say("No saved blueprint named " + name.trim(), RED);
                return;
            }
            PlanetClient.blueprints.delete(name.trim());
            say("Deleted " + name.trim(), GREEN);
        } catch (IOException e) {
            say("Could not delete: " + e.getMessage(), RED);
        }
        refreshSaved();
        rebuildWidgets();
    }

    /** Remembers what is open (if it is a whole blueprint) for the next time the editor opens. */
    @Override
    public void removed() {
        String s = status;
        int c = statusColor;
        PlanetBlueprint b = blueprint();
        status = s;
        statusColor = c;
        if (b == null) return;
        try {
            PlanetClient.blueprints.writeLast(b);
        } catch (IOException e) {
            GalaxyCraft.LOG.warn("Could not remember the last blueprint: {}", e.toString());
        }
    }

    private void spawn() {
        PlanetBlueprint b = blueprint();
        if (b == null) return;
        PlanetClient.requestSpawn(b);
        onClose();
    }

    /** A saved blueprint into the editor, and with load also into this stage. */
    private void open(String file, boolean load) {
        try {
            PlanetBlueprint b = PlanetClient.blueprints.read(file).orElse(null);
            if (b == null) {
                say("It is gone: " + file, RED);
                refreshSaved();
            } else {
                edit(b);
                say("Opened " + b.name(), WHITE);
                if (load) {
                    spawn();
                    return;
                }
            }
        } catch (IOException e) {
            say("Could not read " + file + ": " + e.getMessage(), RED);
        }
        rebuildWidgets();
    }

    @Override
    public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
        int step = scrollY > 0 ? -1 : scrollY < 0 ? 1 : 0;
        if (step == 0) return false;
        if (x < LIST_W + 15) listScroll += step;
        else layerScroll += step;
        rebuildWidgets();
        return true;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
        super.extractRenderState(g, mouseX, mouseY, a);
        int x = LIST_W + 20;
        g.text(font, "Saved planets", 10, 10, WHITE);
        if (saved.isEmpty()) g.text(font, "None yet", 10, 28, GRAY);
        g.text(font, "Name", x, 10, GRAY);
        int right = width - 10, infoX = x + MODE_W + 6;
        if (mode == PlanetBlueprint.Mode.GENERATED) {
            g.text(font, font.plainSubstrByWidth("From Minecraft's worldgen", right - infoX), infoX, 50, GRAY);
            g.text(font, "Biome", x, TOP + 6, GRAY);
            g.text(font, "Seed", x, TOP + ROW + 6, GRAY);
            String shown = PlanetBlueprint.RANDOM.equals(biome) ? "Random (from the seed)" : McWorldgen.name(biome);
            if (biomeSize > 0) shown = "Mixed (" + shown + " unused)";
            BlockState top = PlanetBlueprint.RANDOM.equals(biome) ? null : state(dev.moui.galaxycraft.voxel.gen.BiomeSurface.of(biome).top());
            if (top != null) g.item(new ItemStack(top.getBlock().asItem()), x + 42, TOP + 2);
            g.text(font, font.plainSubstrByWidth(shown, Math.min(220, right - x - 20) - 28), x + 62, TOP + 6,
                    biomeKnown() ? biomeSize > 0 ? GRAY : WHITE : RED);
            g.text(font, status, x, height - 40, statusColor);
            return;
        }
        int room = room(), used = used();
        String crust = used < room ? "Crust " + used + "/" + room + ", the last layer fills " + (room - used) + " more"
                : rows.size() > room ? "Crust full: " + (rows.size() - room) + " layer(s) do not fit"
                : "Crust " + room + "/" + room + " blocks, then bedrock";
        int color = rows.size() > room ? RED : GRAY;
        g.text(font, font.plainSubstrByWidth(crust, right - infoX), infoX, 50, color);
        if (addLayer != null) addLayer.active = canAdd();
        for (int i = 0; i < sliders.size(); i++) {
            int r = layerScroll + i, y = TOP + i * ROW;
            Row row = rows.get(r);
            BlockState s = state(row.block);
            g.text(font, Integer.toString(r + 1), x + 4, y + 6, GRAY);
            if (s != null) g.item(new ItemStack(s.getBlock().asItem()), x + 22, y + 2);
            int blockW = sliders.get(i).getX() - 4 - (x + 20);
            String label = font.plainSubstrByWidth(blockName(row.block), blockW - 28);
            g.text(font, label, x + 42, y + 6, s == null ? RED : reached(r) ? WHITE : GRAY);
        }
        preview(g, right - PREVIEW_W + 4, TOP);
        g.text(font, status, x, height - 40, statusColor);
    }

    /** The crust as it will be built, a half-size block per block from the surface down to the bedrock. */
    private void preview(GuiGraphicsExtractor g, int px, int py) {
        int room = room(), cell = Math.min(8, (height - 48 - py) / (room + 1));
        if (cell < 2) return;
        List<ItemStack> column = new ArrayList<>();
        for (Row row : rows) {
            BlockState s = state(row.block);
            for (int i = 0; i < row.thickness; i++) column.add(s == null ? ItemStack.EMPTY : new ItemStack(s.getBlock().asItem()));
        }
        ItemStack last = column.isEmpty() ? ItemStack.EMPTY : column.getLast();
        while (column.size() < room) column.add(last);
        column = new ArrayList<>(column.subList(0, room));
        column.add(new ItemStack(net.minecraft.world.item.Items.BEDROCK));
        g.fill(px - 1, py - 1, px + cell + 1, py + column.size() * cell + 1, 0x80000000);
        for (int i = 0; i < column.size(); i++) {
            if (column.get(i).isEmpty()) continue;
            g.pose().pushMatrix();
            g.pose().translate(px, py + i * cell);
            g.pose().scale(cell / 16f, cell / 16f);
            g.item(column.get(i), 0, 0);
            g.pose().popMatrix();
        }
    }
}
