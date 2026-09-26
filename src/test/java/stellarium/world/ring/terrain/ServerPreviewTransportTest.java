package stellarium.world.ring.terrain;

import static org.junit.Assert.*;
import io.netty.buffer.Unpooled;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class ServerPreviewTransportTest {
    @Rule public TemporaryFolder temporary=new TemporaryFolder();
    private static final UUID NONCE=new UUID(53,11);
    private static PreviewRequestPacket hello(long generation) {
        return new PreviewRequestPacket(NONCE,generation,0,0,0,PreviewRequestPacket.Command.HELLO,0,0,0);
    }
    private static PreviewRequestPacket tile(long generation,long sequence,int x,int z) {
        return new PreviewRequestPacket(NONCE,generation,0,1,sequence,PreviewRequestPacket.Command.TILE,0,x,z);
    }
    private static PreviewRequestPacket release(long sequence) {
        return new PreviewRequestPacket(NONCE,1,0,1,sequence,PreviewRequestPacket.Command.RELEASE,0,0,0);
    }
    private final class Fixture implements AutoCloseable,ServerPreviewTransport.Gateway {
        final Object world=new Object();
        final AtomicInteger samples=new AtomicInteger();
        boolean real;
        final Map<Object,ServerPreviewTransport.Connection> connections=new IdentityHashMap<>();
        final Map<Object,List<PreviewResponsePacket>> replies=new IdentityHashMap<>();
        final PreviewCacheIdentity identity=new PreviewCacheIdentity(UUID.randomUUID(),UUID.randomUUID(),0,UUID.randomUUID(),1);
        final SeedPreviewSession session;
        final ServerPreviewTransport transport=new ServerPreviewTransport(this);
        Fixture(CompletableFuture<SeedPreviewAdmission.Coverage> coverage) throws Exception {
            var io=new SeedPreviewIo(temporary.newFolder().toPath(),identity,1,64,256);
            session=new SeedPreviewSession(1,(x,z)->{samples.incrementAndGet();return new SeedTerrainChunk.Empty(x,z);},io,
                    k->real?CompletableFuture.completedFuture(SeedPreviewAdmission.Coverage.REAL_DATA):coverage,64);
            transport.start();
        }
        Object client() {
            var id=new Object();connections.put(id,new ServerPreviewTransport.Connection(world,0,0,0));
            replies.put(id,new ArrayList<>());offer(id,hello(1));transport.advance();replies.get(id).clear();return id;
        }
        void offer(Object client,PreviewRequestPacket packet) {
            var bytes=Unpooled.buffer();
            try {packet.encode(bytes);transport.offer(client,PreviewRequestPacket.decode(bytes));}
            finally {bytes.release();}
        }
        void awaitTile(Object client) throws Exception {
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
            while(replies.get(client).stream().noneMatch(p->p.type()==PreviewResponsePacket.Type.TILE)) {
                if(System.nanoTime()>deadline)throw new AssertionError("Transport stalled: "+replies.get(client));
                session.tick(4096,10_000_000);transport.advance();Thread.sleep(1);
            }
        }
        @Override public ServerPreviewTransport.Connection connection(Object client){return connections.get(client);}
        @Override public Optional<ServerPreviewRuntime.View> prepare(Object value) {
            return value==world?Optional.of(new ServerPreviewRuntime.View(1,identity,session)):Optional.empty();
        }
        @Override public PreviewResponsePacket.Reason unavailableReason(Object value){return PreviewResponsePacket.Reason.UNSUPPORTED;}
        @Override public void send(Object client,PreviewResponsePacket packet) {
            var bytes=Unpooled.buffer();
            try {packet.encode(bytes);replies.get(client).add(PreviewResponsePacket.decode(bytes));}
            catch(java.io.IOException failure){throw new AssertionError(failure);}
            finally {bytes.release();}
        }
        @Override public void failed(String message,RuntimeException failure){throw new AssertionError(message,failure);}
        @Override public void close() throws Exception {
            transport.stop();session.closeAsync().toCompletableFuture().get(10,TimeUnit.SECONDS);
        }
    }
    @Test public void sharedTileSurvivesOnePeerCancellationAndRetiresForRealData() throws Exception {
        try(var f=new Fixture(CompletableFuture.completedFuture(SeedPreviewAdmission.Coverage.NO_REAL_DATA))) {
            var a=f.client();var b=f.client();
            f.offer(a,tile(1,1,0,0));f.offer(b,tile(1,1,0,0));f.transport.advance();
            f.offer(a,release(2));f.transport.advance();f.awaitTile(b);
            assertTrue(f.replies.get(a).isEmpty());assertEquals(16,f.samples.get());
            f.real=true;f.session.realRegion(0,0,16,16);f.transport.advance();
            assertEquals(PreviewResponsePacket.Type.RETIRED,f.replies.get(b).getLast().type());
            f.offer(b,tile(1,2,0,0));f.transport.advance();f.session.tick(4096,10_000_000);f.transport.advance();
            assertEquals(PreviewResponsePacket.Type.UNAVAILABLE,f.replies.get(b).getLast().type());
            assertEquals(16,f.samples.get());
        }
    }
    @Test public void droppedReleaseExpiresAndDisconnectCancelsPendingWork() throws Exception {
        try(var f=new Fixture(new CompletableFuture<>())) {
            var client=f.client();f.offer(client,tile(1,1,0,0));f.transport.advance();
            var first=f.session.request(tile(1,1,0,0).key()).orElseThrow();
            for(int i=0;i<1199;i++)f.transport.advance();
            assertFalse(first.result().toCompletableFuture().isDone());
            f.transport.advance();assertTrue(first.result().toCompletableFuture().get().isEmpty());
            f.offer(client,tile(1,2,0,0));f.transport.advance();
            var second=f.session.request(tile(1,2,0,0).key()).orElseThrow();assertNotSame(first,second);
            f.connections.remove(client);f.transport.advance();
            assertTrue(second.result().toCompletableFuture().get().isEmpty());assertEquals(0,f.samples.get());
        }
    }
    @Test public void renewedLeaseAndNewGenerationRejectLateCancellation() throws Exception {
        try(var f=new Fixture(new CompletableFuture<>())) {
            var client=f.client();f.offer(client,tile(1,1,0,0));f.transport.advance();
            var first=f.session.request(tile(1,1,0,0).key()).orElseThrow();
            for(int i=0;i<1100;i++)f.transport.advance();
            f.offer(client,tile(1,2,0,0));f.transport.advance();
            for(int i=0;i<200;i++)f.transport.advance();
            assertFalse(first.result().toCompletableFuture().isDone());
            f.offer(client,hello(2));f.transport.advance();assertTrue(first.result().toCompletableFuture().get().isEmpty());
            f.offer(client,tile(2,1,0,0));f.transport.advance();
            var second=f.session.request(tile(2,1,0,0).key()).orElseThrow();
            f.offer(client,release(100));f.offer(client,hello(1));f.transport.advance();
            assertFalse(second.result().toCompletableFuture().isDone());
            f.transport.stop();assertTrue(second.result().toCompletableFuture().get().isEmpty());
        }
    }
    @Test public void fullPeerCanRenewExistingLeaseButCannotAddAnother() throws Exception {
        try(var f=new Fixture(new CompletableFuture<>())) {
            var client=f.client();int sequence=0;
            for(int x=-4;x<8;x++)for(int z=0;z<4;z++)f.offer(client,tile(1,++sequence,x,z));
            f.transport.advance();f.transport.advance();assertTrue(f.replies.get(client).isEmpty());
            f.offer(client,tile(1,++sequence,0,0));f.transport.advance();assertTrue(f.replies.get(client).isEmpty());
            f.offer(client,tile(1,++sequence,-5,0));f.transport.advance();
            assertEquals(1,f.replies.get(client).size());
            assertEquals(PreviewResponsePacket.Reason.BUSY,f.replies.get(client).getFirst().reason());
        }
    }
    @Test public void ingressAndPerTickConsumptionAreBounded() throws Exception {
        try(var f=new Fixture(new CompletableFuture<>())) {
            var client=f.client();for(int i=0;i<300;i++)f.offer(client,hello(1));
            f.transport.advance();assertEquals(32,f.replies.get(client).size());
            for(int i=0;i<10;i++)f.transport.advance();
            assertEquals(256,f.replies.get(client).size());
        }
    }
}
