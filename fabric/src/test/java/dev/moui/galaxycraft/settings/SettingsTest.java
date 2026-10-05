package dev.moui.galaxycraft.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SettingsTest {
    @TempDir Path dir;

    private static Setting.Choice<Movement> movement() {
        return new Setting.Choice<>("movement", "Movement", "", Movement.class, Movement.MARIO, Movement::label);
    }

    @Test void kindsKeepTheirValuesValid() {
        Settings s = new Settings(null);
        Setting.Range r = s.add(new Setting.Range("range", "Range", "", 16, 64, 8, 64, " blocks"));
        r.set(1000);
        assertEquals(64, r.get());
        r.set(19);
        assertEquals(16, r.get()); // to the nearest step
        r.setFraction(0.5);
        assertEquals(40, r.get());
        assertEquals("40 blocks", r.display());
        Setting.Text t = s.add(new Setting.Text("skin", "Skin", "", "", 16));
        t.set("  Notch  ");
        assertEquals("Notch", t.get());
        t.set("x".repeat(40));
        assertEquals(16, t.get().length());
        Setting.Choice<Movement> m = s.add(movement());
        m.cycle();
        assertEquals(Movement.MINECRAFT, m.get());
        assertEquals("Minecraft", m.display());
        m.cycle();
        assertEquals(Movement.MARIO, m.get());
    }

    @Test void listenersHearOnlyChanges() {
        Settings s = new Settings(null);
        Setting.Toggle t = s.add(new Setting.Toggle("t", "T", "", true));
        List<Boolean> heard = new ArrayList<>();
        t.onChange(heard::add);
        t.set(true);
        t.flip();
        t.flip();
        assertEquals(List.of(false, true), heard);
    }

    @Test void changesAreWrittenAndReadBack() throws Exception {
        Path file = dir.resolve("config/galaxycraft.properties");
        Settings a = new Settings(file);
        Setting.Choice<Movement> m = a.add(movement());
        Setting.Text skin = a.add(new Setting.Text("skin", "Skin", "", "", 16));
        m.set(Movement.MINECRAFT);
        skin.set("jeb_");
        assertTrue(Files.isRegularFile(file));

        Settings b = new Settings(file);
        List<Movement> heard = new ArrayList<>();
        Setting.Choice<Movement> m2 = b.add(movement());
        m2.onChange(heard::add);
        assertEquals(Movement.MINECRAFT, m2.get());
        assertEquals("jeb_", b.add(new Setting.Text("skin", "Skin", "", "", 16)).get());
        assertTrue(heard.isEmpty()); // loading is not a change
    }

    @Test void badOrUnknownValuesFallBack() throws Exception {
        Path file = dir.resolve("galaxycraft.properties");
        Files.writeString(file, "movement=WALKING\nrange=lots\nold=kept\n");
        Settings s = new Settings(file);
        assertEquals(Movement.MARIO, s.add(movement()).get());
        Setting.Range r = s.add(new Setting.Range("range", "Range", "", 16, 64, 8, 48, ""));
        assertEquals(48, r.get());
        r.set(24);
        assertTrue(Files.readString(file).contains("old=kept")); // a newer version's setting survives
    }

    @Test void keysAreUnique() {
        Settings s = new Settings(null);
        s.add(movement());
        assertThrows(IllegalArgumentException.class, () -> s.add(movement()));
        assertFalse(s.all().isEmpty());
    }
}
