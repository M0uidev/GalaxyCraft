package dev.moui.galaxycraft.ship;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Who sits where in a flown ship besides its pilot (seat 0): passengers take the cabin's other cushions, one each.
 * The host decides; everyone keeps a copy of the answers. Pure.
 */
public final class ShipSeats {
    /** A seat that was freed. */
    public record Freed(String ship, int seat, String who) {}

    private final Map<String, Map<Integer, String>> seated = new HashMap<>();

    /** The passenger takes the first free cushion after the pilot's (or keeps theirs); the seat, or -1 if the cabin is full. */
    public int sit(String shipId, String who, int seatCount) {
        Map<Integer, String> s = seated.computeIfAbsent(shipId, k -> new HashMap<>());
        int mine = seatOf(shipId, who);
        if (mine >= 0) return mine;
        for (int i = 1; i < seatCount; i++)
            if (!s.containsKey(i)) {
                s.put(i, who);
                return i;
            }
        return -1;
    }

    /** The seat this passenger had on this ship, or -1 if they had none. */
    public int stand(String shipId, String who) {
        int seat = seatOf(shipId, who);
        if (seat >= 0) seated.get(shipId).remove(seat);
        return seat;
    }

    /** The passenger left the game: every seat they held. */
    public List<Freed> standAll(String who) {
        List<Freed> out = new ArrayList<>();
        for (var e : seated.entrySet())
            for (var it = e.getValue().entrySet().iterator(); it.hasNext(); ) {
                var s = it.next();
                if (s.getValue().equals(who)) {
                    out.add(new Freed(e.getKey(), s.getKey(), who));
                    it.remove();
                }
            }
        return out;
    }

    /** The ship has no pilot any more (it is parked): everybody gets out. */
    public List<Freed> clearShip(String shipId) {
        List<Freed> out = new ArrayList<>();
        Map<Integer, String> s = seated.remove(shipId);
        if (s != null) for (var e : s.entrySet()) out.add(new Freed(shipId, e.getKey(), e.getValue()));
        return out;
    }

    /** What a copy hears from the host: this seat has this passenger, or none (empty who). */
    public void set(String shipId, int seat, String who) {
        if (seat < 1) return;
        if (who == null || who.isEmpty()) {
            Map<Integer, String> s = seated.get(shipId);
            if (s != null) s.remove(seat);
        } else {
            seated.computeIfAbsent(shipId, k -> new HashMap<>()).values().remove(who); // one seat each
            seated.get(shipId).put(seat, who);
        }
    }

    public int seatOf(String shipId, String who) {
        Map<Integer, String> s = seated.get(shipId);
        if (s != null) for (var e : s.entrySet()) if (e.getValue().equals(who)) return e.getKey();
        return -1;
    }

    /** Every passenger: ship, seat, who. */
    public List<Freed> all() {
        List<Freed> out = new ArrayList<>();
        for (var e : seated.entrySet()) for (var s : e.getValue().entrySet()) out.add(new Freed(e.getKey(), s.getKey(), s.getValue()));
        return out;
    }

    public void clear() {
        seated.clear();
    }
}
