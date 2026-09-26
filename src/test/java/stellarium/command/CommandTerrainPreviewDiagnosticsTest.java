package stellarium.command;

import static org.junit.Assert.*;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.regex.Pattern;
import net.minecraft.command.CommandHandler;
import net.minecraft.command.ICommandSender;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.world.World;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import stellarium.world.ring.terrain.PreviewCacheIdentity;
import stellarium.world.ring.terrain.PreviewRequestPacket;
import stellarium.world.ring.terrain.PreviewResponsePacket;
import stellarium.world.ring.terrain.SeedPreviewAdmission;
import stellarium.world.ring.terrain.SeedPreviewIo;
import stellarium.world.ring.terrain.SeedPreviewSession;
import stellarium.world.ring.terrain.ServerPreviewRuntime;
import stellarium.world.ring.terrain.TerrainPreviewTrace;
import stellarium.world.ring.terrain.TerrainPreviewTrace.CoverageUnknownCause;

/**
 * Drives the real server-side production paths (transport ingress/response loop, preview session, admission)
 * and reads the resulting counters only through {@code /ssterrain status}. The command is never allowed to be
 * the source of a counter value: every number asserted here was produced by production code first.
 */
public class CommandTerrainPreviewDiagnosticsTest {
    @Rule public TemporaryFolder temporary=new TemporaryFolder();
    private static final String TRANSPORT_TYPE="stellarium.world.ring.terrain.ServerPreviewTransport";
    private static final String CAUSE_GROUP_OPEN="terrain coverage unknown causes[";
    private static final String[] CAUSES={"examined","pendingWrites","diskDir","diskRegionBudget","diskMutated",
            "diskUnstable","revision","closed","queryCapacity"};
    private static final UUID NONCE=new UUID(53,11);
    @Before public void clear() {TerrainPreviewTrace.reset();}

    // ---- the command contract -------------------------------------------------------------------------

    @Test public void secondCallReportsTheCountersMovedByRealProductionWork() throws Exception {
        var command=new CommandTerrainPreviewDiagnostics();
        var first=new FakeSender(true);
        assertTrue(command.report(first,new String[]{"status"}));
        assertEquals(0,counter(first.messages,"REAL_OR_UNKNOWN"));
        assertEquals(0,counter(first.messages,"noPublication"));
        assertEquals(0,counter(first.messages,"peers"));
        assertEquals(0,counter(first.messages,"hello"));
        try(var fixture=new TransportFixture(CompletableFuture.completedFuture(SeedPreviewAdmission.Coverage.REAL_DATA),1)) {
            // One real client connects and one real tile is refused: the transport, not the command, moves these.
            var client=fixture.client();
            fixture.offer(client,tile(1,0,0));
            fixture.advance();
            fixture.pumpUntilReply(client,PreviewResponsePacket.Type.UNAVAILABLE);
            var second=new FakeSender(true);
            assertTrue(command.report(second,new String[]{"status"}));
            assertEquals(1,counter(second.messages,"hello"));
            assertEquals(1,counter(second.messages,"peers"));
            assertEquals(1,counter(second.messages,"REAL_OR_UNKNOWN"));
            assertEquals(1,counter(second.messages,"noPublication"));
            assertTrue("the second call must show the new sent total",
                    counter(second.messages,"REAL_OR_UNKNOWN")>counter(first.messages,"REAL_OR_UNKNOWN"));
            assertTrue("the second call must show the new gauge",counter(second.messages,"peers")>counter(first.messages,"peers"));
            // A second real refusal must be visible to a THIRD call, so no snapshot can be cached anywhere.
            var other=fixture.client();
            fixture.offer(other,tile(1,0,1));
            fixture.advance();
            fixture.pumpUntilReply(other,PreviewResponsePacket.Type.UNAVAILABLE);
            var third=new FakeSender(true);
            assertTrue(command.report(third,new String[]{"status"}));
            assertEquals(2,counter(third.messages,"REAL_OR_UNKNOWN"));
            assertEquals(2,counter(third.messages,"noPublication"));
            assertEquals(2,counter(third.messages,"hello"));
        }
    }

