package stellarium.world.ring.terrain;

import static org.junit.Assert.*;
import java.util.regex.Pattern;
import org.junit.Before;
import org.junit.Test;
import stellarium.world.ring.terrain.PreviewResponsePacket.Reason;
import stellarium.world.ring.terrain.PreviewResponsePacket.Type;
import stellarium.world.ring.terrain.SeedPreviewAdmission.Coverage;
import stellarium.world.ring.terrain.TerrainPreviewTrace.BusySite;
import stellarium.world.ring.terrain.TerrainPreviewTrace.ClientGate;
import stellarium.world.ring.terrain.TerrainPreviewTrace.ClockGate;
import stellarium.world.ring.terrain.TerrainPreviewTrace.CoverageUnknownCause;
import stellarium.world.ring.terrain.TerrainPreviewTrace.RealOrUnknownCause;
import stellarium.world.ring.terrain.TerrainPreviewTrace.ServerGate;
import stellarium.world.ring.terrain.TerrainPreviewTrace.SessionRefusal;

public class TerrainPreviewTraceTest {
    private static final String TRACE="terrain trace:",KEYED="terrain unavail keyed:",GATES="terrain client gates:";
    private static final String DROPS="terrain accept drops:",SENT="terrain server sent:",SGATES="terrain server gates:";
    private static final String BUSY="terrain server busy:",COVERAGE="terrain coverage:",SESSION="terrain session:";
    @Before public void clear() {TerrainPreviewTrace.reset();}

    @Test public void resetZeroesEveryCounterAndGauge() {
        touchEveryCounter();
        assertTrue("fixture must produce non-zero counters",number(TRACE,"retry")>0);
        assertTrue(number(DROPS,"envelope")>0);
        TerrainPreviewTrace.reset();
        for(var label:new String[]{"tile","release","retry","welcome","unavail","envelope","welcomeConflict","epoch",
                "noPendingOrSequence","tileIdentity","nettyNullReceipt","clockNullReceipt","clockStaleTicket","idleTicks",
                "hello","dimOrConnection","staleHello","leaseGate","responseBudget","leaseExpired","ingressStopped","ingressFull",
                "peerCap","rangeOrLeaseCap","sharedCap","sessionRefused","noPublication","sessionUnusable","notCurrent",
                "diskDir","diskRegionBudget","diskMutated","diskUnstable","revision","closed","queryCapacity","examined",
                "pendingWrites","entryCapacity","queryInflight","coverage","inflight","realRegionPins","UNKNOWN","NO_REAL_DATA","REAL_DATA",
                "PREPARING","UNSUPPORTED","FAILED","pending","tiles","retired","stallTicks","peers","leases","shared","ingress",
                "cov","read","reserve","sample","write","rejectRetired","rejectRealOrUnknown"})
            assertEquals("counter "+label+" after reset",0,number(label));
    }

    @Test public void perSiteCountersStayIsolated() {
        for(int i=0;i<7;i++)TerrainPreviewTrace.clientAcceptDrop(ClientGate.ENVELOPE);
        assertEquals(7,number(DROPS,"envelope"));
        assertEquals(0,number(DROPS,"welcomeConflict"));
        assertEquals(0,number(DROPS,"epoch"));
        assertEquals(0,number(DROPS,"noPendingOrSequence"));
        assertEquals(0,number(DROPS,"tileIdentity"));
        for(int i=0;i<5;i++)TerrainPreviewTrace.serverBusy(BusySite.SHARED_CAP);
        assertEquals(0,number(BUSY,"peerCap"));
        assertEquals(0,number(BUSY,"rangeOrLeaseCap"));
        assertEquals(5,number(BUSY,"sharedCap"));
        assertEquals(0,number(BUSY,"sessionRefused"));
        for(int i=0;i<3;i++)TerrainPreviewTrace.serverSilentDrop(ServerGate.LEASE_GATE);
        TerrainPreviewTrace.serverLeaseExpired();
        assertEquals(3,number(SGATES,"leaseGate"));
        assertEquals(1,number(SGATES,"leaseExpired"));
        assertEquals(0,number(SGATES,"dimOrConnection"));
        assertEquals(0,number(SGATES,"staleHello"));
        assertEquals(0,number(SGATES,"responseBudget"));
        assertEquals(0,number(SGATES,"ingressStopped"));
        assertEquals(0,number(SGATES,"ingressFull"));
        for(int i=0;i<2;i++)TerrainPreviewTrace.clientClockGate(ClockGate.STALE_TICKET);
        TerrainPreviewTrace.clientInboxNullReceipt();
        assertEquals(2,number(GATES,"clockStaleTicket"));
        assertEquals(0,number(GATES,"clockNullReceipt"));
        assertEquals(1,number(GATES,"nettyNullReceipt"));
    }

