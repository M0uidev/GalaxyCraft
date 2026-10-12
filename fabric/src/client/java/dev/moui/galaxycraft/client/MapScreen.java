package dev.moui.galaxycraft.client;

import dev.moui.galaxycraft.gravity.GravityFrame;
import dev.moui.galaxycraft.map.MapShips;
import dev.moui.galaxycraft.map.MapView;
import dev.moui.galaxycraft.map.SystemInfo;
import dev.moui.galaxycraft.map.Trip;
import dev.moui.galaxycraft.universe.UPos;
import dev.moui.galaxycraft.universe.SystemIndex;
import dev.moui.galaxycraft.universe.Universe;
import dev.moui.galaxycraft.voxel.GalaxyCatalog;
import dev.moui.galaxycraft.voxel.gen.LegacyBiome;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.joml.Vector3d;

/**
 * The 3D map: systems of the universe around the player, and (entering one) its planets. An orbit
 * camera (drag turns, wheel zooms); click picks, double click or Enter enters a system, Backspace
 * goes up. A side panel names the pick and offers Warp, and (when a ship side is installed) Send
 * ship and Cancel course. Positions are units relative to Mario, so doubles stay small anywhere.
 */
final class MapScreen extends Screen {
    private static final double UNITS = 1 / GravityFrame.SCALE;
    private static final int WHITE = 0xFFFFFFFF, GRAY = 0xFFA0A0A0, PANEL = 0xFF101018, RING = 0xFF6EC1FF;
    /** Labels drawn at most: the nearest ones, the pick and the one under the mouse (thousands of systems would pile up). */
    private static final int LABELS = 12;
    /**
     * Discs drawn at most. Minecraft compares every filled rectangle with the ones before it, so the
     * cost grows with the square of the count: thousands of systems at once make each frame crawl.
     */
    private static final int MAX_DRAWN = 250;
    /** How near the mouse must be to a point, in pixels, to hover or click it (a star is often a 2 px dot). */
    private static final int PICK_PX = 14;
    /** Ring color by tier 0..5 (the border of a system's dot). */
    private static final int[] TIER = {0xFF7CFF7C, 0xFF4FD0FF, 0xFF8F7CFF, 0xFFFF7CE0, 0xFFFFA040, 0xFFFF4040};
    private static final int HOVER = 0xFFFFE066, TIP = 0xF0181A28;

    /** Something on the map: where (units from Mario), size (units), color, label. */
    private record Item(String label, Vector3d pos, double size, int color, Universe.Star star, UPos center, double radiusBlocks, int ring, String biome) {}

    private final MapView view = new MapView();
    private final List<Item> items = new ArrayList<>();
    private Universe.Star inside; // null: the universe level
    private UPos origin = UPos.ZERO; // where Mario was when the items were made: their positions are relative to it
    private int selected = -1;
    private int hovered = -1;
    private double lastDragX;

    MapScreen() {
        super(Component.literal("Galaxy Map"));
    }

    @Override
    protected void init() {
        view.width = width;
        view.height = height;
        if (items.isEmpty()) universeLevel();
        rebuildButtons();
    }

    private static String prettyBiome(String id) {
        String n = id == null ? "random" : id.substring(id.indexOf(':') + 1);
        return n.replace('_', ' ');
    }

    /** A planet's color from its biome (blueprints: violet). */
    private static int biomeColor(GalaxyCatalog.Entry e) {
        if (e.kind() == GalaxyCatalog.Kind.BLUEPRINT || e.biome() == null) return 0xFFC080FF;
        String b = e.biome();
        String[][] table = {{"ocean", "0xFF3A6FD8"}, {"river", "0xFF4F8AE0"}, {"desert", "0xFFE0C878"}, {"badlands", "0xFFC8643C"},
                {"savanna", "0xFFBDB35A"}, {"jungle", "0xFF2FA23A"}, {"swamp", "0xFF5F7F4F"}, {"dark_forest", "0xFF2F5F2F"},
                {"birch", "0xFF7DB86A"}, {"forest", "0xFF4F9A4A"}, {"windswept", "0xFF8A9A8A"}, {"taiga", "0xFF4F8A6A"},
                {"snowy", "0xFFEAF2F8"}, {"beach", "0xFFE8DCA0"}};
        for (String[] row : table) if (b.contains(row[0])) return (int) Long.decode(row[1]).longValue();
        return 0xFF8FD16B; // plains and the rest
    }

