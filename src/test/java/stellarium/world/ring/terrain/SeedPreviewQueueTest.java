package stellarium.world.ring.terrain;

import static org.junit.Assert.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CompletableFuture;
import org.junit.Test;

public class SeedPreviewQueueTest {
    @Test public void widestFarBandPreservesBothSpaceEdgesInSamplesAndMesh() {
        var queue=new SeedPreviewQueue(1,(x,z)->{
            if(z< -512 || z>=512)return new SeedTerrainChunk.Empty(x,z);
            var density=new double[825];java.util.Arrays.fill(density,1);
            return new SeedTerrainChunk.Density(new OverworldDensityLattice(x,z,63,density));
        },2,()->0L);
        for(int tileZ=-1;tileZ<=0;tileZ++) {
            var key=new TerrainTileKey(1,12,1,tileZ);
            var request=queue.request(key).orElseThrow();queue.tick(4096,100);
            var tile=request.result().toCompletableFuture().join();
            for(int x=0;x<64;x++)for(int z=0;z<64;z++) {
                long worldZ=key.minBlockZ()+z*4096L;
                assertEquals(worldZ>=-8192&&worldZ<8192,tile.columns().get(x*64+z).kind()!=SeedTerrainTile.Kind.EMPTY);
            }
            var mesh=SeedTerrainMesh.build(tile,43_683_000);
            assertEquals(16*6*16,mesh.length);
            double min=Double.POSITIVE_INFINITY,max=Double.NEGATIVE_INFINITY;
            for(int i=0;i<mesh.length;i+=16) {
                double z=(double)mesh[i+2]+mesh[i+5];min=Math.min(min,z);max=Math.max(max,z);
            }
            assertEquals(tileZ<0?-8192:0,min,0);assertEquals(tileZ<0?0:8192,max,0);
        }
    }
    @Test public void farSummaryOnlySamplesItsDisplayedCoarseGrid() {
        var calls=new AtomicInteger();
        var queue=new SeedPreviewQueue(1,(x,z)->{calls.incrementAndGet();assertEquals(0,Math.floorMod(x,64));assertEquals(0,Math.floorMod(z,64));return new SeedTerrainChunk.Empty(x,z);},1,()->0L);
        var ticket=queue.request(new TerrainTileKey(1,8,0,0)).orElseThrow();
        queue.tick(4096,100);
        assertEquals(4096,ticket.result().toCompletableFuture().join().columns().size());
        assertEquals(256,calls.get());
    }
    private static TerrainTileKey key(long epoch,long x) { return new TerrainTileKey(epoch,0,x,0); }
    @Test public void partialTilesStayPrivateAndRepeatedChunksAreReused() {
        var calls=new AtomicInteger();
        var queue=new SeedPreviewQueue(1,(x,z)->{calls.incrementAndGet();return new SeedTerrainChunk.Empty(x,z);},2,()->0L);
        var ticket=queue.request(key(1,0)).orElseThrow();
        assertSame(ticket,queue.request(key(1,0)).orElseThrow());
        assertEquals(20,queue.tick(20,100));
        assertFalse(ticket.result().toCompletableFuture().isDone());
        queue.tick(4096,100);
        var tile=ticket.result().toCompletableFuture().join();
        assertEquals(4096,tile.columns().size());
        assertTrue(tile.columns().stream().allMatch(c->c.kind()==SeedTerrainTile.Kind.EMPTY));
        assertEquals(16,calls.get());
        assertEquals(0,queue.pending());
    }
    @Test public void timeBudgetStopsBetweenNonpreemptibleSamples() {
        var clock=new AtomicLong(); var calls=new AtomicInteger();
        var queue=new SeedPreviewQueue(1,(x,z)->{calls.incrementAndGet();clock.addAndGet(3);return new SeedTerrainChunk.Empty(x,z);},2,clock::get);
        queue.request(new TerrainTileKey(1,4,0,0));
        assertEquals(2,queue.tick(100,5)); assertEquals(2,calls.get());
    }
    @Test public void capacityCancelAndEpochChangeRejectStaleWork() {
        var queue=new SeedPreviewQueue(1,SeedTerrainChunk.Empty::new,1,()->0L);
        var old=queue.request(key(1,0)).orElseThrow();
        assertTrue(queue.request(key(1,1)).isEmpty());
        queue.cancel(old);
        assertTrue(old.result().toCompletableFuture().isCompletedExceptionally());
        var other=queue.request(key(1,1)).orElseThrow();
        queue.switchWorld(2,SeedTerrainChunk.Empty::new);
        assertTrue(other.result().toCompletableFuture().isCompletedExceptionally());
        assertThrows(IllegalArgumentException.class,()->queue.request(key(1,0)));
        assertTrue(queue.request(key(2,0)).isPresent());
    }
    @Test public void sourceFailureCompletesExceptionallyAndDoesNotBlockOtherJobs() {
        var failure=new IllegalStateException("injected source failure");
        var queue=new SeedPreviewQueue(1,(x,z)->{if(x==0)throw failure;return new SeedTerrainChunk.Empty(x,z);},2,()->0L);
        var bad=queue.request(key(1,0)).orElseThrow(); var good=queue.request(key(1,1)).orElseThrow();
        queue.tick(4097,100);
        try {bad.result().toCompletableFuture().join();fail();}catch(java.util.concurrent.CompletionException e){assertSame(failure,e.getCause());}
        assertTrue(good.result().toCompletableFuture().isDone());
    }
    @Test public void mismatchedChunkAndUnrepresentableCoordinatesFailExplicitly() {
        var queue=new SeedPreviewQueue(1,(x,z)->new SeedTerrainChunk.Empty(x+1,z),1,()->0L);
        var ticket=queue.request(key(1,0)).orElseThrow(); queue.tick(1,100);
        assertTrue(ticket.result().toCompletableFuture().isCompletedExceptionally());
        assertThrows(IllegalArgumentException.class,()->queue.request(new TerrainTileKey(1,24,2,0)));
    }
    @Test public void worldChangeDuringSourceCallCannotPublishOldTile() {
        var holder=new AtomicReference<SeedPreviewQueue>();
        var queue=new SeedPreviewQueue(1,(x,z)->{
            holder.get().switchWorld(2,SeedTerrainChunk.Empty::new);
            holder.get().request(key(2,0));
            return new SeedTerrainChunk.Empty(x,z);
        },2,()->0L);
        holder.set(queue);
        var old=queue.request(key(1,0)).orElseThrow(); queue.tick(1,100);
        assertTrue(old.result().toCompletableFuture().isCompletedExceptionally());
        var next=queue.request(key(2,0)).orElseThrow(); queue.tick(4096,100);
        assertEquals(2,next.result().toCompletableFuture().join().key().worldEpoch());
    }
    @Test public void callerCannotCompleteInternalFutureAndCloseCancelsPending() {
        var queue=new SeedPreviewQueue(1,SeedTerrainChunk.Empty::new,1,()->0L);
        var ticket=queue.request(key(1,0)).orElseThrow();
        ticket.result().toCompletableFuture().complete(null);
        assertFalse(ticket.result().toCompletableFuture().isDone());
        queue.close();
        assertTrue(ticket.result().toCompletableFuture().isCompletedExceptionally());
        assertThrows(IllegalStateException.class,()->queue.request(key(1,0)));
    }
    @Test public void negativeCoordinatesReachCorrectChunkAndLocalColumn() {
        var queue=new SeedPreviewQueue(1,(x,z)->{
            assertTrue(x>=-4 && x<=-1); assertTrue(z>=-4 && z<=-1);
            var values=new double[825];
            for(int ix=0;ix<5;ix++)for(int iz=0;iz<5;iz++)for(int y=0;y<33;y++)values[(ix*5+iz)*33+y]=8+ix*4+iz*4-y*8;
            return new SeedTerrainChunk.Density(new OverworldDensityLattice(x,z,16,values));
        },1,()->0L);
        var ticket=queue.request(new TerrainTileKey(1,0,-1,-1)).orElseThrow(); queue.tick(4096,100);
        var tile=ticket.result().toCompletableFuture().join();
        assertEquals(8,tile.columns().getFirst().groundTop());
        assertEquals(SeedTerrainTile.Kind.OCEAN,tile.columns().getFirst().kind());
        assertEquals(38,tile.columns().getLast().groundTop());
        assertEquals(SeedTerrainTile.Kind.LAND,tile.columns().getLast().kind());
    }
    @Test public void onlyOwnerThreadMayScheduleWork() {
        var queue=new SeedPreviewQueue(1,SeedTerrainChunk.Empty::new,1,()->0L);
        CompletableFuture.runAsync(()->assertThrows(IllegalStateException.class,()->queue.request(key(1,0)))).join();
    }

