package dev.moui.galaxycraft.net;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class GuestFolderTest {
    @Test
    void usesTheWorldIdUnderGuest() {
        String id = "123e4567-e89b-12d3-a456-426614174000";
        assertEquals(Path.of("/d/guest", id), GuestFolder.of(Path.of("/d"), id));
    }

    @Test
    void rejectsAnythingThatIsNotAUuid() {
        for (String bad : new String[] {"../x", "a/b", "", "..", "123e4567", "123e4567-e89b-12d3-a456-426614174000/..",
                "123E4567-E89B-12D3-A456-426614174000 "})
            assertThrows(IllegalArgumentException.class, () -> GuestFolder.of(Path.of("/d"), bad), bad);
    }
}
