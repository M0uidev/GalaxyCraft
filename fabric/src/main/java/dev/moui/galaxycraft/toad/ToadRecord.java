package dev.moui.galaxycraft.toad;

/**
 * A placed Toad: its type, where its feet are (universe units), how it is turned (a quaternion x, y,
 * z, w: model +y up, +z the way it faces; up is the planet's there) and its stage.
 */
public record ToadRecord(String id, ToadType type, double[] pos, double[] rot, String stage) {}
