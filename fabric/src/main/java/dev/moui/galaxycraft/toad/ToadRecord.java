package dev.moui.galaxycraft.toad;

/** A placed Toad: its type, where it is (universe units), the way it faces (radians about up) and its stage. */
public record ToadRecord(String id, ToadType type, double[] pos, double yaw, String stage) {}
