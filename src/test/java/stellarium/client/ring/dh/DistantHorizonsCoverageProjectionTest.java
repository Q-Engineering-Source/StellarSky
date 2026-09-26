package stellarium.client.ring.dh;

import static org.junit.Assert.*;
import com.seibel.distanthorizons.core.pos.DhSectionPos;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.Test;
import stellarium.world.ring.terrain.TerrainColumnState;
import stellarium.world.ring.terrain.TerrainTileKey;

public class DistantHorizonsCoverageProjectionTest {
    @Test public void onlyLiveSubmittedColumnsOfCorrectWorldAndFrameProject() {
        assertLiveProjection(0);
    }
    @Test public void completedCoarseFrameProjectsAtItsActualFootprintScale() {
        assertLiveProjection(6);
    }
    private void assertLiveProjection(int level) {
        Object world = new Object(), frame = new Object(), view = new Object(), vbo = new Object();
        var columns = new ArrayList<>(Collections.nCopies(4096,TerrainColumnState.UNKNOWN));
        columns.set(2*64+3,TerrainColumnState.REAL_SOLID);
        columns.set(3*64+2,TerrainColumnState.REAL_AIR);
        long pos = DhSectionPos.encode((byte)(6+level),-1,2);
        var build = new DistantHorizonsCoverageTransfer.Lease().begin(world,pos,new CompletableFuture<>(),
                new DistantHorizonsCoverageTransfer.Generation());
        build.capture(new DistantHorizonsColumnCoverage(pos,0,256,false,columns),true);
        var attachment = new DistantHorizonsCoverageTransfer.Attachment(); attachment.attach(build);
        var ledger = new DistantHorizonsFrameCoverage(world,frame,view,8);
        var selected = ledger.select(attachment,List.of(vbo),List.of());
        var snapshot = new DistantHorizonsFrameCoverage.Snapshot(frame,view,ledger);
        var target = new TerrainTileKey(1,level,-1,2);
        var projection = new DistantHorizonsCoverageProjection(1,world);
        assertEquals(0,projection.project(target,snapshot,frame).coveredColumns());
        assertTrue(DistantHorizonsCoverageProjection.currentPatches(1,snapshot,frame).isEmpty());
        ledger.submitted(selected,true,vbo); ledger.finishPass(true); ledger.finishPass(false); ledger.composited(frame,view);
        var result = projection.project(target,snapshot,frame);
        assertEquals(2,result.coveredColumns()); assertTrue(result.covers(2,3)); assertTrue(result.covers(3,2));
        assertFalse(result.covers(2,2));
        var patches=DistantHorizonsCoverageProjection.currentPatches(1,snapshot,frame);
        assertEquals(1,patches.size());
        assertEquals(2,patches.getFirst().columns().cardinality());
        assertTrue(DistantHorizonsCoverageProjection.currentPatches(1,snapshot,new Object()).isEmpty());
        assertEquals(0,projection.project(target,snapshot,new Object()).coveredColumns());
        assertEquals(0,new DistantHorizonsCoverageProjection(1,new Object()).project(target,snapshot,frame).coveredColumns());
        assertThrows(IllegalArgumentException.class,()->projection.project(new TerrainTileKey(2,0,-1,2),snapshot,frame));
        attachment.close();
        assertTrue(DistantHorizonsCoverageProjection.currentPatches(1,snapshot,frame).isEmpty());
        assertEquals(0,projection.project(target,snapshot,frame).coveredColumns());
    }
}