    @Test public void permissionLevelIsTwoAndTheVanillaGateConsultsIt() throws Exception {
        var command=new CommandTerrainPreviewDiagnostics();
        assertEquals("the level the vanilla gate reads",2,command.getRequiredPermissionLevel());
        var denied=new FakeSender(false);
        assertFalse("the real CommandBase gate must deny an unpermitted sender",command.checkPermission(null,denied));
        assertEquals("the gate must ask for exactly this level and name",List.of("2:ssterrain"),denied.permissionCalls);
        assertTrue("a denied gate must not print anything",denied.messages.isEmpty());
        var allowed=new FakeSender(true);
        assertTrue("the real CommandBase gate must allow a permitted sender",command.checkPermission(null,allowed));
        assertEquals(List.of("2:ssterrain"),allowed.permissionCalls);
        assertTrue(allowed.messages.isEmpty());
    }

    @Test public void vanillaDispatcherDeniesWithoutExecutingAndExecutesWhenPermitted() throws Exception {
        var command=new CommandTerrainPreviewDiagnostics();
        var denied=new FakeSender(false);
        var deniedResult=dispatcher(command).executeCommand(denied,"ssterrain status");
        assertEquals("a denied command must not run",0,deniedResult);
        assertEquals("vanilla reports exactly one denial",1,denied.messages.size());
        // Vanilla sends TextComponentTranslation("commands.generic.permission"); the client renderer resolves the
        // key from the loaded language file, so both the key and its en_us text are accepted here.
        var denial=denied.messages.getFirst();
        assertTrue("vanilla must report the permission denial: "+denial,
                denial.equals("commands.generic.permission")
                        ||denial.equalsIgnoreCase("You do not have permission to use this command"));
        for(var line:TerrainPreviewTrace.serverReportLines())
            assertFalse("a denied command must print no report line",denied.messages.contains(line));
        var allowed=new FakeSender(true);
        var allowedResult=dispatcher(command).executeCommand(allowed,"ssterrain status");
        assertTrue("a permitted command must run through the same dispatcher",allowedResult>0);
        assertEquals("the permitted call prints exactly the snapshot",TerrainPreviewTrace.serverReportLines(),
                allowed.messages);
    }

    @Test public void invalidArgumentsSendOnlyTheUsageLine() throws Exception {
        var command=new CommandTerrainPreviewDiagnostics();
        var report=TerrainPreviewTrace.serverReportLines();
        assertFalse("the snapshot must be non-empty so this test can detect one",report.isEmpty());
        for(var args:new String[][]{new String[0],new String[]{"bogus"},new String[]{"status","extra"}}) {
            var checked=new FakeSender(true);
            assertFalse("missing or unknown arguments must be refused",command.report(checked,args));
            var executed=new FakeSender(true);
            command.execute(null,executed,args);
            assertEquals("exactly one usage line may be sent",List.of(command.getUsage(executed)),executed.messages);
            for(var line:report)
                assertFalse("no report line may be printed for "+List.of(args),executed.messages.contains(line));
            assertEquals(checked.messages,executed.messages);
        }
        for(var args:new String[][]{new String[]{"status"},new String[]{"STATUS"}}) {
            var sender=new FakeSender(true);
            assertTrue("a valid status argument must print the snapshot",command.report(sender,args));
            assertEquals(report,sender.messages);
        }
    }

    @Test public void executingTheCommandChangesNoCounterAndNoGauge() throws Exception {
        try(var fixture=new TransportFixture(CompletableFuture.completedFuture(SeedPreviewAdmission.Coverage.REAL_DATA),1)) {
            var client=fixture.client();
            fixture.offer(client,tile(1,0,0));
            fixture.advance();
            fixture.pumpUntilReply(client,PreviewResponsePacket.Type.UNAVAILABLE);
            assertEquals(1,counter(TerrainPreviewTrace.serverReportLines(),"REAL_OR_UNKNOWN"));
            var command=new CommandTerrainPreviewDiagnostics();
            var before=new ArrayList<>(TerrainPreviewTrace.serverReportLines());
            var first=new FakeSender(true);
            command.execute(null,first,new String[]{"status"});
            var second=new FakeSender(true);
            command.execute(null,second,new String[]{"status"});
            assertEquals("the command must render current state only",before,first.messages);
            assertEquals("repeated calls must render the same snapshot",first.messages,second.messages);
            assertEquals("the command must not mutate any counter or gauge",before,
                    new ArrayList<>(TerrainPreviewTrace.serverReportLines()));
        }
    }

