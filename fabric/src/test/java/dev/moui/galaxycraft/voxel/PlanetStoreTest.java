package dev.moui.galaxycraft.voxel;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PlanetStoreTest {
    @Test void dataDirPerSystem() {
        assertEquals(Path.of("/home/me/.local/share/galaxycraft"), PlanetStore.dataDir("Linux", Map.<String, String>of()::get, "/home/me"));
        assertEquals(Path.of("/x/galaxycraft"), PlanetStore.dataDir("Linux", Map.of("XDG_DATA_HOME", "/x")::get, "/home/me"));
        assertEquals(Path.of("C:\\Users\\me\\AppData\\Roaming", "galaxycraft"),
                PlanetStore.dataDir("Windows 11", Map.of("APPDATA", "C:\\Users\\me\\AppData\\Roaming", "XDG_DATA_HOME", "/x")::get, "C:\\Users\\me"));
        assertEquals(Path.of("C:\\Users\\me", "AppData", "Roaming", "galaxycraft"),
                PlanetStore.dataDir("Windows 10", Map.<String, String>of()::get, "C:\\Users\\me"));
    }
}
