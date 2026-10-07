import dev.moui.galaxycraft.proto.Layout;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Plays the mod's part against a running patched Dolphin (CI boots it with tools/ci/make_dol.py's
 * program): finds the shared memory where the mod looks for it (Layout.SHM_PATH), checks the host
 * wrote it (magic, its pid, a fresh heartbeat by this process's clock), beats as the mod does, and
 * asks Dolphin over the dev control channel how old it sees that heartbeat.
 *   javac -d out fabric/src/main/java/dev/moui/galaxycraft/proto/Layout.java tools/ci/ShmCheck.java
 *   java -cp out ShmCheck [Dolphin.exe: the host's executable, whose pid it must have written]
 */
public class ShmCheck {
    static final ValueLayout.OfInt I = ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
    static final ValueLayout.OfLong L = ValueLayout.JAVA_LONG_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);

    static long now() {
        return System.nanoTime() / 1_000_000L;
    }

    static void fail(String why) {
        System.out.println("ShmCheck: " + why);
        System.exit(1);
    }

    public static void main(String[] args) throws Exception {
        Path shm = Path.of(Layout.SHM_PATH);
        System.out.println("ShmCheck: " + shm);
        long deadline = now() + 90_000;
        while (!(Files.isRegularFile(shm) && Files.size(shm) == Layout.TOTAL_SIZE)) {
            if (now() > deadline) fail("no shared memory file of the right size at " + shm);
            Thread.sleep(200);
        }
        try (FileChannel ch = FileChannel.open(shm, StandardOpenOption.READ, StandardOpenOption.WRITE);
             Arena arena = Arena.ofShared()) {
            MemorySegment seg = ch.map(FileChannel.MapMode.READ_WRITE, 0, Layout.TOTAL_SIZE, arena);
            while (seg.get(I, Layout.H_MAGIC) != Layout.MAGIC || seg.get(L, Layout.H_HOST_HEARTBEAT) == 0) {
                if (now() > deadline) fail("no magic or host heartbeat (magic " + Integer.toHexString(seg.get(I, Layout.H_MAGIC)) + ")");
                Thread.sleep(50);
            }
            int hostPid = seg.get(I, Layout.H_HOST_PID);
            System.out.println("ShmCheck: host pid " + hostPid + ", protocol " + seg.get(I, Layout.H_VERSION));
            if (args.length > 0) {
                // The pids of the running programs named so (Dolphin.exe): the host's must be one.
                java.util.List<Long> pids = ProcessHandle.allProcesses()
                        .filter(h -> h.info().command().map(c -> Path.of(c).getFileName().toString().equalsIgnoreCase(args[0])).orElse(false))
                        .map(ProcessHandle::pid).toList();
                if (!pids.contains((long) hostPid)) fail("host pid " + hostPid + " is not " + args[0] + "'s " + pids);
            }
            long hostAge = now() - seg.get(L, Layout.H_HOST_HEARTBEAT);
            System.out.println("ShmCheck: Dolphin's heartbeat is " + hostAge + " ms old by Java's clock");
            if (hostAge < -1 || hostAge > 2000) fail("the clocks disagree (or Dolphin stalls): " + hostAge + " ms");

            // The mod's heartbeat for a few seconds, then Dolphin says how old it sees it.
            seg.set(I, Layout.H_MOD_PID, (int) ProcessHandle.current().pid());
            Path ctl = Path.of(Layout.SHM_DIR, "galaxycraft_ctl");
            Path out = Path.of(Layout.SHM_DIR, "galaxycraft_ctl.out");
            Files.deleteIfExists(out);
            long until = now() + 3000;
            String status = null;
            boolean asked = false;
            while (status == null) {
                seg.set(L, Layout.H_MOD_HEARTBEAT, now());
                if (!asked && now() > until) {
                    Files.writeString(ctl, "status\n");
                    asked = true;
                }
                if (asked && Files.exists(out)) {
                    for (String line : Files.readAllLines(out))
                        if (line.contains("mod_heartbeat_age_ms=")) status = line;
                }
                if (now() > deadline + 30_000) fail("no answer to ctl status in " + out);
                Thread.sleep(10);
            }
            System.out.println("ShmCheck: " + status);
            long modAge = Long.parseLong(status.replaceAll(".*mod_heartbeat_age_ms=(-?\\d+).*", "$1"));
            if (modAge < -1 || modAge > 1000) fail("Dolphin sees the mod's heartbeat " + modAge + " ms old: the clocks disagree");
            System.out.println("ShmCheck: ok, both sides share the memory and agree on the time");
        }
    }
}