    // ---- output contract ------------------------------------------------------------------------------

    @Test public void everyCoverageCauseIsPrintedAndTheGroupIsSplitInsteadOfCut() throws Exception {
        var expected=new LinkedHashMap<String,Long>();
        // No headless path reaches these nine sites: ServerPreviewCoverage and AnvilPreviewCoverage need a real
        // WorldServer, an Anvil chunk loader and a world region directory. Each cause is therefore advanced
        // through the trace API (never invented), while every other assertion in this suite uses production code.
        for(int i=0;i<CAUSES.length;i++) {
            var cause=CoverageUnknownCause.values()[i];
            for(int step=0;step<1_000_000+i;step++)TerrainPreviewTrace.serverCoverageUnknownCause(cause);
            expected.put(CAUSES[i],1_000_000L+i);
        }
        var sender=new FakeSender(true);
        assertTrue(new CommandTerrainPreviewDiagnostics().report(sender,new String[]{"status"}));
        assertTrue("line budget: "+sender.messages.size(),sender.messages.size()<=16);
        for(var line:sender.messages)assertTrue("length "+line.length()+": "+line,line.length()<=200);
        for(var entry:expected.entrySet())
            assertEquals("cause "+entry.getKey(),(long)entry.getValue(),counter(sender.messages,entry.getKey()));
        assertTrue("the nine causes must span more than one line",
                sender.messages.stream().filter(line->line.contains("causes")).count()>1);
        var naive=new StringBuilder(CAUSE_GROUP_OPEN);
        for(var entry:expected.entrySet()) {
            if(naive.length()>CAUSE_GROUP_OPEN.length())naive.append(',');
            naive.append(entry.getKey()).append('=').append(entry.getValue());
        }
        naive.append(']');
        assertTrue("a single-line rendering of this input must overflow, otherwise splitting proves nothing: "
                +naive.length(),naive.length()>200);
    }

    @Test public void longMaxValueCountersStayInsideTheLineBudget() throws Exception {
        saturateEveryCounterAndGauge();
        try {
            var sender=new FakeSender(true);
            assertTrue(new CommandTerrainPreviewDiagnostics().report(sender,new String[]{"status"}));
            assertTrue("line budget: "+sender.messages.size(),sender.messages.size()<=16);
            for(var line:sender.messages) {
                assertNotNull(line);
                assertTrue("length "+line.length()+": "+line,line.length()<=200);
            }
            for(var label:new String[]{"hello","tile","retired","ingressStopped","ingressFull","dimOrConnection",
                    "staleHello","leaseGate","responseBudget","leaseExpired","peerCap","rangeOrLeaseCap","sharedCap",
                    "sessionRefused","noPublication","sessionUnusable","notCurrent","UNKNOWN","NO_REAL_DATA",
                    "REAL_DATA","examined","pendingWrites","diskDir","diskRegionBudget","diskMutated","diskUnstable",
                    "revision","closed","queryCapacity","coverage","inflight","realRegionPins","entryCapacity"})
                assertEquals("counter "+label+" at Long.MAX_VALUE",Long.MAX_VALUE,counter(sender.messages,label));
            for(var gauge:new String[]{"peers","leases","shared","ingress","cov","read","reserve","sample","write"})
                assertEquals("gauge "+gauge+" at Long.MAX_VALUE",Long.MAX_VALUE,counter(sender.messages,gauge));
            var capacities=sender.messages.stream().anyMatch(line->line.contains(
                    "coveragePending="+Long.MAX_VALUE+"/"+Long.MAX_VALUE)&&line.contains(
                    "admissionEntries="+Long.MAX_VALUE+"/"+Long.MAX_VALUE));
            assertTrue("both capacities must survive saturation",capacities);
            assertTrue("an unconnected slot must still not be printed as a number",
                    sender.messages.stream().anyMatch(line->line.contains("handleDisabled=unsupported")));
            // Recorded in the test XML so a real run can be compared against actual rendering.
            System.out.println("serverReportLines() with every slot at Long.MAX_VALUE:");
            for(var line:sender.messages)System.out.println(line.length()+" | "+line);
        } finally {
            TerrainPreviewTrace.reset();
        }
    }

