package dev.moui.galaxycraft.voxel;

import java.util.function.BooleanSupplier;
import org.joml.Vector3d;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Flying over a planet at elytra speeds with PlanetClient's budget (6 ms a tick): the far view stays at the block distance. */
class FlightLoadTest {
    @Tag("timing") @Test void flyingFastTheBlocksKeepUp() {
        for (double render : new double[] {64, 96}) for (double speed : new double[] {3}) {
            PlanetSession s = new PlanetSession(80);
            s.setRenderDistance(render);
            s.spawn(128, new Vector3d(), new Vector3d(0, 1, 0));
            VoxelPlanet p = s.planet();
            double alt = 129.5; // just over the ground: collision all along
            Vector3d dir = new Vector3d(1, 0, 0), up = new Vector3d(0, -1, 0);
            // Settle above the start, everything sent.
            Vector3d at = new Vector3d(s.center()).add(new Vector3d(up).mul(alt * 80));
            s.update(3, 100, at);
            while (s.peek() != null) s.sent();
            double minNear = 1e9, sumNear = 0;
            int ticks = 0, starved = 0, maxParts = 0;
            long chunkNs = 0; int farMsgs = 0, chunkMsgs = 0; long farBytes = 0, chunkBytes = 0;
            for (double ang = 0; ang < Math.PI / 2; ang += speed / alt) {
                Vector3d local = new Vector3d(up).mul(Math.cos(ang)).add(new Vector3d(dir).mul(Math.sin(ang))).mul(alt);
                s.update(3, 100, new Vector3d(local).mul(80).add(s.center()));
                long end = System.nanoTime() + 6_000_000;
                BooleanSupplier bulk = () -> System.nanoTime() - end < 0;
                long t0 = System.nanoTime();
                for (PlanetSession.Msg m; (m = s.peek(bulk)) != null; ) {
                        boolean isFar = m.type() == dev.moui.galaxycraft.proto.Layout.MSG_CHUNK
                            && (java.nio.ByteBuffer.wrap(m.payload()).order(java.nio.ByteOrder.LITTLE_ENDIAN).getInt(0) & PlanetSession.FAR_VIEW) != 0;
                    if (isFar) { farMsgs++; farBytes += m.payload().length; } else { chunkMsgs++; chunkBytes += m.payload().length; }
                    s.sent();
                }
                chunkNs += System.nanoTime() - t0;
                if (s.queued() > 0) starved++;
                maxParts = Math.max(maxParts, s.collisionChunks());
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
            // The game's collision zones hold 512 parts: one more overwrites a zone's count and SMG2
            // then searches all of memory on every removal (the game hangs).
            org.junit.jupiter.api.Assertions.assertTrue(maxParts <= PlanetSession.MAX_PARTS, maxParts + " collision parts in the game");
            org.junit.jupiter.api.Assertions.assertTrue(starved < ticks / 2, starved + " of " + ticks + " ticks behind");
        }
    }

    @Tag("timing") @Test void aSlowMachineStillLoadsWhatIsNearestFirst() {
        // A quarter of the budget (a slower computer, a busier tick): what loads first is what
        // Mario is about to reach, so the far view stays as far off as it can.
        PlanetSession s = new PlanetSession(80);
        s.setRenderDistance(96);
        s.spawn(128, new Vector3d(), new Vector3d(0, 1, 0));
        VoxelPlanet p = s.planet();
        double alt = 136;
        Vector3d dir = new Vector3d(1, 0, 0), up = new Vector3d(0, -1, 0);
        s.update(3, 100, new Vector3d(up).mul(alt * 80).add(s.center()));
        while (s.peek() != null) s.sent();
        double sumAhead = 0;
        int ticks = 0;
        for (double ang = 0; ang < Math.PI / 2; ang += 3 / alt) {
            Vector3d local = new Vector3d(up).mul(Math.cos(ang)).add(new Vector3d(dir).mul(Math.sin(ang))).mul(alt);
            s.update(3, 100, new Vector3d(local).mul(80).add(s.center()));
            long end = System.nanoTime() + 1_500_000;
            for (PlanetSession.Msg m; (m = s.peek(() -> System.nanoTime() - end < 0)) != null; ) s.sent();
            // The nearest far view drawn up close ahead of Mario (where he is headed).
            Vector3d heading = new Vector3d(up).mul(-Math.sin(ang)).add(new Vector3d(dir).mul(Math.cos(ang)));
            double near = 1e9;
            for (int t = 0; t < PlanetLod.tileCount(p); t++) {
                if (!s.farDrawnNear(t)) continue;
                int[] cs = PlanetLod.chunksOfTile(p, t);
                Vector3d c = new Vector3d();
                p.sphere(cs[cs.length / 2], c, new double[1]);
                c.normalize(128);
                if (new Vector3d(c).sub(local).dot(heading) < 0) continue;
                near = Math.min(near, c.distance(local) - 32);
            }
            sumAhead += Math.min(near, 200);
            ticks++;
        }
        System.out.printf("slow machine, render 96, speed 3: far view ahead %.0f blocks off on average%n", sumAhead / ticks);
        org.junit.jupiter.api.Assertions.assertTrue(sumAhead / ticks > 80, "far view ahead " + sumAhead / ticks + " blocks off (64 when chunks went first come, first served)");
    }

    @Test void withTheGameBehindCollisionNeverOutgrowsItsZone() {
        // The host has a backlog the whole way: only the urgent lane goes (Mario's collision).
        PlanetSession s = new PlanetSession(80);
        s.spawn(128, new Vector3d(), new Vector3d(0, 1, 0));
        double alt = 129.5;
        Vector3d dir = new Vector3d(1, 0, 0), up = new Vector3d(0, -1, 0);
        s.update(3, 100, new Vector3d(up).mul(alt * 80).add(s.center()));
        while (s.peek() != null) s.sent();
        int maxParts = 0;
        for (double ang = 0; ang < Math.PI / 2; ang += 3 / alt) {
            Vector3d local = new Vector3d(up).mul(Math.cos(ang)).add(new Vector3d(dir).mul(Math.sin(ang))).mul(alt);
            s.update(3, 100, new Vector3d(local).mul(80).add(s.center()));
            for (PlanetSession.Msg m; (m = s.peek(() -> false)) != null; ) s.sent();
            maxParts = Math.max(maxParts, s.collisionChunks());
        }
        org.junit.jupiter.api.Assertions.assertTrue(maxParts <= PlanetSession.MAX_PARTS, maxParts + " collision parts in the game");
    }

    @Test void standingStillNothingUnderMarioIsSentAgain() {
        // Away from the keyboard: the chunks with collision under Mario stay as the game has them
        // (SMG2 may keep the triangle he stands on without looking again; a new part frees the old).
        PlanetSession s = new PlanetSession(80);
        s.setRenderDistance(96);
        s.spawn(64, new Vector3d(), new Vector3d(0, 1, 0));
        Vector3d at = new Vector3d(s.center()).add(0, -65 * 80, 0);
        for (int i = 0; i < 40; i++) {
            s.update(3, 100, at);
            while (s.peek() != null) s.sent();
        }
        int resent = 0;
        for (int i = 0; i < 600; i++) {
            s.update(3, 100, at);
            for (PlanetSession.Msg m; (m = s.peek()) != null; s.sent()) {
                java.nio.ByteBuffer b = java.nio.ByteBuffer.wrap(m.payload()).order(java.nio.ByteOrder.LITTLE_ENDIAN);
                if (m.type() == dev.moui.galaxycraft.proto.Layout.MSG_CHUNK && (b.getInt(0) & PlanetSession.FAR_VIEW) == 0
                        && s.collides(b.getInt(0) & 0x7FFFFF)) resent++;
            }
        }
        org.junit.jupiter.api.Assertions.assertEquals(0, resent, "chunks with collision sent again while Mario stood still");
    }

    @Test void flyingLowNoTickStalls() {
        // Just over the ground at elytra speed, with PlanetClient's budget: the worst tick, since
        // Mario's collision (the urgent lane) is built whatever the budget.
        PlanetSession s = new PlanetSession(80);
        s.setRenderDistance(96);
        s.spawn(128, new Vector3d(), new Vector3d(0, 1, 0));
        double alt = 130;
        Vector3d dir = new Vector3d(1, 0, 0), up = new Vector3d(0, -1, 0);
        s.update(3, 100, new Vector3d(up).mul(alt * 80).add(s.center()));
        while (s.peek() != null) s.sent();
        long worst = 0, total = 0;
        int ticks = 0;
        for (int lap = 0; lap < 2; lap++) // the first lap warms the JIT
            for (double ang = 0; ang < Math.PI; ang += 2.5 / alt) {
                Vector3d local = new Vector3d(up).mul(Math.cos(ang)).add(new Vector3d(dir).mul(Math.sin(ang))).mul(alt);
                long t0 = System.nanoTime();
                s.update(3, 100, new Vector3d(local).mul(80).add(s.center()));
                long end = t0 + 6_000_000;
                for (PlanetSession.Msg m; (m = s.peek(() -> System.nanoTime() - end < 0)) != null; ) s.sent();
                long dt = System.nanoTime() - t0;
                if (lap == 1) {
                    worst = Math.max(worst, dt);
                    total += dt;
                    ticks++;
                }
            }
        System.out.printf("flying low: worst tick %.1f ms, average %.1f ms%n", worst / 1e6, total / 1e6 / ticks);
        // 88 ms once (a hitch of 5 frames) when a tile came in and every chunk of it was asked
        // whether it may show, walking all its cells.
        org.junit.jupiter.api.Assertions.assertTrue(worst < 30_000_000, "worst tick " + worst / 1e6 + " ms");
    }
}
