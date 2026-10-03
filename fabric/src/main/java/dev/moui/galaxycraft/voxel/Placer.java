package dev.moui.galaxycraft.voxel;

import java.util.List;
import org.joml.Vector3d;

/** How a held block goes onto a planet (client/McBlocks follows Minecraft's placement rules). */
public interface Placer {
    /**
     * The cells to set, each {cell, block id}, to place into cell (empty or replaceable): face is
     * the side of the block clicked it came through (pointing from that block to cell), hit where
     * the click was in cell's model space, look the player's look in those axes. Empty to refuse.
     */
    List<int[]> place(VoxelPlanet p, int cell, int face, Vector3d hit, Vector3d look);

    /** Always the same block. */
    static Placer of(int id) {
        return (p, cell, face, hit, look) -> List.of(new int[] {cell, id});
    }
}