    @Test public void clientRejectionLanesStaySeparate() {
        for(int i=0;i<4;i++)TerrainPreviewTrace.clientRejected(true);
        for(int i=0;i<9;i++)TerrainPreviewTrace.clientRejected(false);
        assertEquals(4,number(DROPS,"rejectRetired"));
        assertEquals(9,number(DROPS,"rejectRealOrUnknown"));
        assertEquals(0,number(DROPS,"envelope"));
        assertEquals(0,number(DROPS,"noPendingOrSequence"));
    }

    @Test public void clientResponseBucketsStaySeparate() {
        TerrainPreviewTrace.clientResponse(Type.UNAVAILABLE,Reason.BUSY,false);
        TerrainPreviewTrace.clientResponse(Type.UNAVAILABLE,Reason.BUSY,false);
        TerrainPreviewTrace.clientResponse(Type.UNAVAILABLE,Reason.PREPARING,false);
        TerrainPreviewTrace.clientResponse(Type.UNAVAILABLE,Reason.BUSY,true);
        TerrainPreviewTrace.clientResponse(Type.UNAVAILABLE,Reason.BUSY,true);
        TerrainPreviewTrace.clientResponse(Type.UNAVAILABLE,Reason.BUSY,true);
        TerrainPreviewTrace.clientResponse(Type.UNAVAILABLE,Reason.FAILED,true);
        TerrainPreviewTrace.clientResponse(Type.TILE,null,false);
        TerrainPreviewTrace.clientResponse(Type.RETIRED,null,false);
        TerrainPreviewTrace.clientResponse(Type.WELCOME,null,false);
        var keyed=line(KEYED);
        var helloAt=keyed.indexOf(" hello[");
        assertTrue("hello block must be rendered once hello-level responses exist",helloAt>0);
        var hello=keyed.substring(helloAt);
        keyed=keyed.substring(0,helloAt);
        assertEquals(2,number(keyed,"BUSY"));
        assertEquals(1,number(keyed,"PREPARING"));
        assertEquals(0,number(keyed,"FAILED"));
        assertEquals(3,number(hello,"BUSY"));
        assertEquals(1,number(hello,"FAILED"));
        assertEquals(0,number(hello,"PREPARING"));
        var response=section(line(TRACE),"rsp[","]");
        assertEquals(1,number(response,"tile"));
        assertEquals(1,number(response,"retired"));
        assertEquals(1,number(response,"welcome"));
        assertEquals(3,number(response,"unavail"));
    }

    @Test public void reportStaysWithinTenLinesAndTwoHundredCharacters() {
        for(int i=0;i<200_000;i++) {
            TerrainPreviewTrace.clientSend(i%3==0);
            TerrainPreviewTrace.clientRetry();
            TerrainPreviewTrace.clientAcceptDrop(ClientGate.values()[i%ClientGate.values().length]);
            TerrainPreviewTrace.clientResponse(Type.UNAVAILABLE,Reason.values()[i%Reason.values().length],i%2==0);
            TerrainPreviewTrace.clientResponse(Type.TILE,null,false);
            TerrainPreviewTrace.clientClockGate(ClockGate.values()[i%2]);
            TerrainPreviewTrace.clientInboxNullReceipt();
            TerrainPreviewTrace.clientTickIdle();
            TerrainPreviewTrace.serverIngress(i%2==0,i%3==0);
            TerrainPreviewTrace.serverSilentDrop(ServerGate.values()[i%ServerGate.values().length]);
            TerrainPreviewTrace.serverBusy(BusySite.values()[i%BusySite.values().length]);
            TerrainPreviewTrace.serverSent(Type.UNAVAILABLE,Reason.values()[i%Reason.values().length]);
            TerrainPreviewTrace.serverSent(Type.TILE,null);
            TerrainPreviewTrace.serverHello();
            TerrainPreviewTrace.serverRealOrUnknown(RealOrUnknownCause.values()[i%3]);
            TerrainPreviewTrace.serverCoverageVerdict(true,Coverage.values()[i%3]);
            TerrainPreviewTrace.serverCoverageUnknownCause(CoverageUnknownCause.values()[i%CoverageUnknownCause.values().length]);
            TerrainPreviewTrace.serverSessionRequestEmpty(SessionRefusal.values()[i%2]);
            TerrainPreviewTrace.serverBeginPreviewEmpty(i%2==0,i%3==0);
            TerrainPreviewTrace.serverRealRegionPins(1000);
        }
        TerrainPreviewTrace.serverRealRegionPins(2_000_000_000);
        TerrainPreviewTrace.clientProgress(9_000_000_000L,123456789,987654321,456789123);
        var lines=TerrainPreviewTrace.reportLines();
        assertEquals(10,lines.size());
        for(var line:lines) {
            assertNotNull(line);
            assertTrue("line must not exceed 200 characters: "+line.length(),line.length()<=200);
            assertFalse(line.isBlank());
        }
        // Recorded in the test XML so a real run can be compared against actual rendering.
        System.out.println("TerrainPreviewTrace.reportLines() after "+200_000+" events:");
        for(var line:lines)System.out.println(line.length()+" | "+line);
        System.out.println("summaryLine: "+TerrainPreviewTrace.summaryLine().length()+" | "+TerrainPreviewTrace.summaryLine());
    }

