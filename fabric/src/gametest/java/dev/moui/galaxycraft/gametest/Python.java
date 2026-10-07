package dev.moui.galaxycraft.gametest;

/** The Python the game tests run the repo's tools with: -Dgalaxycraft.python, else python3 (python on Windows). */
final class Python {
    private Python() {}

    static String exe() {
        String prop = System.getProperty("galaxycraft.python", "");
        if (!prop.isEmpty()) return prop;
        return System.getProperty("os.name", "").startsWith("Windows") ? "python" : "python3";
    }
}
