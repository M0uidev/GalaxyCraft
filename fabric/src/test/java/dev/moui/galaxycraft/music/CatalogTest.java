package dev.moui.galaxycraft.music;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CatalogTest {
    @TempDir Path dir;

    @Test void roundTrips() {
        List<Track> in = List.of(
                new Track("galaxy02", "Yoshi Star Galaxy", "SMG2_galaxy02_strm.ast", Source.SMG2, Mood.PLANET, List.of(), true),
                new Track("galaxy23", "Slipsand Galaxy", "SMG2_galaxy23_strm.ast", Source.SMG2, null, List.of("desert", "hot"), false));
        assertEquals(in, Catalog.parse(Catalog.format(in)));
    }

    @Test void skipsCommentsBlankAndBrokenLines() {
        String text = "# header\n\nok\tOK\tf.ast\tsmg2\tspace\t\ttrue\nshort\tline\nbad\tB\tf.ast\tnope\tspace\t\ttrue\n";
        List<Track> t = Catalog.parse(text);
        assertEquals(1, t.size());
        assertEquals(Mood.SPACE, t.get(0).mood());
        assertEquals(List.of(), t.get(0).tags());
    }

    @Test void unknownMoodMeansNone() {
        assertNull(Catalog.parse("a\tA\tf.ast\tsmg2\tweird\t\ttrue\n").get(0).mood());
    }

    @Test void aMissingFileIsAnEmptyCatalog() throws IOException {
        assertEquals(List.of(), Catalog.read(dir.resolve("nope.tsv")));
    }

    @Test void writesAndReadsAFile() throws IOException {
        Path f = dir.resolve("sub/tracks.tsv");
        List<Track> in = List.of(new Track("a", "A\tB", "a.ast", Source.MINECRAFT, Mood.PLANET, List.of("x"), true));
        Catalog.write(f, in);
        List<Track> out = Catalog.read(f);
        assertEquals("A B", out.get(0).title(), "tabs in a title become spaces");
        assertEquals(Source.MINECRAFT, out.get(0).source());
    }
}