    @Test public void reportValuesEqualTheCounters() {
        for(int i=0;i<3;i++)TerrainPreviewTrace.clientSend(false);
        TerrainPreviewTrace.clientSend(true);TerrainPreviewTrace.clientSend(true);
        TerrainPreviewTrace.clientRetry();
        TerrainPreviewTrace.clientResponse(Type.TILE,null,false);
        TerrainPreviewTrace.clientResponse(Type.TILE,null,false);
        TerrainPreviewTrace.clientResponse(Type.RETIRED,null,false);
        TerrainPreviewTrace.clientResponse(Type.WELCOME,null,false);
        for(int i=0;i<4;i++)TerrainPreviewTrace.clientResponse(Type.UNAVAILABLE,Reason.BUSY,false);
        for(int i=0;i<5;i++)TerrainPreviewTrace.clientResponse(Type.UNAVAILABLE,Reason.PREPARING,true);
        TerrainPreviewTrace.serverHello();
        for(int i=0;i<6;i++)TerrainPreviewTrace.serverSent(Type.TILE,null);
        for(int i=0;i<7;i++)TerrainPreviewTrace.serverSent(Type.RETIRED,null);
        for(int i=0;i<9;i++)TerrainPreviewTrace.serverSent(Type.UNAVAILABLE,Reason.FAILED);
        for(int i=0;i<11;i++)TerrainPreviewTrace.clientAcceptDrop(ClientGate.NO_PENDING_OR_SEQUENCE);
        for(int i=0;i<13;i++)TerrainPreviewTrace.serverSilentDrop(ServerGate.RESPONSE_BUDGET);
        for(int i=0;i<15;i++)TerrainPreviewTrace.serverBusy(BusySite.PEER_CAP);
        for(int i=0;i<17;i++)TerrainPreviewTrace.serverRealOrUnknown(RealOrUnknownCause.NOT_CURRENT);
        for(int i=0;i<19;i++)TerrainPreviewTrace.serverCoverageUnknownCause(CoverageUnknownCause.DISK_MUTATED);
        for(int i=0;i<21;i++)TerrainPreviewTrace.serverSessionRequestEmpty(SessionRefusal.ENTRY_CAPACITY);
        for(int i=0;i<23;i++)TerrainPreviewTrace.serverBeginPreviewEmpty(true,true);
        TerrainPreviewTrace.serverRealRegionPins(25);
        TerrainPreviewTrace.serverCoverageVerdict(true,Coverage.NO_REAL_DATA);
        var trace=line(TRACE);
        assertEquals(3,number(section(trace,"sent[","]"),"tile"));
        assertEquals(2,number(section(trace,"sent[","]"),"release"));
        assertEquals(1,number(section(trace,"sent[","]"),"retry"));
        assertEquals(2,number(section(trace,"rsp[","]"),"tile"));
        assertEquals(1,number(section(trace,"rsp[","]"),"retired"));
        assertEquals(1,number(section(trace,"rsp[","]"),"welcome"));
        assertEquals(4,number(section(trace,"rsp[","]"),"unavail"));
        var keyed=line(KEYED);
        assertEquals(4,number(keyed,"BUSY"));
        assertEquals(5,number(keyed.substring(keyed.indexOf(" hello[")),"PREPARING"));
        assertEquals(11,number(line(DROPS),"noPendingOrSequence"));
        var sent=line(SENT);
        assertEquals(1,number(sent,"hello"));
        assertEquals(6,number(sent,"tile"));
        assertEquals(7,number(sent,"retired"));
        assertEquals(9,number(section(sent,"unavail=[","]"),"FAILED"));
        var gates=line(SGATES);
        assertEquals(13,number(gates,"responseBudget"));
        assertEquals(15,number(line(BUSY),"peerCap"));
        assertEquals(17,number(section(line(BUSY),"realOrUnknown[","]"),"notCurrent"));
        var coverage=line(COVERAGE);
        assertEquals(19,number(section(coverage,"causes[","]"),"diskMutated"));
        assertEquals(1,number(section(coverage,"live[","]"),"NO_REAL_DATA"));
        assertEquals(23,number(section(coverage,"beginPreviewEmpty[","]"),"coverage"));
        assertEquals(25,number(coverage,"realRegionPins"));
        assertEquals(21,number(section(line(SESSION),"requestEmpty[","]"),"entryCapacity"));
    }

