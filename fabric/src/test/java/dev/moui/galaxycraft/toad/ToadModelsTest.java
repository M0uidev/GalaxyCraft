package dev.moui.galaxycraft.toad;

import static org.junit.jupiter.api.Assertions.*;

import dev.moui.galaxycraft.ship.model.BdlReader;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ToadModelsTest {
    @AfterEach void reset() {
        ToadModels.use(null, Map.of());
    }

    /** Two joints: a root and a child 100 units up; a triangle in each (the child's is the one at the top). */
    static BdlReader.Rig rig() {
        var tex = new BdlReader.Texture(4, 4, new int[16]);
        var uv = new float[][] {{0, 0}, {1, 0}, {0, 1}};
        var base = new BdlReader.Tri(new float[][] {{-10, 0, 0}, {10, 0, 0}, {0, 20, 0}}, uv);
        var top = new BdlReader.Tri(new float[][] {{-10, 0, 0}, {10, 0, 0}, {0, 80, 0}}, uv);
        return new BdlReader.Rig(new int[] {-1, 0},
                new float[][] {{1, 1, 1, 0, 0, 0, 0, 0, 0}, {1, 1, 1, 0, 0, 0, 0, 100, 0}},
                List.of(new BdlReader.Part(0, 0, List.of(base)), new BdlReader.Part(1, 0, List.of(top))), List.of(tex));
    }

    @Test void aMissingModelIsABoxOfTheToadsHeight() {
        ToadModels.load(Path.of("no-such-dir"));
        var l = ToadModels.get();
        assertEquals(1, l.parts().size());
        assertEquals(ToadModels.HEIGHT_PX, l.fit() * 1f, 0.01f, "a box 1 unit tall is scaled to the height");
    }

    @Test void aRigIsScaledSoTheRestPoseIsTheToadsHeightWithFeetAtTheOrigin() {
        ToadModels.use(rig(), Map.of());
        var l = ToadModels.get();
        // The rest pose spans y 0..180 (the child's triangle: 100 + 80).
        assertEquals(ToadModels.HEIGHT_PX / 180f, l.fit(), 1e-5);
        assertEquals(0f, l.origin()[1], 1e-5, "feet");
        // A piece's rest matrix puts the child's top corner (0, 80) 24 pixels up.
        var w = ToadPose.worlds(l.parent(), l.rest());
        var m = ToadModels.pieceMatrix(l, w[1]);
        float[] corner = l.parts().get(1).part().quads().get(0).pos()[2];
        Vector3f p = m.transformPosition(new Vector3f(corner[0], corner[1], corner[2]));
        assertEquals(ToadModels.HEIGHT_PX, p.y, 1e-3);
    }

    @Test void aPosedJointMovesItsPieceAndNothingElse() {
        ToadModels.use(rig(), Map.of());
        var l = ToadModels.get();
        float[][] posed = {l.rest()[0], l.rest()[1].clone()};
        posed[1][6] = 30; // the child slides 30 units in x
        var w = ToadPose.worlds(l.parent(), posed);
        float[] c0 = l.parts().get(0).part().quads().get(0).pos()[0];
        Vector3f base = ToadModels.pieceMatrix(l, w[0]).transformPosition(new Vector3f(c0[0], c0[1], c0[2]));
        var rest = ToadModels.pieceMatrix(l, ToadPose.worlds(l.parent(), l.rest())[0]).transformPosition(new Vector3f(c0[0], c0[1], c0[2]));
        assertEquals(rest.x, base.x, 1e-5);
        float[] c1 = l.parts().get(1).part().quads().get(0).pos()[0];
        Vector3f top = ToadModels.pieceMatrix(l, w[1]).transformPosition(new Vector3f(c1[0], c1[1], c1[2]));
        var topRest = ToadModels.pieceMatrix(l, ToadPose.worlds(l.parent(), l.rest())[1]).transformPosition(new Vector3f(c1[0], c1[1], c1[2]));
        assertEquals(30f * l.fit(), top.x - topRest.x, 1e-3);
    }

    @Test void anEmptyRigFallsBackToTheBox() {
        ToadModels.use(new BdlReader.Rig(new int[] {-1}, new float[][] {{1, 1, 1, 0, 0, 0, 0, 0, 0}}, List.of(), List.of()), Map.of());
        assertEquals(1, ToadModels.get().parts().size());
        assertEquals(BdlReaderBox.JOINT, ToadModels.get().parts().get(0).joint());
    }

    /** The box hangs on joint 0. */
    private static final class BdlReaderBox {
        static final int JOINT = 0;
    }
}
