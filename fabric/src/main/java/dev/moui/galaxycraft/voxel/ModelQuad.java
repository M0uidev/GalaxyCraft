package dev.moui.galaxycraft.voxel;

/**
 * One face of a block's model, as Minecraft bakes it: four corners in block space (x, y, z from 0
 * to 1, y up; counter-clockwise seen from the front), each with a texture coordinate within its
 * sprite (u, v from 0 to 1, v down), the atlas tile of that sprite, a tint (0xRRGGBB, white for
 * none) and the side of the cell that hides it when covered (a {@link CubeSphere} side), or -1.
 */
public record ModelQuad(float[] pos, float[] uv, int tile, int tint, int cull) {
    public ModelQuad {
        if (pos.length != 12 || uv.length != 8) throw new IllegalArgumentException("a quad has 4 corners");
    }
}
