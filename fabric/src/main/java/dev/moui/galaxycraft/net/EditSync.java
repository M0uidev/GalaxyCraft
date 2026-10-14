package dev.moui.galaxycraft.net;

import java.util.HashMap;
import java.util.Map;

/**
 * A guest's view of one planet's edit stream. Its own edits are applied at once and remembered by cell
 * until the host's echo of them comes back; meanwhile the host's other changes to those cells are skipped
 * (so the cell does not flicker), and the echo (or a correction) settles it. Pure.
 */
public final class EditSync {
    public interface CellSetter {
        void set(int cell, int id);
    }

    private final int snapshotSeq;
    private final Map<Integer, Integer> pending = new HashMap<>();
    private int cseq;

    /** snapshotSeq: the host's sequence when the planet's bytes were taken; older batches are already in them. */
    public EditSync(int snapshotSeq) {
        this.snapshotSeq = snapshotSeq;
    }

    public int nextCseq() {
        return ++cseq;
    }

    /** The player changed this cell in batch cseq. */
    public void local(int cell, int cseq) {
        pending.put(cell, cseq);
    }

    public boolean accepts(int hseq) {
        return hseq > snapshotSeq;
    }

    /** Takes a host batch (ids already resolved to this game's), one per entry. */
    public void apply(EditCodec.Msg m, int[] ids, CellSetter set) {
        if (m.origin() > 0) pending.values().removeIf(v -> v <= m.origin());
        for (int i = 0; i < m.cells().length; i++)
            if (!pending.containsKey(m.cells()[i])) set.set(m.cells()[i], ids[i]);
    }

    public boolean hasPending() {
        return !pending.isEmpty();
    }

    public int pendingCount() {
        return pending.size();
    }
}
