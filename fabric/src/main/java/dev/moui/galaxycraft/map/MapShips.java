package dev.moui.galaxycraft.map;

import dev.moui.galaxycraft.universe.UPos;
import java.util.List;

/**
 * What the map needs from the ship side (the Toad ship): the ships it may send and a way to tell
 * it where a stopped ship is. Master has none: {@link #NONE} and the map hides the ship controls.
 */
public interface MapShips {
    /** A sendable ship: id, label, speed (universe units a tick) and where it is recorded (not on a trip). */
    record Ship(String id, String name, double unitsPerTick, UPos position) {}

    List<Ship> ships();

    /** A trip was cancelled: the ship now stands at this universe position. */
    void stopped(String shipId, UPos at);

    MapShips NONE = new MapShips() {
        @Override public List<Ship> ships() {
            return List.of();
        }

        @Override public void stopped(String shipId, UPos at) {}
    };

    static MapShips provider() {
        return Holder.current;
    }

    static void install(MapShips ships) {
        Holder.current = ships == null ? NONE : ships;
    }

    final class Holder {
        static volatile MapShips current = NONE;

        private Holder() {}
    }
}
