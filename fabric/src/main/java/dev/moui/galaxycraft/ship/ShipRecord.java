package dev.moui.galaxycraft.ship;

/**
 * A parked ship: its modules, where it is (universe units), how it is turned (a quaternion x, y,
 * z, w: model +x right, +y up, +z the nose) and the stage it was put down in.
 */
public record ShipRecord(String id, ShipModules modules, double[] pos, double[] rot, String stage) {}