    @Test public void summaryLineIsBoundedAndOnlyExistsAfterAnEvent() {
        assertEquals("",TerrainPreviewTrace.summaryLine());
        TerrainPreviewTrace.clientAcceptDrop(ClientGate.ENVELOPE);
        TerrainPreviewTrace.clientSend(false);
        var summary=TerrainPreviewTrace.summaryLine();
        assertFalse(summary.isBlank());
        assertFalse(summary.contains("\n"));
        assertTrue(summary.length()<=200);
        assertEquals(1,number(summary,"drops"));
        assertEquals(1,number(summary,"tile"));
        TerrainPreviewTrace.serverSilentDrop(ServerGate.STALE_HELLO);
        TerrainPreviewTrace.serverBusy(BusySite.SESSION_REFUSED);
        TerrainPreviewTrace.serverCoverageVerdict(true,Coverage.REAL_DATA);
        TerrainPreviewTrace.serverCoverageUnknownCause(CoverageUnknownCause.LIVE_EXAMINED);
        TerrainPreviewTrace.serverSessionRequestEmpty(SessionRefusal.QUERY_INFLIGHT);
        summary=TerrainPreviewTrace.summaryLine();
        assertEquals(1,number(summary,"gates"));
        assertEquals(1,number(summary,"busy"));
        assertEquals(1,number(summary,"causes"));
        assertEquals(1,number(summary,"refusals"));
        assertEquals(1,number(section(summary,"live[","]"),"R"));
        assertEquals(0,number(section(summary,"live[","]"),"U"));
    }

    @Test public void stallTicksFollowsProgressAndResetsOnAcceptedTile() {
        TerrainPreviewTrace.clientProgress(100,3,2,1);
        var trace=line(TRACE);
        assertTrue(trace.contains("stallTicks="));
        assertEquals(0,number(tail(trace),"stallTicks"));
        TerrainPreviewTrace.clientResponse(Type.TILE,null,false);
        assertEquals(0,number(tail(line(TRACE)),"stallTicks"));
        TerrainPreviewTrace.clientProgress(140,3,3,1);
        assertEquals(40,number(tail(line(TRACE)),"stallTicks"));
        TerrainPreviewTrace.clientResponse(Type.TILE,null,false);
        assertEquals(0,number(tail(line(TRACE)),"stallTicks"));
        TerrainPreviewTrace.clientProgress(500,0,3,1);
        assertEquals(360,number(tail(line(TRACE)),"stallTicks"));
    }

