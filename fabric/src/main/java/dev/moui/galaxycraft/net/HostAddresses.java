package dev.moui.galaxycraft.net;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The addresses a friend can try to reach a world opened to LAN at, for the host to read out: the world listens on every
 * interface, so each of these works if the network between them lets it through.
 */
public final class HostAddresses {
    public enum Kind {
        /** Hamachi's virtual network (25.0.0.0/8): works over the internet for both players with Hamachi running. */
        HAMACHI("Hamachi: friends who joined your Hamachi network"),
        /** 10/8, 172.16/12, 192.168/16: the same router / Wi-Fi only. */
        LAN("Same Wi-Fi or router"),
        /** Any other IPv4 on this machine (a public address, or another virtual network such as ZeroTier or Radmin). */
        OTHER("Other network (public address or another virtual network)");

        public final String label;

        Kind(String label) {
            this.label = label;
        }
    }

    public record Found(Kind kind, String ip) {}

    private HostAddresses() {}

    /** Which kind an IPv4 address is; null for one that is no use to a friend (loopback, link-local, any, multicast). */
    public static Kind classify(int a, int b, int c, int d) {
        if (a == 127 || a == 0 || a >= 224 || a == 169 && b == 254) return null;
        if (a == 25) return Kind.HAMACHI;
        if (a == 10 || a == 172 && b >= 16 && b <= 31 || a == 192 && b == 168) return Kind.LAN;
        return Kind.OTHER;
    }

    /** The usable IPv4 addresses of the given (already listed) addresses, Hamachi first, then LAN, then the rest. */
    public static List<Found> of(List<InetAddress> addresses) {
        List<Found> out = new ArrayList<>();
        for (InetAddress a : addresses) {
            if (!(a instanceof Inet4Address)) continue;
            byte[] b = a.getAddress();
            Kind k = classify(b[0] & 0xFF, b[1] & 0xFF, b[2] & 0xFF, b[3] & 0xFF);
            if (k != null) out.add(new Found(k, a.getHostAddress()));
        }
        out.sort((x, y) -> x.kind().compareTo(y.kind()));
        return out;
    }

    /** This machine's addresses, found now (empty if the system will not say). */
    public static List<Found> local() {
        List<InetAddress> all = new ArrayList<>();
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces()))
                if (ni.isUp() && !ni.isLoopback()) all.addAll(Collections.list(ni.getInetAddresses()));
        } catch (java.io.IOException | RuntimeException e) {
            return List.of();
        }
        return of(all);
    }
}
