package dev.moui.galaxycraft.net;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class EditSyncTest {
    static EditCodec.Msg msg(int seq, int origin, int... cells) {
        return new EditCodec.Msg(1, seq, origin, List.of(), cells, new int[cells.length]);
    }

    final List<int[]> sets = new ArrayList<>();

    void apply(EditSync s, EditCodec.Msg m, int... ids) {
        s.apply(m, ids, (cell, id) -> sets.add(new int[] {cell, id}));
    }

    @Test
    void anEchoClearsPendingAndApplies() {
        var s = new EditSync(0);
        int c = s.nextCseq();
        s.local(7, c);
        apply(s, msg(1, c, 7), 42);
        assertEquals(1, sets.size());
        assertEquals(42, sets.get(0)[1]);
        assertFalse(s.hasPending());
    }

    @Test
    void otherEditsToAPendingCellAreSkipped() {
        var s = new EditSync(0);
        s.local(7, s.nextCseq());
        apply(s, msg(1, 0, 7), 5);
        assertTrue(sets.isEmpty());
        apply(s, msg(2, 1, 9), 6);
        assertEquals(1, sets.size());
        assertEquals(9, sets.get(0)[0]);
        assertFalse(s.hasPending());
    }

    @Test
    void aLaterLocalEditSurvivesAnEarlierEcho() {
        var s = new EditSync(0);
        s.local(7, s.nextCseq());
        s.local(7, s.nextCseq());
        apply(s, msg(1, 1, 7), 5);
        assertTrue(sets.isEmpty());
        assertEquals(1, s.pendingCount());
        apply(s, msg(2, 2, 7), 6);
        assertEquals(1, sets.size());
        assertFalse(s.hasPending());
    }

    @Test
    void snapshotFiltersOldBatches() {
        assertFalse(new EditSync(5).accepts(5));
        assertTrue(new EditSync(5).accepts(6));
        assertTrue(new EditSync(5).accepts(0 + 7));
    }

    @Test
    void cseqsCountFromOne() {
        var s = new EditSync(0);
        assertEquals(1, s.nextCseq());
        assertEquals(2, s.nextCseq());
    }
}