    @Test public void gaugesStoreTheLatestValueInsteadOfAccumulating() {
        TerrainPreviewTrace.clientProgress(5,1,2,3);
        TerrainPreviewTrace.clientProgress(9,4,5,6);
        var trace=tail(line(TRACE));
        assertEquals(4,number(trace,"pending"));
        assertEquals(5,number(trace,"tiles"));
        assertEquals(6,number(trace,"retired"));
        TerrainPreviewTrace.serverGauges(1,2,3,4);
        TerrainPreviewTrace.serverGauges(7,8,9,10);
        var gauges=section(line(SGATES),"gauges[","]");
        assertEquals(7,number(gauges,"peers"));
        assertEquals(8,number(gauges,"leases"));
        assertEquals(9,number(gauges,"shared"));
        assertEquals(10,number(gauges,"ingress"));
        TerrainPreviewTrace.serverCoveragePending(3,256);
        TerrainPreviewTrace.serverCoveragePending(11,256);
        assertTrue(line(SESSION).contains("coveragePending=11/256"));
        TerrainPreviewTrace.serverAdmissionEntries(2,64);
        TerrainPreviewTrace.serverAdmissionEntries(5,64);
        assertTrue(line(SESSION).contains("admissionEntries=5/64"));
        TerrainPreviewTrace.serverJobs(1,1,1,1,1);
        TerrainPreviewTrace.serverJobs(0,2,0,0,3);
        var jobs=section(line(SESSION),"jobs[","]");
        assertEquals(0,number(jobs,"cov"));
        assertEquals(2,number(jobs,"read"));
        assertEquals(0,number(jobs,"reserve"));
        assertEquals(0,number(jobs,"sample"));
        assertEquals(3,number(jobs,"write"));
    }

    private static void touchEveryCounter() {
        TerrainPreviewTrace.clientSend(false);TerrainPreviewTrace.clientSend(true);TerrainPreviewTrace.clientRetry();
        TerrainPreviewTrace.clientResponse(Type.TILE,null,false);
        TerrainPreviewTrace.clientResponse(Type.RETIRED,null,false);
        TerrainPreviewTrace.clientResponse(Type.WELCOME,null,false);
        TerrainPreviewTrace.clientResponse(Type.UNAVAILABLE,Reason.BUSY,false);
        TerrainPreviewTrace.clientResponse(Type.UNAVAILABLE,Reason.BUSY,true);
        TerrainPreviewTrace.clientClockGate(ClockGate.NULL_RECEIPT);
        TerrainPreviewTrace.clientClockGate(ClockGate.STALE_TICKET);
        TerrainPreviewTrace.clientInboxNullReceipt();TerrainPreviewTrace.clientTickIdle();
        TerrainPreviewTrace.clientRejected(true);TerrainPreviewTrace.clientRejected(false);
        TerrainPreviewTrace.clientProgress(1,1,1,1);
        for(var gate:ClientGate.values())TerrainPreviewTrace.clientAcceptDrop(gate);
        TerrainPreviewTrace.serverIngress(false,false);
        TerrainPreviewTrace.serverIngress(true,false);
        for(var gate:ServerGate.values())TerrainPreviewTrace.serverSilentDrop(gate);
        for(var site:BusySite.values())TerrainPreviewTrace.serverBusy(site);
        for(var type:Type.values())TerrainPreviewTrace.serverSent(type,type==Type.UNAVAILABLE?Reason.FAILED:null);
        TerrainPreviewTrace.serverHello();
        for(var cause:RealOrUnknownCause.values())TerrainPreviewTrace.serverRealOrUnknown(cause);
        for(var verdict:Coverage.values())TerrainPreviewTrace.serverCoverageVerdict(true,verdict);
        for(var cause:CoverageUnknownCause.values())TerrainPreviewTrace.serverCoverageUnknownCause(cause);
        for(var refusal:SessionRefusal.values())TerrainPreviewTrace.serverSessionRequestEmpty(refusal);
        TerrainPreviewTrace.serverBeginPreviewEmpty(true,true);
        TerrainPreviewTrace.serverRealRegionPins(3);
        TerrainPreviewTrace.serverGauges(1,1,1,1);
        TerrainPreviewTrace.serverCoveragePending(1,1);
        TerrainPreviewTrace.serverAdmissionEntries(1,1);
        TerrainPreviewTrace.serverJobs(1,1,1,1,1);
        TerrainPreviewTrace.serverHandleDisabled();
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
    private static String tail(String text) {
        int cut=text.lastIndexOf(']');
        return cut<0?text:text.substring(cut+1);
    }
    /** Reads a label out of a report line, a section of it, or a group prefix naming that line. */
    private static long number(String context,String label) {
        var text=context.endsWith(":")?line(context):context;
        var matcher=Pattern.compile("(?:^|[\\s\\[,])"+Pattern.quote(label)+"=(-?\\d+)").matcher(text);
        return matcher.find()?Long.parseLong(matcher.group(1)):0;
    }
    private static long number(String label) {
        for(var line:TerrainPreviewTrace.reportLines()) {
            var value=number(line,label);
            if(value!=0)return value;
            if(line.contains(label+'='))return 0;
        }
        return 0;
    }
}