    private UPos mario() {
        Vector3d m = PlanetClient.marioUniverse();
        return m == null ? UPos.ZERO : UPos.of(m);
    }

    private void universeLevel() {
        inside = null;
        items.clear();
        selected = -1;
        Universe u = UniverseClient.universe();
        if (u == null) return;
        UPos m = mario();
        origin = m;
        for (Universe.Star s : u.around(m, UniverseClient.STAR_SECTORS)) {
            SystemInfo info = SystemInfo.of(s);
            String label = (s.home() ? "Home" : "System " + SystemIndex.name(s.sector())) + "  T" + info.tier();
            int color = SystemInfo.visited(s) ? info.star().argb : dim(info.star().argb);
            items.add(new Item(label, s.center().minus(m), (150 + 25 * s.planets()) * UNITS, color, s, s.center(), 0, TIER[Math.min(5, info.tier())], null));
        }
        view.focusOn(new Vector3d(), Math.max(1, UNITS * 6000));
    }

    private void systemLevel(Universe.Star star) {
        Universe u = UniverseClient.universe();
        if (u == null) return;
        inside = star;
        items.clear();
        selected = -1;
        UPos m = mario();
        origin = m;
        List<GalaxyCatalog.Entry> planets;
        if (star.home()) planets = PlanetClient.catalog();
        else planets = u.system(star, LegacyBiome.land()).entries();
        for (GalaxyCatalog.Entry e : planets) {
            UPos at = star.home() ? UPos.of(new Vector3d(e.x(), e.y(), e.z())) : star.center().plus(new Vector3d(e.x(), e.y(), e.z()));
            String kind = e.kind() == GalaxyCatalog.Kind.BLUEPRINT ? "blueprint" : prettyBiome(e.biome());
            items.add(new Item("Planet " + e.index() + "  " + kind, at.minus(m), e.radius() * UNITS, biomeColor(e), star, at, e.radius(), 0, kind));
        }
        view.focusOn(star.center().minus(m), Math.max(1, UNITS * 3000));
    }

    private void rebuildButtons() {
        clearWidgets();
        int x = width - 170, y = 40;
        addRenderableWidget(Button.builder(Component.literal("Done"), b -> onClose()).bounds(width / 2 - 50, height - 27, 100, 20).build());
        if (selected < 0) return;
        Item it = items.get(selected);
        if (inside == null && it.star() != null) {
            addRenderableWidget(Button.builder(Component.literal("Warp"), b -> {
                onClose();
                Warp.to(minecraft, it.star());
            }).bounds(x, y, 160, 20).build());
            y += 24;
            addRenderableWidget(Button.builder(Component.literal("Enter system"), b -> {
                systemLevel(it.star());
                rebuildButtons();
            }).bounds(x, y, 160, 20).build());
        } else if (inside != null) {
            for (MapShips.Ship ship : MapShips.provider().ships()) {
                boolean onTrip = MapClient.courses().get(ship.id()).isPresent();
                addRenderableWidget(Button.builder(Component.literal("Send " + ship.name()), b -> {
                    MapClient.send(ship, it.center(), it.radiusBlocks());
                    rebuildButtons();
                }).bounds(x, y, 160, 20).build());
                y += 24;
                if (onTrip) {
                    addRenderableWidget(Button.builder(Component.literal("Cancel " + ship.name()), b -> {
                        MapClient.cancel(ship);
                        rebuildButtons();
                    }).bounds(x, y, 160, 20).build());
                    y += 24;
                }
            }
        }
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent ev, boolean doubled) {
        if (super.mouseClicked(ev, doubled)) return true;
        if (ev.button() != 0) return false;
        lastDragX = ev.x();
        int i = pickAt(ev.x(), ev.y());
        if (i < 0 && doubled && selected >= 0) i = selected; // the first click moved the focus: the second is on the same item
        if (i >= 0) {
            selected = i;
            view.focusOn(items.get(i).pos(), view.distance());
            if (doubled && inside == null && items.get(i).star() != null) systemLevel(items.get(i).star());
            rebuildButtons();
            return true;
        }
        return false;
    }

