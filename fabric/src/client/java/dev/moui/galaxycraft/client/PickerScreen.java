package dev.moui.galaxycraft.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Chooses one of a list (blocks for a planet layer, biomes for a generated planet), each with an
 * icon and the name the game shows for it, narrowed by what is typed (words of the name or of the
 * id). Clicking one, or Enter on the highlighted one, hands its text back and returns to the editor.
 */
public final class PickerScreen extends Screen {
    private static final int WHITE = 0xFFFFFFFF, GRAY = 0xFFA0A0A0, HOVER = 0x40FFFFFF, PICKED = 0x60FFFFFF;
    private static final int ROW = 20, TOP = 44;

    /** One as listed: the text it is saved as, its name and icon, and what the search looks in. */
    private record Entry(String text, String name, ItemStack icon, String haystack) {
        static Entry of(String text, String name, ItemStack icon) {
            return new Entry(text, name, icon, (name + " " + text).toLowerCase(Locale.ROOT));
        }

        static Entry block(String text, BlockState state) {
            return of(text, state.getBlock().getName().getString(), new ItemStack(state.getBlock().asItem()));
        }
    }

    private static List<Entry> allBlocks;
    private final List<Entry> all;
    private final boolean states; // blocks: text with a state is offered as typed
    private final Screen parent;
    private final Consumer<String> pick;
    private final String current;
    private List<Entry> shown = List.of();
    private String query = "";
    private int scroll, selected;
    private EditBox search;

    private PickerScreen(String title, List<Entry> all, boolean states, Screen parent, String current, Consumer<String> pick) {
        super(Component.literal(title));
        this.all = all;
        this.states = states;
        this.parent = parent;
        this.current = current;
        this.pick = pick;
    }

    /** Every Minecraft block; text with a state, as in /setblock (minecraft:oak_log[axis=x]), is offered as it is. */
    public static PickerScreen blocks(Screen parent, String current, Consumer<String> pick) {
        if (allBlocks == null) {
            allBlocks = new ArrayList<>();
            for (Block b : BuiltInRegistries.BLOCK)
                if (!b.defaultBlockState().isAir())
                    allBlocks.add(Entry.block(BuiltInRegistries.BLOCK.getKey(b).toString(), b.defaultBlockState()));
        }
        return new PickerScreen("Choose a block", allBlocks, true, parent, current, pick);
    }

    /** The land biomes, each with its ground's top block as icon; "Random" (from the seed) first. */
    public static PickerScreen biomes(Screen parent, List<String> biomes, String current, Consumer<String> pick) {
        List<Entry> all = new ArrayList<>();
        all.add(Entry.of(dev.moui.galaxycraft.voxel.PlanetBlueprint.RANDOM, "Random (from the seed)", new ItemStack(net.minecraft.world.item.Items.COMPASS)));
        for (String b : biomes) {
            BlockState top = PlanetEditorScreen.state(dev.moui.galaxycraft.voxel.gen.BiomeSurface.of(b).top());
            all.add(Entry.of(b, McWorldgen.name(b), top == null ? ItemStack.EMPTY : new ItemStack(top.getBlock().asItem())));
        }
        return new PickerScreen("Choose a biome", all, false, parent, current, pick);
    }

    private void filter() {
        String[] words = query.toLowerCase(Locale.ROOT).trim().split("\\s+");
        List<Entry> out = new ArrayList<>();
        // A block with its state typed out goes first, as it is.
        if (states && query.contains("[")) {
            try {
                out.add(Entry.block(query.trim(), BlockStateParser.parseForBlock(BuiltInRegistries.BLOCK, query.trim(), false).blockState()));
            } catch (Exception ignored) {
                // Not one yet: the list below still narrows by its words.
            }
        }
        for (Entry e : all) {
            boolean match = true;
            for (String w : words) if (!e.haystack.contains(w.replace("[", " ").split(" ")[0])) match = false;
            if (match) out.add(e);
        }
        shown = out;
        selected = 0;
        scroll = 0;
    }

