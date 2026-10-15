package dev.moui.galaxycraft.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.net.InetAddress;
import java.util.List;
import org.junit.jupiter.api.Test;

class HostAddressesTest {
    @Test
    void kinds() {
        assertEquals(HostAddresses.Kind.HAMACHI, HostAddresses.classify(25, 4, 7, 9));
        assertEquals(HostAddresses.Kind.LAN, HostAddresses.classify(192, 168, 1, 20));
        assertEquals(HostAddresses.Kind.LAN, HostAddresses.classify(10, 0, 0, 5));
        assertEquals(HostAddresses.Kind.LAN, HostAddresses.classify(172, 31, 0, 1));
        assertEquals(HostAddresses.Kind.OTHER, HostAddresses.classify(172, 32, 0, 1));
        assertEquals(HostAddresses.Kind.OTHER, HostAddresses.classify(203, 0, 113, 5));
        assertNull(HostAddresses.classify(127, 0, 0, 1));
        assertNull(HostAddresses.classify(169, 254, 3, 3));
        assertNull(HostAddresses.classify(0, 0, 0, 0));
        assertNull(HostAddresses.classify(224, 0, 0, 1));
    }

    @Test
    void hamachiFirstAndOnlyIPv4() throws Exception {
        List<HostAddresses.Found> f = HostAddresses.of(List.of(InetAddress.getByName("192.168.1.20"), InetAddress.getByName("::1"),
                InetAddress.getByName("25.1.2.3"), InetAddress.getByName("127.0.0.1")));
        assertEquals(List.of(new HostAddresses.Found(HostAddresses.Kind.HAMACHI, "25.1.2.3"),
                new HostAddresses.Found(HostAddresses.Kind.LAN, "192.168.1.20")), f);
    }
}
