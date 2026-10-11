package dev.moui.galaxycraft.ship;

/** A parked ship: its modules, where it is (galaxy units) and which way it faces. */
public record ShipRecord(String id, ShipModules modules, double[] pos, float yaw) {}