    @Test public void emptySnapshotPrintsAnExplicitZeroState() throws Exception {
        var sender=new FakeSender(true);
        assertTrue(new CommandTerrainPreviewDiagnostics().report(sender,new String[]{"status"}));
        assertFalse("a zero server state must be explicit, not an empty list",sender.messages.isEmpty());
        assertEquals("terrain server: no events recorded (process-local snapshot)",sender.messages.getFirst());
        assertTrue(sender.messages.size()<=16);
        for(var line:sender.messages) {
            assertNotNull(line);
            assertFalse("no blank line may be sent",line.isBlank());
            assertTrue("length "+line.length()+": "+line,line.length()<=200);
        }
        assertEquals(0,counter(sender.messages,"peers"));
        assertEquals(0,counter(sender.messages,"leases"));
        assertEquals(0,counter(sender.messages,"shared"));
        assertEquals(0,counter(sender.messages,"ingress"));
        assertEquals(0,counter(sender.messages,"REAL_OR_UNKNOWN"));
        assertEquals(0,counter(sender.messages,"examined"));
        assertTrue("the capacities must be visible even when nothing happened",
                sender.messages.stream().anyMatch(line->line.contains("coveragePending=0/0")));
        assertTrue(sender.messages.stream().anyMatch(line->line.contains("admissionEntries=0/0")));
    }

    @Test public void unwiredSlotsAreMarkedUnsupportedInsteadOfFabricatedZeros() throws Exception {
        try(var fixture=new TransportFixture(CompletableFuture.completedFuture(SeedPreviewAdmission.Coverage.REAL_DATA),1)) {
            fixture.client();
            var sender=new FakeSender(true);
            assertTrue(new CommandTerrainPreviewDiagnostics().report(sender,new String[]{"status"}));
            // These three slots have no production call site today: an observed report must label them.
            assertTrue(sender.messages.stream().anyMatch(line->line.contains("queryInflight=unsupported")));
            assertTrue(sender.messages.stream().anyMatch(line->line.contains("handleDisabled=unsupported")));
            assertTrue(sender.messages.stream().anyMatch(line->line.contains("nonLiveCoverageVerdict=unsupported")));
            assertFalse("no fake zero may be printed for the query-inflight refusal",
                    sender.messages.stream().anyMatch(line->line.contains("queryInflight=0")));
            assertFalse(sender.messages.stream().anyMatch(line->line.contains("handleDisabled=0")));
            // Every printed value is a real number, a capacity pair, or the explicit unsupported marker.
            var values=Pattern.compile("(?:^|[\\s\\[,])([A-Za-z][A-Za-z0-9\\-]*)=([^\\s\\],]+)");
            var checked=0;
            for(var line:sender.messages) {
                var matcher=values.matcher(line);
                while(matcher.find()) {
                    assertTrue("unsupported value "+matcher.group(1)+"="+matcher.group(2),
                            matcher.group(2).matches("\\d+|\\d+/\\d+|unsupported"));
                    checked++;
                }
            }
            assertTrue("the sweep must have inspected the report: "+checked,checked>3);
        }
    }

    // ---- scope, regression and data source -------------------------------------------------------------

