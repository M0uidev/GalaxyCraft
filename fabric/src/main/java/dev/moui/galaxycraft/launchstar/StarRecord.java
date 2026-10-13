package dev.moui.galaxycraft.launchstar;

/**
 * A placed Launch Star: where it stands (universe units), which way is up there, its stage, the
 * body (planet or station key) it stands on and its code. Stars with the same code on different
 * bodies are linked.
 */
public record StarRecord(String id, double[] pos, double[] up, String stage, String body, String code) {
    public StarRecord withCode(String c) {
        return new StarRecord(id, pos, up, stage, body, c);
    }
}
