package dev.moui.galaxycraft.voxel;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import org.joml.Vector3d;
import org.junit.jupiter.api.io.TempDir;
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

    /** What a guest is sent is the save's own bytes: written to its folder they read back as the same planet. */
    @Test void encodedBytesAreTheSaveAndReadBack(@TempDir Path dir) throws Exception {
        Blocks blocks = CubeBlocks.INSTANCE;
        VoxelPlanet made = VoxelPlanet.ofRadius(12, blocks);
        made.set(made.grid.cellAt(new Vector3d(0, 13.5, 0)), blocks.id(Material.OBSIDIAN)); // an edit the copy must carry
        PlanetStore.Saved saved = PlanetStore.Saved.of(made, new Vector3d(1, 2, 3));
        byte[] bytes = PlanetStore.encode(saved, blocks);
        assertEquals(dev.moui.galaxycraft.net.PlanetXfer.etag(bytes), dev.moui.galaxycraft.net.PlanetXfer.etag(PlanetStore.encode(saved, blocks)),
                "the same planet always has the same etag, so a guest's copy can be told to be current");
        PlanetStore guest = new PlanetStore(dir);
        Files.write(guest.file("stage"), bytes);
        PlanetStore.Saved back = guest.read("stage", blocks).orElseThrow();
        assertEquals(saved.n(), back.n());
        assertEquals(new Vector3d(1, 2, 3), back.center());
        assertArrayEquals(made.cells(), back.cells());
        PlanetStore host = new PlanetStore(dir.resolve("host"));
        host.write("stage", saved, blocks);
        assertArrayEquals(bytes, Files.readAllBytes(host.file("stage")), "what the host saves and what it sends are the same bytes");
    }

    @Test void bytesDecodeToTheSamePlanet() throws Exception {
        Blocks blocks = CubeBlocks.INSTANCE;
        VoxelPlanet made = VoxelPlanet.ofRadius(12, blocks);
        made.set(made.grid.cellAt(new Vector3d(0, 13.5, 0)), blocks.id(Material.OBSIDIAN));
        byte[] bytes = PlanetStore.encode(PlanetStore.Saved.of(made, new Vector3d(4, 5, 6)), blocks);
        PlanetStore.Saved back = PlanetStore.decode(bytes, blocks, blocks::parse);
        assertArrayEquals(made.cells(), back.cells());
        assertEquals(new Vector3d(4, 5, 6), back.center());
        assertThrows(java.io.IOException.class, () -> PlanetStore.decode(new byte[] {1, 2, 3}, blocks, blocks::parse));
    }
}