    @Test public void alignedAsyncColumnsRemainPrivateUntilWorkerCompletes() {
        var owner=Thread.currentThread();
        var delayed=new CompletableFuture<SeedTerrainTile.Column>();
        var calls=new AtomicInteger();
        SeedPreviewColumnSource columns=(x,z)->{
            assertSame(owner,Thread.currentThread());
            assertEquals(0,Math.floorMod(x,4));
            assertEquals(0,Math.floorMod(z,4));
            calls.incrementAndGet();return delayed;
        };
        var queue=new SeedPreviewQueue(1,SeedTerrainChunk.Empty::new,columns,1,()->0L);
        var ticket=queue.request(new TerrainTileKey(1,6,0,0)).orElseThrow();
        assertEquals(64,queue.tick(4096,100));
        assertEquals(64,calls.get());
        assertFalse(ticket.result().toCompletableFuture().isDone());
        delayed.complete(new SeedTerrainTile.Column(SeedTerrainTile.Kind.LAND,72,72));
        for(int i=0;i<256 && !ticket.result().toCompletableFuture().isDone();i++)queue.tick(4096,100);
        var tile=ticket.result().toCompletableFuture().join();
        assertEquals(4096,tile.columns().size());
        assertTrue(tile.columns().stream().allMatch(column->column.groundTop()==72));
    }

    @Test public void cancellationRevokesUnfinishedAsyncColumnsAndOldWorld() {
        var delayed=new CompletableFuture<SeedTerrainTile.Column>();
        var queue=new SeedPreviewQueue(1,SeedTerrainChunk.Empty::new,(x,z)->delayed,1,()->0L);
        var old=queue.request(new TerrainTileKey(1,6,0,0)).orElseThrow();
        queue.tick(64,100);
        queue.switchWorld(2,SeedTerrainChunk.Empty::new);
        assertTrue(old.result().toCompletableFuture().isCompletedExceptionally());
        assertFalse(delayed.complete(SeedTerrainTile.Column.EMPTY));
        assertEquals(0,queue.pending());
        var next=queue.request(new TerrainTileKey(2,6,0,0)).orElseThrow();
        queue.tick(4096,100);
        assertTrue(next.result().toCompletableFuture().join().columns().stream()
                .allMatch(column->column.kind()==SeedTerrainTile.Kind.EMPTY));
    }
}
