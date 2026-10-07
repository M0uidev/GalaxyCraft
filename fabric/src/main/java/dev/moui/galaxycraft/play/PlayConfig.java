package dev.moui.galaxycraft.play;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * play.json, written by the Super Minecraft Galaxy launcher: how to start Dolphin when Minecraft
 * is started by Mojang's launcher. Same shape as launcher/src/core/mclauncher.js playJson().
 */
public record PlayConfig(String cmd, List<String> args, String cwd, Map<String, String> env) {
    public static final int FORMAT = 1;

    /** The launcher's data folder: $GXC_DATA_DIR, else the one launcher/src/core/paths.js picks. */
    public static Path dataDir(Map<String, String> env, Map<String, String> props, boolean windows) {
        String own = env.get("GXC_DATA_DIR");
        if (own != null && !own.isEmpty()) return Path.of(own);
        String home = props.getOrDefault("user.home", "");
        if (windows) {
            String appData = env.get("APPDATA");
            return (appData != null && !appData.isEmpty() ? Path.of(appData) : Path.of(home, "AppData", "Roaming")).resolve("galaxycraft");
        }
        String xdg = env.get("XDG_DATA_HOME");
        return (xdg != null && !xdg.isEmpty() ? Path.of(xdg) : Path.of(home, ".local", "share")).resolve("galaxycraft");
    }

    /** The parsed file, or empty if it is missing, broken or from a newer launcher. */
    public static Optional<PlayConfig> read(Path file) {
        try {
            return parse(Files.readString(file));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    public static Optional<PlayConfig> parse(String json) {
        try {
            JsonObject o = JsonParser.parseString(json).getAsJsonObject();
            if (o.get("format") == null || o.get("format").getAsInt() != FORMAT) return Optional.empty();
            String cmd = o.get("cmd").getAsString();
            if (cmd.isEmpty()) return Optional.empty();
            List<String> args = new ArrayList<>();
            if (o.has("args")) for (JsonElement a : o.getAsJsonArray("args")) args.add(a.getAsString());
            Map<String, String> env = new LinkedHashMap<>();
            if (o.has("env")) for (var e : o.getAsJsonObject("env").entrySet()) env.put(e.getKey(), e.getValue().getAsString());
            String cwd = o.has("cwd") ? o.get("cwd").getAsString() : null;
            return Optional.of(new PlayConfig(cmd, args, cwd, env));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /** The command line, for the log. */
    public List<String> command() {
        List<String> c = new ArrayList<>();
        c.add(cmd);
        c.addAll(args);
        return c;
    }
}
