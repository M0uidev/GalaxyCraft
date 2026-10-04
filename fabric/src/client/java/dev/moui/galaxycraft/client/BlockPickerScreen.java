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
 * Chooses a block for a planet layer: every Minecraft block with its icon and the name the
 * inventory gives it, narrowed by what is typed (words of the name or of the id). Text with a
 * state, as in /setblock (minecraft:oak_log[axis=x]), is offered as it is. Clicking one, or Enter
 * on the highlighted one, hands its text back and returns to the editor.
 */
public final class BlockPickerScreen extends Screen {
    private static final int WHITE = 0xFFFFFFFF, GRAY = 0xFFA0A0A0, HOVER = 0x40FFFFFF, PICKED = 0x60FFFFFF;
    private static final int ROW = 20, TOP = 44;

    /** A block as listed: the text it is saved as and what the search looks in. */
    private record Entry(String text, BlockState state, String name, String haystack) {
        static Entry of(String text, BlockState state) {
            String name = state.getBlock().getName().getString();
            return new Entry(text, state, name, (name + " " + text).toLowerCase(Locale.ROOT));
        }
    }

    private static List<Entry> all;
    private final Screen parent;
    private final Consumer<String> pick;
    private final String current;
    private List<Entry> shown = List.of();
    private String query = "";
    private int scroll, selected;
    private EditBox search;

    public BlockPickerScreen(Screen parent, String current, Consumer<String> pick) {
        super(Component.literal("Choose a block"));
        this.parent = parent;
        this.current = current;
        this.pick = pick;
    }

    private static List<Entry> all() {
        if (all == null) {
            all = new ArrayList<>();
            for (Block b : BuiltInRegistries.BLOCK)
                if (!b.defaultBlockState().isAir())
                    all.add(Entry.of(BuiltInRegistries.BLOCK.getKey(b).toString(), b.defaultBlockState()));
        }
        return all;
    }

    private void filter() {
        String[] words = query.toLowerCase(Locale.ROOT).trim().split("\\s+");
        List<Entry> out = new ArrayList<>();
        // A block with its state typed out goes first, as it is.
        if (query.contains("[")) {
            try {
                out.add(Entry.of(query.trim(), BlockStateParser.parseForBlock(BuiltInRegistries.BLOCK, query.trim(), false).blockState()));
            } catch (Exception ignored) {
                // Not one yet: the list below still narrows by its words.
            }
        }
        for (Entry e : all()) {
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
        search.setHint(Component.literal("Search: name or id (grass, oak log, minecraft:stone...)"));
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
        g.text(font, "Choose a block", x, 8, WHITE);
        String count = shown.size() + " blocks";
        g.text(font, count, x + w - font.width(count), 8, GRAY);
        int hovered = at(mouseX, mouseY);
        g.enableScissor(x, TOP, x + w, TOP + rows() * ROW);
        for (int i = 0; i < rows() && scroll + i < shown.size(); i++) {
            int r = scroll + i, y = TOP + i * ROW;
            Entry e = shown.get(r);
            if (r == selected) g.fill(x, y, x + w, y + ROW, PICKED);
            else if (r == hovered) g.fill(x, y, x + w, y + ROW, HOVER);
            ItemStack icon = new ItemStack(e.state.getBlock().asItem());
            if (!icon.isEmpty()) g.item(icon, x + 2, y + 2);
            g.text(font, e.name, x + 22, y + 6, WHITE);
            String id = font.plainSubstrByWidth(e.text, Math.max(0, w - 30 - font.width(e.name)));
            g.text(font, id, x + w - 4 - font.width(id), y + 6, GRAY);
        }
        g.disableScissor();
        if (shown.isEmpty()) g.centeredText(font, "No block matches", width / 2, TOP + 8, GRAY);
    }
}
