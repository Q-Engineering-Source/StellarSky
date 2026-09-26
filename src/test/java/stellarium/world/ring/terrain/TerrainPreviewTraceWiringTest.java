package stellarium.world.ring.terrain;

import static org.junit.Assert.*;
import static stellarium.world.ring.terrain.SeedPreviewAdmission.Coverage.NO_REAL_DATA;
import static stellarium.world.ring.terrain.SeedPreviewAdmission.Coverage.REAL_DATA;
import io.netty.buffer.Unpooled;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Drives the real preview transport/session/admission paths and asserts the trace counters move because that
 * production code ran. The counters are never called directly here.
 */
public class TerrainPreviewTraceWiringTest {
    @Rule public TemporaryFolder temporary=new TemporaryFolder();
    private static final UUID NONCE=new UUID(53,11);
    private static final String TRACE="terrain trace:",SENT="terrain server sent:";
    private static final String BUSY="terrain server busy:",COVERAGE="terrain coverage:",SESSION="terrain session:";
    @Before public void clear() {TerrainPreviewTrace.reset();}

    private static PreviewRequestPacket hello(long generation) {
        return new PreviewRequestPacket(NONCE,generation,0,0,0,PreviewRequestPacket.Command.HELLO,0,0,0);
    }
    private static PreviewRequestPacket tile(long sequence,int x,int z) {
        return new PreviewRequestPacket(NONCE,1,0,1,sequence,PreviewRequestPacket.Command.TILE,0,x,z);
    }