    /** The item nearest the point within PICK_PX, or -1. */
    private int pickAt(double x, double y) {
        if (x >= width - 175) return -1; // the side panel
        List<Vector3d> pts = new ArrayList<>(items.size());
        for (Item it : items) pts.add(it.pos());
        return view.pick(pts, x, y, PICK_PX);
    }

    @Override
    public boolean isPauseScreen() {
        return false; // trips run on the world's clock: markers must keep moving
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent ev, double dx, double dy) {
        if (ev.button() == 0 && ev.x() < width - 175) {
            view.orbit(-dx * 0.01, dy * 0.01);
            return true;
        }
        return super.mouseDragged(ev, dx, dy);
    }

    @Override
    public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
        if (scrollY == 0) return false;
        view.zoom(scrollY > 0 ? 0.85 : 1 / 0.85);
        return true;
    }

    @Override
    public boolean keyPressed(KeyEvent ev) {
        if (ev.isConfirmation() && selected >= 0 && inside == null && items.get(selected).star() != null) {
            systemLevel(items.get(selected).star());
            rebuildButtons();
            return true;
        }
        if (ev.key() == 259 && inside != null) { // Backspace: up to the universe
            universeLevel();
            rebuildButtons();
            return true;
        }
        return super.keyPressed(ev);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
        g.fill(0, 0, width, height, 0xFF05060C);
        view.width = width;
        view.height = height;
        long now = MapClient.now();
        int n = items.size();
        double[] depth = new double[n];
        double[] sx = new double[n], sy = new double[n];
        boolean[] seen = new boolean[n];
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            var p = view.project(items.get(i).pos());
            if (p.isEmpty() || p.get().x() < -30 || p.get().y() < -30 || p.get().x() > width + 30 || p.get().y() > height + 30) continue;
            seen[i] = true;
            depth[i] = p.get().depth();
            sx[i] = p.get().x();
            sy[i] = p.get().y();
            order.add(i);
        }
        // Far to near, so nearer discs cover farther ones.
        order.sort(java.util.Comparator.comparingDouble((Integer i) -> -depth[i]));
        if (order.size() > MAX_DRAWN) order = new ArrayList<>(order.subList(order.size() - MAX_DRAWN, order.size()));
        // Labels: the nearest few, the pick, home and whatever is under the mouse.
        java.util.Set<Integer> labelled = new java.util.HashSet<>();
        for (int k = order.size() - 1; k >= 0 && labelled.size() < LABELS; k--) labelled.add(order.get(k));
        if (selected >= 0) labelled.add(selected);
        for (int i : order) {
            Item it = items.get(i);
            if (it.star() != null && it.star().home() && inside == null) labelled.add(i);
            if (Math.abs(sx[i] - mouseX) < 6 && Math.abs(sy[i] - mouseY) < 6) labelled.add(i);
        }
        hovered = pickAt(mouseX, mouseY);
        if (hovered >= 0) labelled.add(hovered);
        for (int i : order) {
            Item it = items.get(i);
            int r = (int) Math.max(2, Math.min(24, it.size() / depth[i] * (height / 2.0) / Math.tan(Math.toRadians(30))));
            if (it.ring() != 0) disc(g, (int) sx[i], (int) sy[i], r + 2, it.ring());
            disc(g, (int) sx[i], (int) sy[i], r, it.color());
            if (i == selected) ring(g, (int) sx[i], (int) sy[i], r + 4, WHITE);
            else if (i == hovered) ring(g, (int) sx[i], (int) sy[i], r + 4, HOVER);
        }
        if (inside != null) systemFrame(g, order, hovered);
        you(g);
        for (int i : order) {
            if (!labelled.contains(i)) continue;
            Item it = items.get(i);
            int r = (int) Math.max(2, Math.min(24, it.size() / depth[i] * (height / 2.0) / Math.tan(Math.toRadians(30))));
            g.text(font, it.label(), (int) sx[i] + r + 3, (int) sy[i] - 4, i == selected ? WHITE : GRAY);
        }
        if (inside != null) shipMarkers(g, now);
        g.fill(width - 175, 0, width, height, PANEL);
        g.centeredText(font, inside == null ? "Galaxy Map" : "System " + (inside.home() ? "Home" : SystemIndex.name(inside.sector())), width / 2, 12, WHITE);
        if (inside == null) {
            g.text(font, "Ring = tier", width - 168, height - 40, GRAY);
            for (int t = 0; t < TIER.length; t++) {
                g.fill(width - 168 + t * 26, height - 28, width - 168 + t * 26 + 8, height - 20, TIER[t]);
                g.text(font, "" + t, width - 168 + t * 26 + 11, height - 28, GRAY);
            }
        }
        if (selected >= 0) info(g, items.get(selected));
        else g.text(font, items.isEmpty() ? "No system in reach" : "Click a system", width - 168, 14, GRAY);
        g.text(font, inside == null
                ? "Click: select   Double click or Enter: enter the system   Drag: turn   Wheel: zoom"
                : "Click: select   Backspace: back to the galaxy   Drag: turn   Wheel: zoom", 8, height - 14, GRAY);
        super.extractRenderState(g, mouseX, mouseY, a);
        if (hovered >= 0 && mouseX < width - 175) tooltip(g, items.get(hovered), mouseX, mouseY);
    }

    /** Lines describing an item, shared by the tooltip and the side panel. */
    private List<String> describe(Item it) {
        List<String> out = new ArrayList<>();
        if (it.star() != null && inside == null) {
            SystemInfo info = SystemInfo.of(it.star());
            out.add("Tier " + info.tier() + ", " + info.star().name().toLowerCase().replace('_', ' ') + " star");
            out.add(it.star().planets() + " planets");
            out.add(SystemInfo.visited(it.star()) ? "Visited" : "Not visited yet");
            out.add(String.format("%,d blocks away", Math.round(it.pos().length() / UNITS)));
        } else {
            out.add("Radius " + (int) it.radiusBlocks() + " blocks");
            if (it.biome() != null) out.add(it.biome());
            out.add(String.format("%,d blocks away", Math.round(it.pos().length() / UNITS)));
        }
        return out;
    }

    /** Inside a system: the star at its center and the orbits (the selected and hovered planet's; all when there are few). */
    private void systemFrame(GuiGraphicsExtractor g, List<Integer> order, int hover) {
        Vector3d c = inside.center().minus(origin);
        var sp = view.project(c);
        if (sp.isPresent()) {
            int r = (int) Math.max(3, Math.min(20, 400 * UNITS / sp.get().depth() * (height / 2.0) / Math.tan(Math.toRadians(30))));
            disc(g, (int) sp.get().x(), (int) sp.get().y(), r, SystemInfo.of(inside).star().argb);
            g.text(font, inside.home() ? "Home star" : "Star", (int) sp.get().x() + r + 3, (int) sp.get().y() - 4, GRAY);
        }
        boolean all = order.size() <= 10;
        for (int i : order) {
            if (!(all || i == selected || i == hover)) continue;
            Vector3d off = new Vector3d(items.get(i).pos()).sub(c);
            double rad = Math.hypot(off.x, off.z);
            if (rad < 1e-9) continue;
            int col = i == selected ? WHITE : i == hover ? HOVER : 0xFF3C4468;
            int prevX = 0, prevY = 0;
            boolean prev = false;
            for (int k = 0; k <= 48; k++) {
                double a = k * Math.PI / 24;
                var p = view.project(new Vector3d(c.x + rad * Math.cos(a), c.y + off.y, c.z + rad * Math.sin(a)));
                if (p.isEmpty()) {
                    prev = false;
                    continue;
                }
                int x = (int) p.get().x(), y = (int) p.get().y();
                if (prev && (k & 1) == 0) line(g, prevX, prevY, x, y, col);
                prevX = x;
                prevY = y;
                prev = true;
            }
        }
    }

    /** Where the player is: a green cross with its label. */
    private void you(GuiGraphicsExtractor g) {
        var p = view.project(mario().minus(origin));
        if (p.isEmpty()) return;
        int x = (int) p.get().x(), y = (int) p.get().y();
        g.fill(x - 5, y, x + 6, y + 1, 0xFF7CFF7C);
        g.fill(x, y - 5, x + 1, y + 6, 0xFF7CFF7C);
        g.text(font, "You", x + 7, y + 3, 0xFF7CFF7C);
    }

    private static int dim(int argb) {
        int r = (argb >> 16 & 0xFF) * 6 / 10, gr = (argb >> 8 & 0xFF) * 6 / 10, b = (argb & 0xFF) * 6 / 10;
        return 0xFF000000 | r << 16 | gr << 8 | b;
    }

    private void tooltip(GuiGraphicsExtractor g, Item it, int mx, int my) {
        List<String> lines = new ArrayList<>();
        lines.add(it.label());
        lines.addAll(describe(it));
        if (inside == null && it.star() != null) lines.add("Double click to enter");
        int w = 0;
        for (String l : lines) w = Math.max(w, font.width(l));
        int h = lines.size() * 11 + 6;
        int x = Math.min(mx + 12, width - 175 - w - 10), y = Math.max(2, Math.min(my + 12, height - h - 2));
        g.fill(x - 4, y - 3, x + w + 4, y + h - 3, TIP);
        for (int k = 0; k < lines.size(); k++) g.text(font, lines.get(k), x, y + k * 11, k == 0 ? WHITE : GRAY);
    }

    private void info(GuiGraphicsExtractor g, Item it) {
        int x = width - 168, y = 14;
        g.text(font, it.label(), x, y, WHITE);
        for (String l : describe(it)) g.text(font, l, x, y += 12, GRAY);
    }

    private void shipMarkers(GuiGraphicsExtractor g, long now) {
        UPos m = mario();
        for (MapShips.Ship s : MapShips.provider().ships()) {
            Trip t = MapClient.courses().get(s.id()).orElse(null);
            UPos at = t == null ? s.position() : t.positionAt(now);
            var p = view.project(at.minus(m));
            if (p.isEmpty()) continue;
            ring(g, (int) p.get().x(), (int) p.get().y(), 4, RING);
            g.text(font, s.name() + (t == null ? "" : t.arrived(now) ? " (orbiting)" : " (en route)"), (int) p.get().x() + 7, (int) p.get().y() - 4, RING);
            if (t != null && !t.arrived(now)) {
                var d = view.project(t.course().to().minus(m));
                d.ifPresent(q -> line(g, (int) p.get().x(), (int) p.get().y(), (int) q.x(), (int) q.y(), RING));
            }
        }
    }

    /** A round-looking disc in at most three rectangles (see MAX_DRAWN: every fill is a cost). */
    private static void disc(GuiGraphicsExtractor g, int cx, int cy, int r, int color) {
        if (r <= 2) {
            g.fill(cx - r, cy - r, cx + r + 1, cy + r + 1, color);
            return;
        }
        int a = Math.max(1, r * 6 / 10), b = Math.max(1, r * 8 / 10);
        g.fill(cx - r, cy - a, cx + r + 1, cy + a + 1, color);
        g.fill(cx - b, cy - b, cx + b + 1, cy + b + 1, color);
        g.fill(cx - a, cy - r, cx + a + 1, cy + r + 1, color);
    }

    private static void ring(GuiGraphicsExtractor g, int cx, int cy, int r, int color) {
        for (int a = 0; a < 48; a++) {
            int x = cx + (int) Math.round(r * Math.cos(a * Math.PI / 24)), y = cy + (int) Math.round(r * Math.sin(a * Math.PI / 24));
            g.fill(x, y, x + 1, y + 1, color);
        }
    }

    private static void line(GuiGraphicsExtractor g, int x0, int y0, int x1, int y1, int color) {
        int n = Math.max(Math.abs(x1 - x0), Math.abs(y1 - y0));
        if (n == 0 || n > 4000) return;
        int step = Math.max(2, n / 150); // at most ~150 dots: each fill is a cost
        for (int i = 0; i <= n; i += step) {
            int x = x0 + (x1 - x0) * i / n, y = y0 + (y1 - y0) * i / n;
            g.fill(x, y, x + 1, y + 1, color);
        }
    }
}
