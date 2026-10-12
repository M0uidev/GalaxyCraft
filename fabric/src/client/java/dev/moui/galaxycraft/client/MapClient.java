package dev.moui.galaxycraft.client;

import dev.moui.galaxycraft.gravity.GravityFrame;
import dev.moui.galaxycraft.map.CourseStore;
import dev.moui.galaxycraft.map.MapShips;
import dev.moui.galaxycraft.map.RemoteControl;
import dev.moui.galaxycraft.map.Trip;
import dev.moui.galaxycraft.universe.UPos;
import java.io.IOException;
import java.nio.file.Path;
import net.minecraft.client.Minecraft;

/** The map's per-world state on the client: the trips of the world's ships (courses.json next to ships.json). */
final class MapClient {
    private static final double UNITS = 1 / GravityFrame.SCALE;
    private static Path file;
    private static CourseStore courses;

    private MapClient() {}

    /** A world's planets folder (null: no world). */
    static void enterWorld(Path planetsDir) {
        save();
        file = planetsDir == null ? null : planetsDir.resolve("courses.json");
        courses = file == null ? null : CourseStore.load(file);
    }

    /** Never null: an unloaded world has an empty store that is not saved. */
    static CourseStore courses() {
        return courses != null ? courses : (courses = CourseStore.empty());
    }

    static long now() {
        Minecraft mc = Minecraft.getInstance();
        return mc.level == null ? 0 : mc.level.getGameTime();
    }

    static Trip send(MapShips.Ship ship, UPos planetCenter, double planetRadiusBlocks) {
        Trip t = RemoteControl.send(courses(), ship.id(), ship.position(), planetCenter, planetRadiusBlocks, UNITS, ship.unitsPerTick(), now());
        save();
        return t;
    }

    static void cancel(MapShips.Ship ship) {
        UPos at = RemoteControl.cancel(courses(), ship.id(), ship.position(), now());
        MapShips.provider().stopped(ship.id(), at);
        save();
    }

    static void save() {
        if (courses == null || file == null) return;
        try {
            courses.save();
        } catch (IOException e) {
            dev.moui.galaxycraft.GalaxyCraft.LOG.warn("Could not save {}: {}", file, e.toString());
        }
    }
}