    @Test public void realDataCoverageRefusalReadsBackThroughTheServerReport() throws Exception {
        try(var fixture=new TransportFixture(CompletableFuture.completedFuture(SeedPreviewAdmission.Coverage.REAL_DATA),1)) {
            var client=fixture.client();
            fixture.offer(client,tile(1,0,0));
            fixture.advance();
            var lease=fixture.session.request(tile(1,0,0).key()).orElseThrow();
            fixture.pumpUntilReply(client,PreviewResponsePacket.Type.UNAVAILABLE);
            assertEquals(1,fixture.repliesOf(client).size());
            assertEquals(PreviewResponsePacket.Type.UNAVAILABLE,fixture.repliesOf(client).getFirst().type());
            assertEquals(PreviewResponsePacket.Reason.REAL_OR_UNKNOWN,fixture.repliesOf(client).getFirst().reason());
            assertTrue("the empty publication must have completed the lease",
                    lease.result().toCompletableFuture().isDone());
            var sender=new FakeSender(true);
            assertTrue(new CommandTerrainPreviewDiagnostics().report(sender,new String[]{"status"}));
            assertEquals("exactly one UNAVAILABLE(REAL_OR_UNKNOWN)",1,counter(sender.messages,"REAL_OR_UNKNOWN"));
            assertEquals("exactly one noPublication site",1,counter(sender.messages,"noPublication"));
            assertEquals(0,counter(sender.messages,"notCurrent"));
            assertEquals(0,counter(sender.messages,"sessionUnusable"));
            assertEquals("no tile may be reported as sent",0,counter(sender.messages,"tile"));
            assertEquals("no retirement may be reported",0,counter(sender.messages,"retired"));
            assertEquals("the live verdict is REAL_DATA",1,counter(sender.messages,"REAL_DATA"));
            assertEquals("REAL_DATA refuses the preview ticket, so the job ends as inflight",
                    1,counter(sender.messages,"inflight"));
            assertEquals(0,counter(sender.messages,"coverage"));
        }
    }

    @Test public void serverReportCarriesNoClientSideCounterEvenWhenTheClientSideOnesMove() throws Exception {
        for(int i=0;i<5;i++)TerrainPreviewTrace.clientSend(false);
        TerrainPreviewTrace.clientRetry();
        TerrainPreviewTrace.clientAcceptDrop(TerrainPreviewTrace.ClientGate.ENVELOPE);
        TerrainPreviewTrace.clientResponse(PreviewResponsePacket.Type.TILE,null,false);
        TerrainPreviewTrace.clientProgress(500,7,8,9);
        TerrainPreviewTrace.clientInboxNullReceipt();
        TerrainPreviewTrace.clientClockGate(TerrainPreviewTrace.ClockGate.STALE_TICKET);
        assertEquals("the fixture must move client-side counters",5,counter(TerrainPreviewTrace.reportLines(),"tile"));
        var sender=new FakeSender(true);
        assertTrue(new CommandTerrainPreviewDiagnostics().report(sender,new String[]{"status"}));
        for(var label:new String[]{"release","retry","rsp","pending","tiles","stallTicks","nettyNullReceipt",
                "clockNullReceipt","clockStaleTicket","inboxOverflow","idleTicks","envelope","welcomeConflict",
                "epoch","noPendingOrSequence","tileIdentity","rejectRetired","rejectRealOrUnknown"})
            assertFalse("the client-side field "+label+" must not appear in the server report",
                    hasField(sender.messages,label));
        for(var line:TerrainPreviewTrace.reportLines())
            assertFalse("the client report line must not be printed by the server command",sender.messages.contains(line));
    }

    @Test public void serverReportScopeLineStatesProcessLocalityCumulativeSemanticsAndTheWorldLimit() throws Exception {
        var sender=new FakeSender(true);
        assertTrue(new CommandTerrainPreviewDiagnostics().report(sender,new String[]{"status"}));
        var scope=sender.messages.getLast();
        assertTrue("the scope line must close the report: "+scope,scope.startsWith("terrain server scope:"));
        assertTrue(scope.length()<=200);
        assertTrue("process locality: "+scope,scope.contains("process-local"));
        assertTrue("cumulative counters: "+scope,scope.contains("counters cumulative"));
        assertTrue("latest gauges: "+scope,scope.contains("gauges latest"));
        assertTrue("no per-world split: "+scope,scope.contains("no per-world split"));
        assertTrue("read-only: "+scope,scope.contains("read-only"));
    }

