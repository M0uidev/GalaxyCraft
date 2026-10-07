import java.io.BufferedReader;
import java.io.InputStreamReader;

/**
 * The heartbeat's two clocks agree: the mod writes System.nanoTime() / 1e6 and the patched Dolphin
 * reads it against C++'s steady_clock (dolphin/galaxycraft/tests/clock_probe.cpp prints that).
 * Run with the probe's path: its time must fall between Java's before and after starting it. On
 * Linux both are CLOCK_MONOTONIC, on Windows both QueryPerformanceCounter; this proves it.
 *   java tools/ci/ClockCheck.java path/to/clock_probe
 */
public class ClockCheck {
    public static void main(String[] args) throws Exception {
        long worst = 0;
        for (int i = 0; i < 5; i++) {
            long before = System.nanoTime() / 1_000_000L;
            Process p = new ProcessBuilder(args[0]).redirectErrorStream(true).start();
            String out;
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                out = r.readLine();
            }
            p.waitFor();
            long after = System.nanoTime() / 1_000_000L;
            long probe = Long.parseLong(out.trim());
            System.out.printf("java before %d, steady_clock %d, java after %d%n", before, probe, after);
            // 1 ms of rounding either way (the two round to milliseconds separately).
            if (probe < before - 1 || probe > after + 1) {
                System.out.println("ClockCheck: the clocks disagree by "
                        + (probe < before ? before - probe : probe - after) + " ms");
                System.exit(1);
            }
            worst = Math.max(worst, after - before);
        }
        System.out.println("ClockCheck: the clocks agree (window " + worst + " ms)");
    }
}
