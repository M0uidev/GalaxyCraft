package dev.moui.galaxycraft.toad;

/**
 * A placed Toad: its type, where its feet are (universe units), how it is turned (a quaternion x, y,
 * z, w: model +y up, +z the way it faces; up is the planet's there), its stage, and the spot it
 * keeps to when it wanders (universe units; null: where it was put, until it first walks).
 */
public record ToadRecord(String id, ToadType type, double[] pos, double[] rot, String stage, double[] home) {
    public ToadRecord(String id, ToadType type, double[] pos, double[] rot, String stage) {
        this(id, type, pos, rot, stage, null);
    }
}