    // ---- helpers --------------------------------------------------------------------------------------

    /** The vanilla command dispatcher: the real permission gate, Forge command event and execute path. */
    private static CommandHandler dispatcher(CommandTerrainPreviewDiagnostics command) {
        var handler=new CommandHandler() {
            @Override protected MinecraftServer getServer() {return null;}
        };
        handler.registerCommand(command);
        return handler;
    }

    /**
     * Minimal real sender. Only the two methods that matter behave: {@code sendMessage} records every line and
     * {@code canUseCommand} records every permission question and answers with the configured verdict.
     */
    private static final class FakeSender implements ICommandSender {
        private final List<String> messages=new ArrayList<>();
        private final List<String> permissionCalls=new ArrayList<>();
        private final boolean permitted;
        FakeSender(boolean permitted) {this.permitted=permitted;}
        @Override public String getName() {return "fake-console";}
        @Override public boolean canUseCommand(int level,String name) {permissionCalls.add(level+":"+name);return permitted;}
        @Override public World getEntityWorld() {return null;}
        @Override public MinecraftServer getServer() {return null;}
        @Override public void sendMessage(ITextComponent component) {messages.add(component.getUnformattedText());}
    }

    private static PreviewRequestPacket hello(long generation) {
        return new PreviewRequestPacket(NONCE,generation,0,0,0,PreviewRequestPacket.Command.HELLO,0,0,0);
    }
    private static PreviewRequestPacket tile(long sequence,int x,int z) {
        return new PreviewRequestPacket(NONCE,1,0,1,sequence,PreviewRequestPacket.Command.TILE,0,x,z);
    }

    /**
     * Real transport and real session behind a fake gateway. {@code ServerPreviewTransport.Gateway}, its
     * constructor, the {@code Connection} record and {@code advance()} are package-private while this suite must
     * live in {@code stellarium.command}, so that seam alone is reached reflectively: the code under test
     * (offer, accept, the response loop, the packets and every counter) is the unmodified production code.
     */
    private final class TransportFixture implements AutoCloseable {
        final Object world=new Object();
        final Map<Object,Object> connections=new IdentityHashMap<>();
        final Map<Object,List<PreviewResponsePacket>> replies=new IdentityHashMap<>();
        final PreviewCacheIdentity identity=new PreviewCacheIdentity(UUID.randomUUID(),UUID.randomUUID(),0,
                UUID.randomUUID(),1);
        final SeedPreviewSession session;
        private final Object transport;
        private final Method offerMethod,advanceMethod,stopMethod;

        TransportFixture(CompletableFuture<SeedPreviewAdmission.Coverage> coverage,int capacity) throws Exception {
            var io=new SeedPreviewIo(temporary.newFolder().toPath(),identity,1,64,256);
            // The REAL_DATA refusal must never sample a chunk: sampling here would mean the fixture is wrong.
            session=new SeedPreviewSession(1,(chunkX,chunkZ)->{
                throw new AssertionError("REAL_DATA coverage must refuse before any chunk is sampled");
            },io,key->coverage,capacity);
            var type=Class.forName(TRANSPORT_TYPE);
            var gatewayType=Class.forName(TRANSPORT_TYPE+"$Gateway");
            var gateway=Proxy.newProxyInstance(type.getClassLoader(),new Class<?>[]{gatewayType},this::gateway);
            var constructor=type.getDeclaredConstructor(gatewayType);
            constructor.setAccessible(true);
            transport=constructor.newInstance(gateway);
            offerMethod=type.getMethod("offer",Object.class,PreviewRequestPacket.class);
            advanceMethod=type.getDeclaredMethod("advance");
            advanceMethod.setAccessible(true);
            stopMethod=type.getMethod("stop");
            type.getMethod("start").invoke(transport);
        }

