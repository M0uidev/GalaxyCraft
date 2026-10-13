package dev.moui.galaxycraft.launchstar;

/**
 * A placed Launch Star: where it stands (universe units), which way is up there, its stage, the
 * body (planet or station key) it stands on and the star it is linked to, if any.
 */
public record StarRecord(String id, double[] pos, double[] up, String stage, String body, String partner) {
    public StarRecord withPartner(String p) {
        return new StarRecord(id, pos, up, stage, body, p);
    }
}
