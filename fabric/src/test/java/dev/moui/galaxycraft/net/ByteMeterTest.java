package dev.moui.galaxycraft.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class ByteMeterTest {
    @Test
    void reportsBiggestFirstPerSecondAndStartsOver() {
        ByteMeter m = new ByteMeter();
        assertNull(m.report(0, 10)); // the first call only starts the clock
        m.add("Mobs", 10_240);
        m.add("Mobs", 10_240);
        m.add("State", 100);
        assertNull(m.report(5_000_000_000L, 10)); // period not over
        List<String> r = m.report(10_000_000_000L, 10);
        assertEquals(3, r.size());
        assertTrue(r.get(0).contains("2.0 KB/s"), r.get(0)); // (20480+100)/1024/10
        assertTrue(r.get(1).contains("Mobs") && r.get(1).contains("2 msgs"), r.get(1));
        assertTrue(r.get(2).contains("State"));
        List<String> again = m.report(20_000_000_000L, 10);
        assertEquals(1, again.size()); // nothing sent: just the total
    }
}