    /** Same fake-gateway technique as ServerPreviewTransportTest; kept local because that fixture is private. */
    private final class Fixture implements AutoCloseable,ServerPreviewTransport.Gateway {
        final Object world=new Object();
        final Map<Object,ServerPreviewTransport.Connection> connections=new IdentityHashMap<>();
        final Map<Object,List<PreviewResponsePacket>> replies=new IdentityHashMap<>();
        final PreviewCacheIdentity identity=new PreviewCacheIdentity(UUID.randomUUID(),UUID.randomUUID(),0,UUID.randomUUID(),1);
        final SeedPreviewSession session;
        final ServerPreviewTransport transport=new ServerPreviewTransport(this);
        Fixture(CompletableFuture<SeedPreviewAdmission.Coverage> coverage,int capacity) throws Exception {
            var io=new SeedPreviewIo(temporary.newFolder().toPath(),identity,1,64,256);
            session=new SeedPreviewSession(1,(x,z)->new SeedTerrainChunk.Empty(x,z),io,key->coverage,capacity);
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
        void pump(int rounds) throws Exception {
            for(int i=0;i<rounds;i++) {session.tick(4096,10_000_000);transport.advance();Thread.sleep(1);}
        }
        void pumpUntilTile(Object client) throws Exception {
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

    @Test public void realCoverageVerdictAndUnavailableRealOrUnknownAreRecordedByTheTransportPath() throws Exception {
        try(var f=new Fixture(CompletableFuture.completedFuture(REAL_DATA),64)) {
            var client=f.client();
            f.offer(client,tile(1,0,0));
            f.transport.advance();
            var lease=f.session.request(tile(1,0,0).key()).orElseThrow();
            f.pump(40);
            var reply=f.replies.get(client);
            assertEquals(1,reply.size());
            assertEquals(PreviewResponsePacket.Type.UNAVAILABLE,reply.getFirst().type());
            assertEquals(PreviewResponsePacket.Reason.REAL_OR_UNKNOWN,reply.getFirst().reason());
            assertTrue("the empty publication must have completed the lease",lease.result().toCompletableFuture().isDone());
            assertEquals(1,number(section(line(COVERAGE),"live[","]"),"REAL_DATA"));
            assertEquals(0,number(section(line(COVERAGE),"live[","]"),"NO_REAL_DATA"));
            assertEquals(1,number(section(line(SENT),"unavail=[","]"),"REAL_OR_UNKNOWN"));
            assertEquals(0,number(SENT,"tile"));
            assertEquals(1,number(section(line(BUSY),"realOrUnknown[","]"),"noPublication"));
            assertEquals(0,number(section(line(BUSY),"realOrUnknown[","]"),"notCurrent"));
            // REAL_DATA makes beginPreview refuse, so the empty job is the ticket path rather than the coverage path.
            assertEquals(1,number(section(line(COVERAGE),"beginPreviewEmpty[","]"),"inflight"));
            assertEquals(0,number(section(line(COVERAGE),"beginPreviewEmpty[","]"),"coverage"));
        }
    }

    @Test public void pendingCoverageLeavesTileAndVerdictCountersUntouchedWhileOutstanding() throws Exception {
        try(var f=new Fixture(new CompletableFuture<>(),64)) {
            var client=f.client();
            f.offer(client,tile(1,0,0));
            f.transport.advance();
            var lease=f.session.request(tile(1,0,0).key()).orElseThrow();
            f.pump(40);
            assertFalse("coverage never completed, so the lease must stay outstanding",lease.result().toCompletableFuture().isDone());
            assertTrue(f.replies.get(client).isEmpty());
            assertEquals(0,number(section(line(TRACE),"sent[","]"),"tile"));
            assertEquals(0,number(SENT,"tile"));
            assertEquals(0,number(section(line(SENT),"unavail=[","]"),"REAL_OR_UNKNOWN"));
            var live=section(line(COVERAGE),"live[","]");
            assertEquals(0,number(live,"UNKNOWN"));
            assertEquals(0,number(live,"NO_REAL_DATA"));
            assertEquals(0,number(live,"REAL_DATA"));
            assertEquals(0,number(section(line(COVERAGE),"causes[","]"),"pendingWrites"));
            assertEquals(0,number(section(line(COVERAGE),"beginPreviewEmpty[","]"),"inflight"));
        }
    }

    @Test public void noRealDataCoverageReachesPublishedTileAndRecordsSentTile() throws Exception {
        try(var f=new Fixture(CompletableFuture.completedFuture(NO_REAL_DATA),64)) {
            var client=f.client();
            f.offer(client,tile(1,0,0));
            f.transport.advance();
            f.pumpUntilTile(client);
            assertEquals(PreviewResponsePacket.Type.TILE,f.replies.get(client).getLast().type());
            assertEquals(1,number(section(line(COVERAGE),"live[","]"),"NO_REAL_DATA"));
            assertEquals(1,number(SENT,"tile"));
            assertEquals(0,number(SENT,"retired"));
            assertEquals(0,number(section(line(SENT),"unavail=[","]"),"REAL_OR_UNKNOWN"));
        }
    }

    @Test public void admissionEntryCapacityRefusalIsRecordedThroughSessionRequest() throws Exception {
        try(var f=new Fixture(new CompletableFuture<>(),1)) {
            assertTrue(f.session.request(new TerrainTileKey(1,0,0,0)).isPresent());
            assertEquals(0,number(section(line(SESSION),"requestEmpty[","]"),"entryCapacity"));
            assertTrue("capacity 1 must refuse the second key",f.session.request(new TerrainTileKey(1,0,1,0)).isEmpty());
            assertEquals(1,number(section(line(SESSION),"requestEmpty[","]"),"entryCapacity"));
            assertEquals(0,number(section(line(SESSION),"requestEmpty[","]"),"queryInflight"));
            // The refused request never reaches a coverage verdict and never sends a tile.
            var live=section(line(COVERAGE),"live[","]");
            assertEquals(0,number(live,"UNKNOWN")+number(live,"NO_REAL_DATA")+number(live,"REAL_DATA"));
            assertEquals(0,number(SENT,"tile"));
            assertTrue(line(SESSION).contains("admissionEntries=1/1"));
        }
    }

    @Test public void roomyAdmissionAdmitsBothKeysWithoutRecordingARefusal() throws Exception {
        try(var f=new Fixture(new CompletableFuture<>(),2)) {
            assertTrue(f.session.request(new TerrainTileKey(1,0,0,0)).isPresent());
            assertTrue(f.session.request(new TerrainTileKey(1,0,1,0)).isPresent());
            assertEquals(0,number(section(line(SESSION),"requestEmpty[","]"),"entryCapacity"));
            assertEquals(0,number(section(line(SESSION),"requestEmpty[","]"),"queryInflight"));
            // The gauge is sampled at admission entry, so the last write of the second request reports 1/2.
            assertTrue(line(SESSION).contains("admissionEntries=1/2"));
        }
    }

    private static String line(String prefix) {
        for(var line:TerrainPreviewTrace.reportLines())if(line.startsWith(prefix))return line;
        throw new AssertionError("missing report line "+prefix);
    }
    private static String section(String text,String start,String end) {
        int from=text.indexOf(start);
        if(from<0)throw new AssertionError("missing section "+start+" in "+text);
        from+=start.length();
        int to=text.indexOf(end,from);
        return to<0?text.substring(from):text.substring(from,to);
    }
    /** Reads a label out of a report line, a section of it, or a group prefix naming that line. */
    private static long number(String context,String label) {
        var text=context.endsWith(":")?line(context):context;
        var matcher=Pattern.compile("(?:^|[\\s\\[,])"+Pattern.quote(label)+"=(\\d+)").matcher(text);
        return matcher.find()?Long.parseLong(matcher.group(1)):0;
    }
}
