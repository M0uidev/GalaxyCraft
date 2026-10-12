package dev.moui.galaxycraft.client;

import com.mojang.blaze3d.platform.InputConstants;
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
    /** The side panel's width: the map is centered in what is left of it. */
    private static final int PANEL_W = 175, TOP = 24;
    private static final int HOVER = 0xFFFFE066, TIP = 0xF0181A28;

    /** Something on the map: where (units from Mario), size (units), color, label. */
    private record Item(String label, Vector3d pos, double size, int color, Universe.Star star, UPos center, double radiusBlocks, int ring, String biome) {}

    private final MapView view = new MapView();
    private final List<Item> items = new ArrayList<>();
    /** Rectangles of the labels drawn this frame: a label that would cover one is dropped. */
    private final List<int[]> taken = new ArrayList<>();
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
        view.width = width - PANEL_W;
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
            SystemInfo info = SystemInfo.of(u, s);
            String label = (s.home() ? "Home" : "System " + SystemIndex.name(s.sector())) + "  T" + info.tier();
            int color = visited(s) ? info.star().argb : dim(info.star().argb);
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
        int x = width - 170, y = selected < 0 ? 40 : panelEnd(items.get(selected));
        addRenderableWidget(Button.builder(Component.literal("Done"), b -> onClose()).bounds(width - 170, height - 68, 160, 20).build());
        if (selected < 0) return;
        Item it = items.get(selected);
        if (inside == null && it.star() != null) {
            Button warp = Button.builder(Component.literal("Warp"), b -> {
                onClose();
                Warp.to(minecraft, it.star());
            }).bounds(x, y, 160, 20).build();
            warp.active = Warp.allowed(minecraft); // else the lines under the buttons say why
            addRenderableWidget(warp);
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
        if (ev.button() != InputConstants.MOUSE_BUTTON_LEFT) return false; // 26.x: SDL numbers, left is 1
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
        if (x >= width - PANEL_W) return -1; // the side panel
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
        if (ev.button() == InputConstants.MOUSE_BUTTON_LEFT && ev.x() < width - PANEL_W) {
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
        if ((ev.isConfirmation() || ev.key() == 257 || ev.key() == 335) && selected >= 0 && inside == null && items.get(selected).star() != null) {
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
        if (inside == null) MapSprites.dust(g, 0, TOP, width - PANEL_W, height - TOP, 0xFFB0C0E8);
        view.width = width - PANEL_W;
        view.height = height;
        taken.clear();
        taken.add(new int[] {0, height - 30, width - PANEL_W, height}); // the help lines
        long now = MapClient.now();
        int n = items.size();
        double[] depth = new double[n];
        double[] sx = new double[n], sy = new double[n];
        boolean[] seen = new boolean[n];
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            var p = view.project(items.get(i).pos());
            if (p.isEmpty() || p.get().x() < -30 || p.get().y() < -30 || p.get().x() > view.width + 30 || p.get().y() > height + 30) continue;
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
        if (inside != null) systemFrame(g, order, hovered); // under the planets
        double near0 = Double.MAX_VALUE, far0 = 0;
        for (int i : order) {
            near0 = Math.min(near0, depth[i]);
            far0 = Math.max(far0, depth[i]);
        }
        for (int i : order) {
            Item it = items.get(i);
            int x = (int) sx[i], y = (int) sy[i];
            int r = (int) Math.max(2, Math.min(24, it.size() / depth[i] * (height / 2.0) / Math.tan(Math.toRadians(30))));
            if (inside == null) {
                double near = far0 > near0 ? 1 - (depth[i] - near0) / (far0 - near0) : 1; // 1 nearest drawn, 0 farthest
                star(g, x, y, r, it, near);
            } else {
                MapSprites.sphere(g, x, y, 2 * r + 1, it.color());
            }
            if (i == selected) MapSprites.ring(g, x, y, 2 * (r + 7), WHITE);
            else if (i == hovered) MapSprites.ring(g, x, y, 2 * (r + 7), HOVER);
        }
        you(g);
        // Labels, most wanted first (the pick and the hovered always win), none that covers another.
        java.util.Set<Integer> shown = new java.util.HashSet<>(order);
        List<Integer> prio = new ArrayList<>();
        if (selected >= 0 && shown.contains(selected)) prio.add(selected);
        if (hovered >= 0 && hovered != selected && shown.contains(hovered)) prio.add(hovered);
        for (int k = order.size() - 1; k >= 0; k--) {
            int i = order.get(k);
            if (labelled.contains(i) && i != selected && i != hovered) prio.add(i);
        }
        for (int i : prio) {
            Item it = items.get(i);
            int r = (int) Math.max(2, Math.min(24, it.size() / depth[i] * (height / 2.0) / Math.tan(Math.toRadians(30))));
            boolean key = i == selected || i == hovered;
            String text = key || inside == null ? it.label() : shortLabel(it);
            label(g, text, (int) sx[i] + r + 3, (int) sy[i] - 4, i == selected ? WHITE : GRAY, key);
        }
        if (inside != null) shipMarkers(g, now);
        g.fill(0, 0, width - PANEL_W, TOP - 2, 0xFF05060C); // the title's strip: nothing shows through
        g.fill(width - PANEL_W, 0, width, height, PANEL);
        g.centeredText(font, inside == null ? "Galaxy Map" : "System " + (inside.home() ? "Home" : SystemIndex.name(inside.sector())), (width - PANEL_W) / 2, 8, WHITE);
        if (inside == null) {
            g.text(font, "Ring = tier", width - 168, height - 40, GRAY);
            for (int t = 0; t < TIER.length; t++) {
                g.fill(width - 168 + t * 26, height - 28, width - 168 + t * 26 + 8, height - 20, TIER[t]);
                g.text(font, "" + t, width - 168 + t * 26 + 11, height - 28, GRAY);
            }
        }
        if (selected >= 0) info(g, items.get(selected));
        if (selected >= 0 && inside == null && items.get(selected).star() != null && !Warp.allowed(minecraft)) {
            int wy = panelEnd(items.get(selected)) + 52;
            g.text(font, "Warp needs creative mode", width - 168, wy, GRAY);
            g.text(font, "or cheats on.", width - 168, wy + 11, GRAY);
        }
        else overview(g);
        g.text(font, "Click: select   Drag: turn   Wheel: zoom", 8, height - 26, GRAY);
        g.text(font, inside == null ? "Double click or Enter: enter the system" : "Backspace: back to the galaxy", 8, height - 14, GRAY);
        super.extractRenderState(g, mouseX, mouseY, a);
        if (hovered >= 0 && mouseX < width - PANEL_W) tooltip(g, items.get(hovered), mouseX, mouseY);
    }

    /** Lines describing an item, shared by the tooltip and the side panel. */
    private List<String> describe(Item it) {
        List<String> out = new ArrayList<>();
        if (it.star() != null && inside == null) {
            SystemInfo info = SystemInfo.of(UniverseClient.universe(), it.star());
            out.add("Tier " + info.tier() + ", " + info.star().name().toLowerCase().replace('_', ' ') + " star");
            out.add(it.star().planets() + " planets, radius " + it.star().minRadius() + " to " + it.star().maxRadius());
            out.add(visited(it.star()) ? "Visited" : "Not visited yet");
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
            int r = (int) Math.max(4, Math.min(16, 400 * UNITS / sp.get().depth() * (height / 2.0) / Math.tan(Math.toRadians(30))));
            int sc = SystemInfo.of(UniverseClient.universe(), inside).star().argb;
            MapSprites.glow(g, (int) sp.get().x(), (int) sp.get().y(), 2 * r * 4, MapSprites.tint(sc, 0.8));
            MapSprites.sphere(g, (int) sp.get().x(), (int) sp.get().y(), 2 * r + 1, sc);
            label(g, inside.home() ? "Home star" : "Star", (int) sp.get().x() + r + 3, (int) sp.get().y() - 4, GRAY, true);
        }
        plate(g, c, order);
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
        label(g, "You", x + 7, y + 3, 0xFF7CFF7C, true);
    }

    /** Draws a label unless it would cover one already drawn (force: always) or sits in the title's strip. */
    private void label(GuiGraphicsExtractor g, String text, int x, int y, int color, boolean force) {
        int w = font.width(text);
        int[] r = {x - 1, y - 1, x + w + 1, y + 9};
        if (y < TOP) return;
        if (!force) for (int[] t : taken) if (r[0] < t[2] && r[2] > t[0] && r[1] < t[3] && r[3] > t[1]) return;
        g.text(font, text, x, y, color);
        taken.add(r);
    }

    /** "P5 r48" for a planet's crowded label (the biome is in the tooltip and the panel). */
    private static String shortLabel(Item it) {
        String l = it.label();
        int sp = l.indexOf(' ', 7);
        return "P" + (sp < 0 ? l.substring(7) : l.substring(7, sp)) + " r" + (int) it.radiusBlocks();
    }

    private static boolean visited(Universe.Star s) {
        return SystemInfo.visited(Warp.visited(), s);
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
        int x = Math.min(mx + 12, width - PANEL_W - w - 10), y = Math.max(2, Math.min(my + 12, height - h - 2));
        g.fill(x - 4, y - 3, x + w + 4, y + h - 3, TIP);
        for (int k = 0; k < lines.size(); k++) g.text(font, lines.get(k), x, y + k * 11, k == 0 ? WHITE : GRAY);
    }

    /** Where the buttons start in the side panel: below the pick's lines and the ships' ones. */
    private int panelEnd(Item it) {
        int lines = 1 + describe(it).size();
        var ships = MapShips.provider().ships();
        if (!ships.isEmpty()) lines += 1 + ships.size();
        return 14 + 12 * lines + 8;
    }

    private void info(GuiGraphicsExtractor g, Item it) {
        int x = width - 168, y = 14;
        g.fill(x, y + 1, x + 7, y + 8, it.ring() != 0 ? it.ring() : it.color()); // the tier's color, or the planet's
        g.text(font, it.label(), x + 11, y, WHITE);
        for (String l : describe(it)) g.text(font, l, x, y += 12, GRAY);
        var ships = MapShips.provider().ships();
        if (ships.isEmpty()) return;
        g.text(font, "Ships", x, y += 12, WHITE);
        long now = MapClient.now();
        for (MapShips.Ship s : ships) {
            Trip t = MapClient.courses().get(s.id()).orElse(null);
            String st = t == null ? "parked" : t.arrived(now) ? "orbiting" : "en route";
            g.text(font, s.name() + ": " + st, x, y += 12, RING);
        }
    }

    /** Nothing picked: the system's planets, or a hint. */
    private void overview(GuiGraphicsExtractor g) {
        int x = width - 168, y = 28;
        if (items.isEmpty()) {
            g.text(font, "No system in reach", x, 14, GRAY);
            return;
        }
        if (inside == null) {
            g.text(font, items.size() + " systems in view", x, 14, WHITE);
            g.text(font, "Click one for details", x, 26, GRAY);
            return;
        }
        g.text(font, items.size() + " planets", x, 14, WHITE);
        for (int i = 0; i < Math.min(items.size(), 10); i++) {
            Item it = items.get(i);
            g.fill(x, y + 1 + i * 12, x + 6, y + 7 + i * 12, it.color());
            g.text(font, it.label() + "  r" + (int) it.radiusBlocks(), x + 10, y + i * 12, GRAY);
        }
        if (items.size() > 10) g.text(font, "+" + (items.size() - 10) + " more", x, y + 120, GRAY);
    }

    private void shipMarkers(GuiGraphicsExtractor g, long now) {
        UPos m = mario();
        for (MapShips.Ship s : MapShips.provider().ships()) {
            Trip t = MapClient.courses().get(s.id()).orElse(null);
            UPos at = t == null ? s.position() : t.positionAt(now);
            var p = view.project(at.minus(m));
            if (p.isEmpty()) continue;
            MapSprites.ring(g, (int) p.get().x(), (int) p.get().y(), 11, RING);
            g.text(font, s.name() + (t == null ? "" : t.arrived(now) ? " (orbiting)" : " (en route)"), (int) p.get().x() + 7, (int) p.get().y() - 4, RING);
            if (t != null && !t.arrived(now)) {
                var d = view.project(t.course().to().minus(m));
                d.ifPresent(q -> line(g, (int) p.get().x(), (int) p.get().y(), (int) q.x(), (int) q.y(), RING));
            }
        }
    }

    /** A star of the universe level: a halo that fades with distance, a lit core, the tier's ring (home: a green one more). */
    private static void star(GuiGraphicsExtractor g, int x, int y, int r, Item it, double near) {
        int c = it.color();
        MapSprites.glow(g, x, y, 2 * (r * 3 + 5), MapSprites.tint(c, 0.25 + 0.5 * near));
        MapSprites.sphere(g, x, y, 2 * r + 1, MapSprites.tint(c, 0.5 + 0.5 * near));
        if (it.ring() != 0) MapSprites.ring(g, x, y, 2 * (r + 4), MapSprites.tint(it.ring(), 0.35 + 0.65 * near));
        if (it.star() != null && it.star().home()) MapSprites.ring(g, x, y, 2 * (r + 10), 0xFF7CFF7C);
    }

    /** Inside a system: the plate the orbits lie on (spokes out to the farthest orbit) and a stem from each planet down to it. */
    private void plate(GuiGraphicsExtractor g, Vector3d c, List<Integer> order) {
        double far = 0;
        for (int i : order) {
            Vector3d off = new Vector3d(items.get(i).pos()).sub(c);
            far = Math.max(far, Math.hypot(off.x, off.z));
        }
        if (far <= 0) return;
        far *= 1.15;
        for (int s = 0; s < 8; s++) {
            double a = s * Math.PI / 4;
            var p = view.project(new Vector3d(c.x + far * Math.cos(a), c.y, c.z + far * Math.sin(a)));
            var q = view.project(c);
            if (p.isPresent() && q.isPresent()) dots(g, (int) q.get().x(), (int) q.get().y(), (int) p.get().x(), (int) p.get().y(), 14, 0xFF1D2A48);
        }
        int n = 0;
        for (int k = order.size() - 1; k >= 0 && n < 16; k--, n++) { // the nearest 16
            Vector3d pos = items.get(order.get(k)).pos();
            var p = view.project(pos);
            var base = view.project(new Vector3d(pos.x, c.y, pos.z));
            if (p.isPresent() && base.isPresent())
                dots(g, (int) p.get().x(), (int) p.get().y(), (int) base.get().x(), (int) base.get().y(), 8, pos.y >= c.y ? 0xFF3A6EA8 : 0xFFC8803A);
        }
    }

    /** A dotted segment of at most `count` dots (each rectangle is a cost). */
    private static void dots(GuiGraphicsExtractor g, int x0, int y0, int x1, int y1, int count, int color) {
        if (Math.abs(x1 - x0) + Math.abs(y1 - y0) > 4000) return;
        for (int i = 0; i <= count; i++) {
            int x = x0 + (x1 - x0) * i / count, y = y0 + (y1 - y0) * i / count;
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
