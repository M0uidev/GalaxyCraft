package dev.moui.galaxycraft.toad;

/**
 * What a Toad knows when you talk to it. The caller fills it (tier and star through the 3D map's
 * SystemInfo; the Warp Core and missing module from the ship). {@code missingModule} may be null.
 */
public record ToadContext(int systemTier, String star, int warpCoreTier, String missingModule, boolean visited) {}
