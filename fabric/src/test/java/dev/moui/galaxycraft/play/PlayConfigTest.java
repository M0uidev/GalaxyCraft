package dev.moui.galaxycraft.play;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PlayConfigTest {
    @TempDir Path dir;

    @Test
    void readsTheLaunchersFile() throws Exception {
        Path f = dir.resolve("play.json");
        Files.writeString(f, "{\"format\":1,\"cmd\":\"/d/dolphin-emu\",\"args\":[\"-u\",\"/x\"],\"cwd\":\"/d\",\"env\":{\"GALAXYCRAFT\":\"1\"}}");
        PlayConfig c = PlayConfig.read(f).orElseThrow();
        assertEquals("/d/dolphin-emu", c.cmd());
        assertEquals(List.of("-u", "/x"), c.args());
        assertEquals("/d", c.cwd());
        assertEquals(Map.of("GALAXYCRAFT", "1"), c.env());
        assertEquals(List.of("/d/dolphin-emu", "-u", "/x"), c.command());
    }

    @Test
    void missingBrokenOrNewerIsEmpty() throws Exception {
        assertTrue(PlayConfig.read(dir.resolve("none.json")).isEmpty());
        assertTrue(PlayConfig.parse("not json").isEmpty());
        assertTrue(PlayConfig.parse("{\"format\":2,\"cmd\":\"x\"}").isEmpty());
        assertTrue(PlayConfig.parse("{\"format\":1}").isEmpty());
        assertTrue(PlayConfig.parse("{\"format\":1,\"cmd\":\"\"}").isEmpty());
    }

    @Test
    void dataDirFollowsThePlayersSystem() {
        assertEquals(Path.of("/g"), PlayConfig.dataDir(Map.of("GXC_DATA_DIR", "/g"), Map.of("user.home", "/h"), false));
        assertEquals(Path.of("/h/.local/share/galaxycraft"), PlayConfig.dataDir(Map.of(), Map.of("user.home", "/h"), false));
        assertEquals(Path.of("/x/galaxycraft"), PlayConfig.dataDir(Map.of("XDG_DATA_HOME", "/x"), Map.of("user.home", "/h"), false));
        assertEquals(Path.of("/a/galaxycraft"), PlayConfig.dataDir(Map.of("APPDATA", "/a"), Map.of("user.home", "/h"), true));
    }
}