    private int rows() {
        return Math.max(1, (height - TOP - 34) / ROW);
    }

    private int listX() {
        return Math.max(10, width / 2 - 150);
    }

    private int listW() {
        return Math.min(300, width - 20);
    }

    @Override
    protected void init() {
        search = new EditBox(font, listX(), 20, listW(), 20, Component.literal("Search"));
        search.setHint(Component.literal("Search by name or id"));
        search.setMaxLength(256);
        search.setValue(query);
        search.setResponder(v -> {
            query = v;
            filter();
        });
        addRenderableWidget(search);
        setInitialFocus(search);
        addRenderableWidget(Button.builder(Component.literal("Cancel"), b -> onClose())
                .bounds(width / 2 - 40, height - 26, 80, 20).build());
        if (shown.isEmpty() && query.isEmpty()) {
            filter();
            // Start on the block the layer already has.
            for (int i = 0; i < shown.size(); i++)
                if (shown.get(i).text.equals(current)) {
                    selected = i;
                    scroll = Math.max(0, i - rows() / 2);
                }
        }
        clampScroll();
    }

    private void clampScroll() {
        scroll = Math.clamp(scroll, 0, Math.max(0, shown.size() - rows()));
    }

    private void choose(Entry e) {
        pick.accept(e.text);
        onClose();
    }

    /** The listed entry under the mouse, or -1. */
    private int at(double mx, double my) {
        if (mx < listX() || mx >= listX() + listW() || my < TOP) return -1;
        int i = (int) ((my - TOP) / ROW);
        return i < rows() && scroll + i < shown.size() ? scroll + i : -1;
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent ev, boolean doubled) {
        int i = at(ev.x(), ev.y());
        if (i >= 0 && ev.button() == 0) {
            choose(shown.get(i));
            return true;
        }
        return super.mouseClicked(ev, doubled);
    }

    @Override
    public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
        scroll -= (int) Math.signum(scrollY) * 3;
        clampScroll();
        return true;
    }

    @Override
    public boolean keyPressed(KeyEvent ev) {
        if (ev.isConfirmation() && selected < shown.size()) {
            choose(shown.get(selected));
            return true;
        }
        int step = ev.isDown() ? 1 : ev.isUp() ? -1 : 0;
        if (step != 0 && !shown.isEmpty()) {
            selected = Math.clamp(selected + step, 0, shown.size() - 1);
            if (selected < scroll) scroll = selected;
            if (selected >= scroll + rows()) scroll = selected - rows() + 1;
            return true;
        }
        return super.keyPressed(ev);
    }

    @Override
    public void onClose() {
        minecraft.gui.setScreen(parent);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
        super.extractRenderState(g, mouseX, mouseY, a);
        int x = listX(), w = listW();
        g.text(font, title, x, 8, WHITE);
        String count = shown.size() + " found";
        g.text(font, count, x + w - font.width(count), 8, GRAY);
        int hovered = at(mouseX, mouseY);
        g.enableScissor(x, TOP, x + w, TOP + rows() * ROW);
        for (int i = 0; i < rows() && scroll + i < shown.size(); i++) {
            int r = scroll + i, y = TOP + i * ROW;
            Entry e = shown.get(r);
            if (r == selected) g.fill(x, y, x + w, y + ROW, PICKED);
            else if (r == hovered) g.fill(x, y, x + w, y + ROW, HOVER);
            if (!e.icon.isEmpty()) g.item(e.icon, x + 2, y + 2);
            g.text(font, e.name, x + 22, y + 6, WHITE);
            String id = font.plainSubstrByWidth(e.text, Math.max(0, w - 30 - font.width(e.name)));
            g.text(font, id, x + w - 4 - font.width(id), y + 6, GRAY);
        }
        g.disableScissor();
        if (shown.isEmpty()) g.centeredText(font, "Nothing matches", width / 2, TOP + 8, GRAY);
    }
}
