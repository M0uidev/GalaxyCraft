package dev.moui.galaxycraft.voxel;

import java.util.function.BooleanSupplier;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

/** Flying over a planet at elytra speeds with PlanetClient's budget (6 ms a tick): the far view stays at the block distance. */
class FlightLoadTest {
    @Test void flyingFastTheBlocksKeepUp() {
        for (double render : new double[] {64, 96}) for (double speed : new double[] {3}) {
            PlanetSession s = new PlanetSession(80);
            s.setRenderDistance(render);
            s.spawn(128, new Vector3d(), new Vector3d(0, 1, 0));
            VoxelPlanet p = s.planet();
            double alt = 136;
            Vector3d dir = new Vector3d(1, 0, 0), up = new Vector3d(0, -1, 0);
            // Settle above the start, everything sent.
            Vector3d at = new Vector3d(s.center()).add(new Vector3d(up).mul(alt * 80));
            s.update(3, 100, at);
            while (s.peek() != null) s.sent();
            double minNear = 1e9, sumNear = 0;
            int ticks = 0, starved = 0;
            long chunkNs = 0; int farMsgs = 0, chunkMsgs = 0; long farBytes = 0, chunkBytes = 0;
            for (double ang = 0; ang < Math.PI / 2; ang += speed / alt) {
                Vector3d local = new Vector3d(up).mul(Math.cos(ang)).add(new Vector3d(dir).mul(Math.sin(ang))).mul(alt);
                s.update(3, 100, new Vector3d(local).mul(80).add(s.center()));
                long end = System.nanoTime() + 6_000_000;
                BooleanSupplier bulk = () -> System.nanoTime() - end < 0;
                long t0 = System.nanoTime();
                for (PlanetSession.Msg m; (m = s.peek(bulk)) != null; ) {
                    long t1 = System.nanoTime();
                    boolean isFar = m.type() == dev.moui.galaxycraft.proto.Layout.MSG_CHUNK
                            && (java.nio.ByteBuffer.wrap(m.payload()).order(java.nio.ByteOrder.LITTLE_ENDIAN).getInt(0) & PlanetSession.FAR_VIEW) != 0;
                    if (isFar) { farMsgs++; farBytes += m.payload().length; } else { chunkMsgs++; chunkBytes += m.payload().length; }
                    s.sent();
                }
                chunkNs += System.nanoTime() - t0;
                if (s.queued() > 0) starved++;
                // Nearest far view drawn up close, measured along the ground.
                double near = 1e9;
                for (int t = 0; t < PlanetLod.tileCount(p); t++) {
                    if (!s.farDrawnNear(t)) continue;
                    int[] cs = PlanetLod.chunksOfTile(p, t);
                    Vector3d c = new Vector3d();
                    double[] r = new double[1];
                    p.sphere(cs[cs.length / 2], c, r);
                    c.normalize(128);
                    near = Math.min(near, c.distance(local) - 32);
                }
                minNear = Math.min(minNear, near);
                sumNear += near;
                ticks++;
            }
            System.out.printf("render " + render + " speed %.0f blocks/tick: far view nearest %.0f blocks (avg %.0f), behind %d of %d ticks, %.1f ms/tick, %d chunks %.1f MB, %d far %.1f MB%n",
                    speed, minNear, sumNear / ticks, starved, ticks, chunkNs / 1e6 / ticks, chunkMsgs, chunkBytes / 1e6, farMsgs, farBytes / 1e6);
            org.junit.jupiter.api.Assertions.assertTrue(minNear > render - 8, "far view " + minNear + " blocks off at " + render);
            org.junit.jupiter.api.Assertions.assertTrue(starved < ticks / 2, starved + " of " + ticks + " ticks behind");
        }
    }
}
