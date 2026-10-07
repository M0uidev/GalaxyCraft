package dev.moui.galaxycraft.voxel;

import dev.moui.galaxycraft.voxel.gen.BiomeSurface;
import dev.moui.galaxycraft.voxel.gen.SurfaceSampler;
import java.util.HashMap;
import java.util.Map;
import java.util.function.ToIntFunction;
import org.joml.Vector3d;

/**
 * What a far view ({@link PlanetLod}) is made from: a planet's grid and, for a patch of its
 * columns, how high the ground is and of what block. A built planet's cells, or the generator of a
 * planet never built (its ground sampled where each patch is, nothing else made).
 */
public interface LodSource {
    /** A patch's ground: its top (layers from the grid's bottom), its block, and a cell of it (tints). */
    record Patch(int height, int block, int cell) {}

    CellGrid grid();

    Blocks blocks();

    Patch patch(int face, int i0, int i1, int j0, int j1);

    /** The color a tint of the patch's block takes there, 0xRRGGBB. */
    int tint(Patch p, int tint);

    /** A built planet: the mean of its columns' tops and the block most of them have (air: no ground at all). */
    static LodSource of(VoxelPlanet p) {
        return of(p, 1);
    }

    /** As {@link #of(VoxelPlanet)}, looking at every stride-th column each way only (faster on big planets). */
    static LodSource of(VoxelPlanet p, int stride) {
        return new LodSource() {
            @Override public CellGrid grid() {
                return p.grid;
            }

            @Override public Blocks blocks() {
                return p.blocks;
            }

            @Override public Patch patch(int face, int i0, int i1, int j0, int j1) {
                CellGrid g = p.grid;
                Map<Integer, Integer> count = new HashMap<>();
                long sum = 0;
                int cols = 0, best = Blocks.AIR, bestCount = 0;
                for (int i = i0; i < i1; i += stride)
                    for (int j = j0; j < j1; j += stride) {
                        // The top of the highest opaque block or fluid; leaves, plants and air above
                        // them do not count: trees blur into lumps from afar.
                        int top = 0, block = Blocks.AIR;
                        for (int k = g.layers - 1; k >= 0; k--) {
                            int id = p.get(g.index(face, i, j, k));
                            BlockInfo b = p.blocks.info(id);
                            if ((b.occludes() || b.isFluid()) && !p.blocks.leaves(id)) {
                                top = k + 1;
                                block = id;
                                break;
                            }
                        }
                        // Columns of empty space (around a station's slab) neither pick the block
                        // nor lower the height: the patch is the ground it has, or nothing.
                        if (block == Blocks.AIR) continue;
                        sum += top;
                        cols++;
                        int c = count.merge(block, 1, Integer::sum);
                        if (c > bestCount) {
                            bestCount = c;
                            best = block;
                        }
                    }
                return new Patch(cols == 0 ? 0 : (int) Math.round((double) sum / cols), best, g.index(face, (i0 + i1) / 2, (j0 + j1) / 2, 0));
            }

            @Override public int tint(Patch t, int tint) {
                return p.tint(t.cell(), tint);
            }
        };
    }

    /**
     * A generated planet never built: each patch is the ground at its middle column, its biome's
     * top block (water where that lies under the sea). ids gives a block's id for its text.
     */
    static LodSource sampled(SurfaceSampler s, Blocks blocks, ToIntFunction<String> ids) {
        CubeSphere g = s.grid();
        Map<Integer, String> biomeAt = new HashMap<>();
        return new LodSource() {
            @Override public CellGrid grid() {
                return g;
            }

            @Override public Blocks blocks() {
                return blocks;
            }

            @Override public Patch patch(int face, int i0, int i1, int j0, int j1) {
                int i = (i0 + i1) / 2, j = (j0 + j1) / 2;
                Vector3d dir = SurfaceSampler.columnDir(g, face, Math.min(i, g.n - 1), Math.min(j, g.n - 1));
                SurfaceSampler.Column c = s.at(dir);
                int cell = g.index(face, Math.min(i, g.n - 1), Math.min(j, g.n - 1), 0);
                biomeAt.put(cell, c.biome());
                boolean wet = s.water() && c.height() < 0;
                int height = wet ? s.depth() : s.depth() + c.height();
                int block = ids.applyAsInt(wet ? BiomeSurface.WATER : BiomeSurface.of(c.biome()).top());
                return new Patch(height, block, cell);
            }

            @Override public int tint(Patch t, int tint) {
                int kind = PlanetBiomes.kind(tint);
                if (kind == PlanetBiomes.FIXED || kind >= PlanetBiomes.KINDS) return tint & 0xFFFFFF;
                int col = t.cell() / g.layers, face = col / (g.n * g.n), i = col / g.n % g.n, j = col % g.n;
                int c = blocks.biomeColor(biomeAt.getOrDefault(t.cell(), PlanetBiomes.PLAINS), kind, face * 1024 + j, i);
                return c < 0 ? tint & 0xFFFFFF : c & 0xFFFFFF;
            }
        };
    }

    /** A ball of radius blocks, its ground all of one block: a planet known only by its size. */
    static LodSource flat(int radius, Blocks blocks, int block) {
        int depth = VoxelPlanet.groundDepth(radius);
        CubeSphere g = new CubeSphere(VoxelPlanet.gridSize(radius), radius - depth, depth + VoxelPlanet.defaultAir(radius));
        return new LodSource() {
            @Override public CellGrid grid() {
                return g;
            }

            @Override public Blocks blocks() {
                return blocks;
            }

            @Override public Patch patch(int face, int i0, int i1, int j0, int j1) {
                return new Patch(depth, block, g.index(face, Math.min((i0 + i1) / 2, g.n - 1), Math.min((j0 + j1) / 2, g.n - 1), 0));
            }

            @Override public int tint(Patch p, int tint) {
                int c = blocks.biomeColor(PlanetBiomes.PLAINS, PlanetBiomes.kind(tint), 0, 0);
                return PlanetBiomes.kind(tint) == PlanetBiomes.FIXED || c < 0 ? tint & 0xFFFFFF : c & 0xFFFFFF;
            }
        };
    }
}
