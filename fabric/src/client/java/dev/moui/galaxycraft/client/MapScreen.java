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
    private static final int WHITE = 0xFFFFFFFF, GRAY = 0xFFA0A0A0, PANEL = 0xC0101018, RING = 0xFF6EC1FF;

    /** Something on the map: where (units from Mario), size (units), color, label. */
    private record Item(String label, Vector3d pos, double size, int color, Universe.Star star, UPos center, double radiusBlocks) {}

    private final MapView view = new MapView();
    private final List<Item> items = new ArrayList<>();
    private Universe.Star inside; // null: the universe level
    private int selected = -1;
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
        for (Universe.Star s : u.around(m, UniverseClient.STAR_SECTORS)) {
            SystemInfo info = SystemInfo.of(s);
            String label = (s.home() ? "Home" : "System " + SystemIndex.name(s.sector())) + "  T" + info.tier();
            items.add(new Item(label, s.center().minus(m), (150 + 25 * s.planets()) * UNITS, info.star().argb, s, s.center(), 0));
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
        List<GalaxyCatalog.Entry> planets;
        if (star.home()) planets = PlanetClient.catalog();
        else planets = u.system(star, LegacyBiome.land()).entries();
        for (GalaxyCatalog.Entry e : planets) {
            UPos at = star.home() ? UPos.of(new Vector3d(e.x(), e.y(), e.z())) : star.center().plus(new Vector3d(e.x(), e.y(), e.z()));
            items.add(new Item("Planet " + e.index() + "  r" + e.radius(), at.minus(m), e.radius() * UNITS, 0xFF8FD16B, star, at, e.radius()));
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
        List<Vector3d> pts = new ArrayList<>();
        for (Item it : items) pts.add(it.pos());
        int i = view.pick(pts, ev.x(), ev.y(), 8);
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
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) order.add(i);
        // Far to near, so nearer discs and labels cover farther ones.
        order.sort(java.util.Comparator.comparingDouble((Integer i) -> -view.project(items.get(i).pos()).map(MapView.Screen::depth).orElse(0.0)));
        for (int i : order) {
            Item it = items.get(i);
            var p = view.project(it.pos());
            if (p.isEmpty() || Math.abs(p.get().x()) > 20000 || Math.abs(p.get().y()) > 20000) continue;
            int r = (int) Math.max(2, Math.min(24, it.size() / p.get().depth() * (height / 2.0) / Math.tan(Math.toRadians(30))));
            disc(g, (int) p.get().x(), (int) p.get().y(), r, it.color());
            if (i == selected) ring(g, (int) p.get().x(), (int) p.get().y(), r + 4, WHITE);
            if (r >= 3 || i == selected) g.text(font, it.label(), (int) p.get().x() + r + 3, (int) p.get().y() - 4, i == selected ? WHITE : GRAY);
        }
        if (inside != null) shipMarkers(g, now);
        g.fill(width - 175, 0, width, height, PANEL);
        g.centeredText(font, inside == null ? "Galaxy Map" : "System " + (inside.home() ? "Home" : SystemIndex.name(inside.sector())), width / 2, 12, WHITE);
        if (selected >= 0) info(g, items.get(selected));
        else g.text(font, items.isEmpty() ? "No system in reach" : "Click a system", width - 168, 14, GRAY);
        g.text(font, "Drag: turn   Wheel: zoom   Backspace: up", 8, height - 14, GRAY);
        super.extractRenderState(g, mouseX, mouseY, a);
    }

    private void info(GuiGraphicsExtractor g, Item it) {
        int x = width - 168, y = 14;
        g.text(font, it.label(), x, y, WHITE);
        if (it.star() != null && inside == null) {
            SystemInfo info = SystemInfo.of(it.star());
            g.text(font, "Tier " + info.tier() + ", " + info.star().name().toLowerCase().replace('_', ' ') + " star", x, y + 12, GRAY);
            g.text(font, it.star().planets() + " planets", x, y + 24, GRAY);
            g.text(font, String.format("%,d blocks", Math.round(it.pos().length() / UNITS)), x, y + 36, GRAY);
        } else {
            g.text(font, "Radius " + (int) it.radiusBlocks() + " blocks", x, y + 12, GRAY);
        }
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

    private static void disc(GuiGraphicsExtractor g, int cx, int cy, int r, int color) {
        for (int dy = -r; dy <= r; dy++) {
            int w = (int) Math.sqrt((double) r * r - (double) dy * dy);
            g.fill(cx - w, cy + dy, cx + w + 1, cy + dy + 1, color);
        }
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
        for (int i = 0; i <= n; i += 2) {
            int x = x0 + (x1 - x0) * i / n, y = y0 + (y1 - y0) * i / n;
            g.fill(x, y, x + 1, y + 1, color);
        }
    }
}
