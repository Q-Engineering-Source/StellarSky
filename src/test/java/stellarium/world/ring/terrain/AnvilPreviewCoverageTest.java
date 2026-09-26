package stellarium.world.ring.terrain;

import static org.junit.Assert.*;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static stellarium.world.ring.terrain.SeedPreviewAdmission.Coverage.*;

public class AnvilPreviewCoverageTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    @Test public void missingRegionIsReadOnlyEvenForLargeSparseAreas() throws Exception {
        var root=temporary.getRoot().toPath().resolve("region"); var coverage=new AnvilPreviewCoverage(root,1);
        assertEquals(NO_REAL_DATA,coverage.query(new TerrainTileKey(1,0,0,0)));
        assertFalse(Files.exists(root));
        assertEquals(NO_REAL_DATA,coverage.query(new TerrainTileKey(1,24,0,0)));
        assertFalse(Files.exists(root));
    }
    @Test public void sparseFarQueryPreservesRealDataAndHeaderBudget() throws Exception {
        var root=temporary.getRoot().toPath();var coverage=new AnvilPreviewCoverage(root,1);
        Files.write(root.resolve("r.0.0.mca"),new byte[8192]);
        assertEquals(NO_REAL_DATA,coverage.query(new TerrainTileKey(1,8,0,0)));
        byte[] real=new byte[12288];ByteBuffer.wrap(real).putInt((2<<8)|1);
        Files.write(root.resolve("r.0.0.mca"),real);
        assertEquals(REAL_DATA,coverage.query(new TerrainTileKey(1,8,0,0)));
        Files.write(root.resolve("r.0.0.mca"),new byte[8192]);
        Files.write(root.resolve("r.1.0.mca"),new byte[8192]);
        assertEquals(UNKNOWN,coverage.query(new TerrainTileKey(1,8,0,0)));
    }
    @Test public void negativeHalfOpenCoordinatesAndStoredAirCountAsReal() throws Exception {
        var root=temporary.getRoot().toPath(); byte[] bytes=new byte[12288];
        ByteBuffer.wrap(bytes).putInt(4*(31+31*32),(2<<8)|1);
        Files.write(root.resolve("r.-1.-1.mca"),bytes);
        var coverage=new AnvilPreviewCoverage(root,4);
        assertEquals(REAL_DATA,coverage.query(new TerrainTileKey(1,0,-1,-1)));
        assertEquals(NO_REAL_DATA,coverage.query(new TerrainTileKey(1,0,0,0)));
        assertEquals(NO_REAL_DATA,coverage.query(new TerrainTileKey(1,0,-2,-2)));
        assertArrayEquals(bytes,Files.readAllBytes(root.resolve("r.-1.-1.mca")));
    }
    @Test public void truncatedOrMalformedRegionNeverBecomesAbsence() throws Exception {
        var root=temporary.getRoot().toPath(); var file=root.resolve("r.0.0.mca");
        var coverage=new AnvilPreviewCoverage(root,1);
        Files.write(file,new byte[100]);
        assertThrows(IOException.class,()->coverage.query(new TerrainTileKey(1,0,0,0)));
        byte[] bytes=new byte[8192]; ByteBuffer.wrap(bytes).putInt((2<<8)|1); Files.write(file,bytes);
        assertThrows(IOException.class,()->coverage.query(new TerrainTileKey(1,0,0,0)));
    }
}
