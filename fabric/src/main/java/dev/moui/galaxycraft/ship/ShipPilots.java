package dev.moui.galaxycraft.ship;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Who flies which ship: one pilot at a time. The host decides; everyone keeps a copy of the answers. Pure. */
public final class ShipPilots {
    private final Map<String, String> pilot = new HashMap<>();

    /** The pilot takes the ship if nobody else has it (or already has it). */
    public boolean board(String shipId, String who) {
        String now = pilot.putIfAbsent(shipId, who);
        return now == null || now.equals(who);
    }

    /** The pilot lets go; false if they were not the pilot. */
    public boolean release(String shipId, String who) {
        return pilot.remove(shipId, who);
    }

    /** Every ship this pilot has (they left the game): the ids freed. */
    public List<String> releaseAll(String who) {
        List<String> freed = new ArrayList<>();
        for (var it = pilot.entrySet().iterator(); it.hasNext(); ) {
            var e = it.next();
            if (e.getValue().equals(who)) {
                freed.add(e.getKey());
                it.remove();
            }
        }
        return freed;
    }

    /** What a copy hears from the host: this ship has this pilot, or none (empty who). */
    public void set(String shipId, String who) {
        if (who == null || who.isEmpty()) pilot.remove(shipId);
        else pilot.put(shipId, who);
    }

    public String pilotOf(String shipId) {
        return pilot.get(shipId);
    }

    public Map<String, String> all() {
        return Map.copyOf(pilot);
    }

    public void clear() {
        pilot.clear();
    }
}
