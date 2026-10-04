package dev.moui.galaxycraft.client;

import dev.moui.galaxycraft.GalaxyCraft;
import dev.moui.galaxycraft.voxel.PlanetBlueprint;
import dev.moui.galaxycraft.voxel.VoxelPlanet;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

/**
 * /galaxycraft: designs planets (name, radius, air above, layers from the surface down), saves
 * them as blueprints and loads them, which replaces this stage's planet with one built from it.
 * Saved blueprints are listed on the left: the name opens one here, Load builds it.
 */
public final class PlanetEditorScreen extends Screen {
    private static final int WHITE = 0xFFFFFFFF, GRAY = 0xFFA0A0A0, RED = 0xFFFF5555, GREEN = 0xFF55FF55;
    private static final int ROW = 22, LIST_W = 120, TOP = 60;
    private static boolean openNextTick;

    /** One layer as typed: the boxes' text, read when the blueprint is made. */
    private static final class Row {
        String block, thickness;

        Row(String block, String thickness) {
            this.block = block;
            this.thickness = thickness;
        }
    }

    private String name, radius, air;
    private final List<Row> rows = new ArrayList<>();
    private List<String> saved = List.of();
    private int layerScroll, listScroll;
    private String status = "";
    private int statusColor = WHITE;
    private final List<EditBox> blockBoxes = new ArrayList<>();

    private PlanetEditorScreen() {
        super(Component.literal("GalaxyCraft planets"));
        edit(PlanetBlueprint.standard("My planet", PlanetClient.DEFAULT_RADIUS));
        refreshSaved();
    }

    /** Opens the editor after the chat that ran the command has closed. */
    public static void requestOpen() {
        openNextTick = true;
    }

    public static void openIfRequested(Minecraft mc) {
        if (!openNextTick || mc.gui.screen() != null) return;
        openNextTick = false;
        mc.gui.setScreen(new PlanetEditorScreen());
    }

    private void edit(PlanetBlueprint b) {
        name = b.name();
        radius = Integer.toString(b.radius());
        air = Integer.toString(b.air());
        rows.clear();
        for (PlanetBlueprint.Layer l : b.layers()) rows.add(new Row(l.block(), Integer.toString(l.thickness())));
        layerScroll = 0;
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

    @Override
    protected void init() {
        blockBoxes.clear();
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
        box(x, 20, 120, name, 64, v -> name = v);
        box(x + 128, 20, 40, radius, 3, v -> radius = v);
        box(x + 176, 20, 40, air, 2, v -> air = v);
        int visible = Math.max(1, (height - TOP - 48) / ROW);
        layerScroll = Math.clamp(layerScroll, 0, Math.max(0, rows.size() - visible));
        int blockW = Math.max(60, right - x - 20 - 34 - 3 * 18);
        for (int i = 0; i < visible && layerScroll + i < rows.size(); i++) {
            int r = layerScroll + i, y = TOP + i * ROW;
            Row row = rows.get(r);
            EditBox b = box(x + 20, y, blockW, row.block, 256, v -> row.block = v);
            blockBoxes.add(b);
            b.setTextColor(state(row.block) == null ? RED : WHITE);
            b.setResponder(v -> {
                row.block = v;
                b.setTextColor(state(v) == null ? RED : WHITE);
            });
            box(x + 24 + blockW, y, 30, row.thickness, 2, v -> row.thickness = v);
            int bx = x + 58 + blockW;
            addRenderableWidget(Button.builder(Component.literal("↑"), btn -> move(r, -1)).bounds(bx, y, 16, 20).build()).active = r > 0;
            addRenderableWidget(Button.builder(Component.literal("↓"), btn -> move(r, 1)).bounds(bx + 18, y, 16, 20).build()).active = r < rows.size() - 1;
            addRenderableWidget(Button.builder(Component.literal("✕"), btn -> {
                rows.remove(r);
                rebuildWidgets();
            }).bounds(bx + 36, y, 16, 20).build()).active = rows.size() > 1;
        }
        int addY = TOP + Math.min(visible, rows.size() - layerScroll) * ROW;
        if (rows.size() < PlanetBlueprint.MAX_LAYERS && addY + 20 <= height - 48)
            addRenderableWidget(Button.builder(Component.literal("+ Layer"), b -> {
                rows.add(new Row("minecraft:stone", "1"));
                layerScroll = Math.max(0, rows.size() - visible);
                rebuildWidgets();
            }).bounds(x + 20, addY, 60, 20).build());
        // Actions.
        int bw = Math.min(70, (right - x - 16) / 5), by = height - 26;
        addRenderableWidget(Button.builder(Component.literal("New"), b -> {
            edit(PlanetBlueprint.standard("My planet", PlanetClient.DEFAULT_RADIUS));
            say("", WHITE);
            rebuildWidgets();
        }).bounds(x, by, bw, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Save"), b -> save()).bounds(x + (bw + 4), by, bw, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Delete"), b -> delete()).bounds(x + 2 * (bw + 4), by, bw, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Spawn"), b -> spawn()).bounds(x + 3 * (bw + 4), by, bw, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Done"), b -> onClose()).bounds(x + 4 * (bw + 4), by, bw, 20).build());
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

    /** The block state that text names, or null if Minecraft has none by it. */
    private static BlockState state(String text) {
        try {
            return BlockStateParser.parseForBlock(BuiltInRegistries.BLOCK, text, false).blockState();
        } catch (Exception e) {
            return null;
        }
    }

    /** The design as a blueprint, or null (and why, in the status line) if it is not one. */
    private PlanetBlueprint blueprint() {
        int r, a;
        try {
            r = Integer.parseInt(radius.trim());
            a = Integer.parseInt(air.trim());
        } catch (NumberFormatException e) {
            say("Radius and air must be numbers", RED);
            return null;
        }
        List<PlanetBlueprint.Layer> layers = new ArrayList<>();
        for (Row row : rows) {
            if (state(row.block) == null) {
                say("Unknown block: " + row.block, RED);
                return null;
            }
            int t;
            try {
                t = Integer.parseInt(row.thickness.trim());
            } catch (NumberFormatException e) {
                say("Thickness must be a number", RED);
                return null;
            }
            layers.add(new PlanetBlueprint.Layer(row.block.trim(), t));
        }
        PlanetBlueprint b = new PlanetBlueprint(name.trim(), r, a, layers);
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
        g.text(font, "Radius", x + 128, 10, GRAY);
        g.text(font, "Air", x + 176, 10, GRAY);
        String crust;
        try {
            int r = Integer.parseInt(radius.trim());
            crust = r < VoxelPlanet.MIN_RADIUS || r > VoxelPlanet.MAX_RADIUS
                    ? "Radius " + VoxelPlanet.MIN_RADIUS + " to " + VoxelPlanet.MAX_RADIUS
                    : "Surface down; crust " + VoxelPlanet.crustDepth(r) + " deep, then bedrock";
        } catch (NumberFormatException e) {
            crust = "Radius must be a number";
        }
        g.text(font, crust, x, 46, GRAY);
        for (int i = 0; i < blockBoxes.size(); i++) {
            BlockState s = state(rows.get(layerScroll + i).block);
            if (s != null) g.item(new ItemStack(s.getBlock().asItem()), x, TOP + i * ROW + 2);
        }
        g.text(font, status, x, height - 40, statusColor);
    }
}
