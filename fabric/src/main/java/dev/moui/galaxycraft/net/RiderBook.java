package dev.moui.galaxycraft.net;

import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Who rides which vehicle, and the movement keys each rider last sent. A vehicle with several riders is steered by
 * the one who mounted first; keys that stopped arriving count as released after {@link #STEER_TTL_TICKS}. Pure
 * logic, one thread (the host's server thread); K is the keys record.
 */
public final class RiderBook<K> {
    /** Server ticks (0.5 s) after which a rider's last keys are forgotten (the guest repeats them five times a second). */
    public static final int STEER_TTL_TICKS = 10;
    /** Riders on one vehicle at most (a boat seats two). */
    public static final int MAX_PER_VEHICLE = 2;

    private record Steer<K>(K keys, long at) {}

    /** In the order they mounted. */
    private final Map<UUID, Integer> vehicles = new LinkedHashMap<>();
    private final Map<UUID, Steer<K>> steers = new HashMap<>();

    /** The player mounts vehicle (an entity id); false when it is full. Mounting again moves the player to the new one. */
    public boolean mount(UUID player, int vehicle) {
        Integer was = vehicles.get(player);
        if (was != null && was == vehicle) return true;
        if (count(vehicle) >= MAX_PER_VEHICLE) return false;
        vehicles.remove(player);
        vehicles.put(player, vehicle);
        return true;
    }

    public void dismount(UUID player) {
        vehicles.remove(player);
        steers.remove(player);
    }

    /** The vehicle the player rides, or -1. */
    public int vehicleOf(UUID player) {
        Integer v = vehicles.get(player);
        return v == null ? -1 : v;
    }

    public int count(int vehicle) {
        int n = 0;
        for (int v : vehicles.values()) if (v == vehicle) n++;
        return n;
    }

    /** Whether this player is the first of the vehicle's riders: the only one whose keys push or steer it. */
    public boolean steers(UUID player) {
        Integer mine = vehicles.get(player);
        if (mine == null) return false;
        for (Map.Entry<UUID, Integer> e : vehicles.entrySet())
            if (e.getValue().equals(mine)) return e.getKey().equals(player);
        return false;
    }

    /** The keys a rider sent at tick now. */
    public void steer(UUID player, K keys, long now) {
        steers.put(player, new Steer<>(keys, now));
    }

    /** The rider's keys at tick now; released when none were sent or the last are older than the time to live. */
    public K keys(UUID player, long now, K released) {
        Steer<K> s = steers.get(player);
        return s == null || now - s.at() > STEER_TTL_TICKS ? released : s.keys();
    }

    /** Riders not in keep are dropped (their planet is gone). */
    public void retain(Set<UUID> keep) {
        for (Iterator<UUID> it = vehicles.keySet().iterator(); it.hasNext(); ) {
            UUID id = it.next();
            if (!keep.contains(id)) {
                it.remove();
                steers.remove(id);
            }
        }
    }
}