        private Object gateway(Object proxy,Method method,Object[] args) {
            return switch(method.getName()) {
                case "connection" -> connections.get(args[0]);
                case "prepare" -> args[0]==world
                        ? Optional.of(new ServerPreviewRuntime.View(1,identity,session)) : Optional.empty();
                case "unavailableReason" -> PreviewResponsePacket.Reason.UNSUPPORTED;
                case "send" -> {replies.get(args[0]).add((PreviewResponsePacket)args[1]);yield null;}
                case "failed" -> throw new AssertionError("transport reported a failure: "+args[0],(Throwable)args[1]);
                case "toString" -> "fixture gateway";
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy==args[0];
                default -> throw new AssertionError("unexpected gateway method "+method);
            };
        }

        Object client() throws Exception {
            var key=new Object();
            var connectionType=Class.forName(TRANSPORT_TYPE+"$Connection");
            var constructor=connectionType.getDeclaredConstructor(Object.class,int.class,double.class,double.class);
            constructor.setAccessible(true);
            connections.put(key,constructor.newInstance(world,0,0.0,0.0));
            replies.put(key,new ArrayList<>());
            offer(key,hello(1));
            advance();
            replies.get(key).clear();
            return key;
        }
        void offer(Object client,PreviewRequestPacket packet) throws Exception {
            offerMethod.invoke(transport,client,packet);
        }
        void advance() throws Exception {
            try {advanceMethod.invoke(transport);}
            catch(InvocationTargetException failure) {
                if(failure.getCause() instanceof RuntimeException runtime)throw runtime;
                throw failure;
            }
        }
        void pumpUntilReply(Object client,PreviewResponsePacket.Type type) throws Exception {
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
            while(repliesOf(client).stream().noneMatch(packet->packet.type()==type)) {
                if(System.nanoTime()>deadline)throw new AssertionError("Transport stalled: "+repliesOf(client));
                session.tick(4096,10_000_000);
                advance();
                Thread.sleep(1);
            }
        }
        List<PreviewResponsePacket> repliesOf(Object client) {return replies.get(client);}
        @Override public void close() throws Exception {
            stopMethod.invoke(transport);
            session.closeAsync().toCompletableFuture().get(10,TimeUnit.SECONDS);
        }
    }

    /**
     * No public trace API can reach Long.MAX_VALUE: counters move by +1 per event and the only additive call
     * takes an int. The boundary is therefore injected into the report's input directly. Nothing is asserted
     * about fabricated values: the point is that the renderer stays inside its bounds for that input.
     */
    private static void saturateEveryCounterAndGauge() throws Exception {
        var countsField=TerrainPreviewTrace.class.getDeclaredField("COUNTS");
        countsField.setAccessible(true);
        var counts=(AtomicLongArray)countsField.get(null);
        for(int index=0;index<counts.length();index++)counts.set(index,Long.MAX_VALUE);
        for(var name:new String[]{"SERVER_PEERS","SERVER_LEASES","SERVER_SHARED","SERVER_INGRESS","COVERAGE_PENDING",
                "COVERAGE_CAPACITY","ADMISSION_ENTRIES","ADMISSION_CAPACITY","JOB_COVERAGE","JOB_READ","JOB_RESERVE",
                "JOB_SAMPLE","JOB_WRITE"}) {
            var field=TerrainPreviewTrace.class.getDeclaredField(name);
            field.setAccessible(true);
            ((AtomicLong)field.get(null)).set(Long.MAX_VALUE);
        }
    }

    /** Reads a rendered {@code label=number} pair out of the emitted lines; an omitted counter reads as 0. */
    private static long counter(List<String> lines,String label) {
        var matcher=Pattern.compile("(?:^|[\\s\\[,])"+Pattern.quote(label)+"=(\\d+)").matcher("");
        for(var line:lines) {
            matcher.reset(line);
            if(matcher.find())return Long.parseLong(matcher.group(1));
        }
        return 0;
    }
    private static boolean hasField(List<String> lines,String label) {
        var matcher=Pattern.compile("(?:^|[\\s\\[,])"+Pattern.quote(label)+"=").matcher("");
        for(var line:lines) {
            matcher.reset(line);
            if(matcher.find())return true;
        }
        return false;
    }
}
