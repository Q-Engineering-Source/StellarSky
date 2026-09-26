package stellarium.world.ring.terrain;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;

/**
 * Observation-only fixed-size counters for the terrain preview receive/transport chain.
 * <p>Every counter is one slot of a single atomic array, so netty I/O, the client main thread and the
 * server thread may all increment concurrently while the client main thread reads a report. No method
 * here is consulted by a production decision, gate, budget or wire path.</p>
 */
public final class TerrainPreviewTrace {
    public enum ClientGate { ENVELOPE, WELCOME_CONFLICT, EPOCH_OR_NULL_KEY, NO_PENDING_OR_SEQUENCE, TILE_IDENTITY }
    public enum ClockGate { NULL_RECEIPT, STALE_TICKET }
    public enum BusySite { PEER_CAP, RANGE_OR_LEASE_CAP, SHARED_CAP, SESSION_REFUSED }
    public enum ServerGate { DIM_OR_CONNECTION, STALE_HELLO, LEASE_GATE, RESPONSE_BUDGET, LEASE_EXPIRED }
    public enum RealOrUnknownCause { NO_PUBLICATION, SESSION_UNUSABLE, NOT_CURRENT }
    public enum CoverageUnknownCause { LIVE_EXAMINED, LIVE_PENDING_WRITES, DISK_DIRECTORY, DISK_REGION_BUDGET, DISK_MUTATED,
                                        DISK_UNSTABLE, REVISION_CHANGED, CLOSED, QUERY_CAPACITY }
    public enum SessionRefusal { ENTRY_CAPACITY, QUERY_INFLIGHT }
    private static final int C_SENT_TILE=0,C_SENT_RELEASE=1,C_SENT_RETRY=2;
    private static final int C_RSP_TILE=3,C_RSP_RETIRED=4,C_RSP_WELCOME=5,C_RSP_UNAVAIL_KEYED=6,C_RSP_UNAVAIL_HELLO=7;
    private static final int C_ACCEPT=8,C_KEYED_REASON=13,C_HELLO_REASON=18;
    private static final int C_SRV_HELLO=23,C_SRV_TILE=24,C_SRV_RETIRED=25,C_SRV_REASON=26;
    private static final int C_GATE=31,C_BUSY=36,C_REAL_OR_UNKNOWN=40,C_LIVE_VERDICT=43,C_COVERAGE_CAUSE=46;
    private static final int C_REFUSAL=55,C_BEGIN_EMPTY_COVERAGE=57,C_BEGIN_EMPTY_INFLIGHT=58;
    private static final int C_INGRESS_STOPPED=59,C_INGRESS_FULL=60;
    private static final int C_CLOCK_NULL_RECEIPT=61,C_CLOCK_STALE_TICKET=62,C_INBOX_NULL_RECEIPT=63,C_TICK_IDLE=64;
    private static final int C_REAL_REGION_PINS=65,C_HANDLE_DISABLED=66,C_REJECT_RETIRED=67,C_REJECT_REAL_OR_UNKNOWN=68;
    private static final int C_SIZE=69;
    private static final AtomicLongArray COUNTS=new AtomicLongArray(C_SIZE);
    private static final AtomicLong CLIENT_TICK=new AtomicLong(),CLIENT_LAST_ACCEPT_TICK=new AtomicLong(-1);
    private static final AtomicLong CLIENT_PENDING=new AtomicLong(),CLIENT_TILES=new AtomicLong(),CLIENT_RETIRED=new AtomicLong();
    private static final AtomicLong SERVER_PEERS=new AtomicLong(),SERVER_LEASES=new AtomicLong();
    private static final AtomicLong SERVER_SHARED=new AtomicLong(),SERVER_INGRESS=new AtomicLong();
    private static final AtomicLong COVERAGE_PENDING=new AtomicLong(),COVERAGE_CAPACITY=new AtomicLong();
    private static final AtomicLong ADMISSION_ENTRIES=new AtomicLong(),ADMISSION_CAPACITY=new AtomicLong();
    private static final AtomicLong JOB_COVERAGE=new AtomicLong(),JOB_READ=new AtomicLong(),JOB_RESERVE=new AtomicLong();
    private static final AtomicLong JOB_SAMPLE=new AtomicLong(),JOB_WRITE=new AtomicLong();
    private static final int SERVER_LINE_LIMIT=16,SERVER_CHAR_BUDGET=200;
    /** Every slot the server report reads; used only to tell a quiet server from an observed one. */
    private static final int[] SERVER_SLOTS={C_SRV_HELLO,C_SRV_TILE,C_SRV_RETIRED,C_SRV_REASON,C_SRV_REASON+1,
            C_SRV_REASON+2,C_SRV_REASON+3,C_SRV_REASON+4,C_INGRESS_STOPPED,C_INGRESS_FULL,C_GATE,C_GATE+1,C_GATE+2,
            C_GATE+3,C_GATE+4,C_BUSY,C_BUSY+1,C_BUSY+2,C_BUSY+3,C_REAL_OR_UNKNOWN,C_REAL_OR_UNKNOWN+1,
            C_REAL_OR_UNKNOWN+2,C_LIVE_VERDICT,C_LIVE_VERDICT+1,C_LIVE_VERDICT+2,C_COVERAGE_CAUSE,C_COVERAGE_CAUSE+1,
            C_COVERAGE_CAUSE+2,C_COVERAGE_CAUSE+3,C_COVERAGE_CAUSE+4,C_COVERAGE_CAUSE+5,C_COVERAGE_CAUSE+6,
            C_COVERAGE_CAUSE+7,C_COVERAGE_CAUSE+8,C_BEGIN_EMPTY_COVERAGE,C_BEGIN_EMPTY_INFLIGHT,C_REAL_REGION_PINS,
            C_REFUSAL,C_REFUSAL+1,C_HANDLE_DISABLED};
    private static final AtomicLong[] SERVER_GAUGES={SERVER_PEERS,SERVER_LEASES,SERVER_SHARED,SERVER_INGRESS,
            COVERAGE_PENDING,COVERAGE_CAPACITY,ADMISSION_ENTRIES,ADMISSION_CAPACITY,JOB_COVERAGE,JOB_READ,JOB_RESERVE,
            JOB_SAMPLE,JOB_WRITE};
    /** SessionRefusal.QUERY_INFLIGHT has no production call site yet; the report says so instead of printing 0. */
    private static final String UNWIRED_QUERY_INFLIGHT="queryInflight=unsupported";
    private TerrainPreviewTrace() {}

    // ---- client side ----
    public static void clientClockGate(ClockGate gate) {COUNTS.incrementAndGet(C_CLOCK_NULL_RECEIPT+gate.ordinal());}
    public static void clientInboxNullReceipt() {COUNTS.incrementAndGet(C_INBOX_NULL_RECEIPT);}
    public static void clientAcceptDrop(ClientGate gate) {COUNTS.incrementAndGet(C_ACCEPT+gate.ordinal());}
    public static void clientResponse(PreviewResponsePacket.Type type,PreviewResponsePacket.Reason reason,boolean helloLevel) {
        switch(type) {
            case TILE -> {COUNTS.incrementAndGet(C_RSP_TILE);CLIENT_LAST_ACCEPT_TICK.set(CLIENT_TICK.get());}
            case RETIRED -> COUNTS.incrementAndGet(C_RSP_RETIRED);
            case WELCOME -> COUNTS.incrementAndGet(C_RSP_WELCOME);
            case UNAVAILABLE -> {
                if(reason==null)return;
                COUNTS.incrementAndGet((helloLevel?C_HELLO_REASON:C_KEYED_REASON)+reason.ordinal());
                COUNTS.incrementAndGet(helloLevel?C_RSP_UNAVAIL_HELLO:C_RSP_UNAVAIL_KEYED);
            }
        }
    }
    public static void clientSend(boolean release) {COUNTS.incrementAndGet(release?C_SENT_RELEASE:C_SENT_TILE);}
    public static void clientRetry() {COUNTS.incrementAndGet(C_SENT_RETRY);}
    /** Demand refinement rejected a key: retirement versus a REAL_OR_UNKNOWN unavailable. */
    public static void clientRejected(boolean retired) {COUNTS.incrementAndGet(retired?C_REJECT_RETIRED:C_REJECT_REAL_OR_UNKNOWN);}
    public static void clientProgress(long tick,int pending,int tiles,int retired) {
        CLIENT_TICK.set(tick);CLIENT_PENDING.set(pending);CLIENT_TILES.set(tiles);CLIENT_RETIRED.set(retired);
    }
    public static void clientTickIdle() {COUNTS.incrementAndGet(C_TICK_IDLE);}

    // ---- server side ----
    public static void serverIngress(boolean running,boolean accepted) {
        if(!running)COUNTS.incrementAndGet(C_INGRESS_STOPPED);
        else if(!accepted)COUNTS.incrementAndGet(C_INGRESS_FULL);
    }
    public static void serverSilentDrop(ServerGate gate) {COUNTS.incrementAndGet(C_GATE+gate.ordinal());}
    public static void serverLeaseExpired() {serverSilentDrop(ServerGate.LEASE_EXPIRED);}
    public static void serverBusy(BusySite site) {COUNTS.incrementAndGet(C_BUSY+site.ordinal());}
    public static void serverSent(PreviewResponsePacket.Type type,PreviewResponsePacket.Reason reason) {
        switch(type) {
            case WELCOME -> COUNTS.incrementAndGet(C_SRV_HELLO);
            case TILE -> COUNTS.incrementAndGet(C_SRV_TILE);
            case RETIRED -> COUNTS.incrementAndGet(C_SRV_RETIRED);
            case UNAVAILABLE -> {if(reason!=null)COUNTS.incrementAndGet(C_SRV_REASON+reason.ordinal());}
        }
    }
    public static void serverHello() {COUNTS.incrementAndGet(C_SRV_HELLO);}
    public static void serverRealOrUnknown(RealOrUnknownCause cause) {COUNTS.incrementAndGet(C_REAL_OR_UNKNOWN+cause.ordinal());}
    public static void serverCoverageVerdict(boolean live,SeedPreviewAdmission.Coverage verdict) {
        // Non-live verdicts have no report field; every current caller passes true.
        if(live&&verdict!=null)COUNTS.incrementAndGet(C_LIVE_VERDICT+verdict.ordinal());
    }
    public static void serverCoverageUnknownCause(CoverageUnknownCause cause) {COUNTS.incrementAndGet(C_COVERAGE_CAUSE+cause.ordinal());}
    public static void serverSessionRequestEmpty(SessionRefusal refusal) {COUNTS.incrementAndGet(C_REFUSAL+refusal.ordinal());}
    public static void serverBeginPreviewEmpty(boolean coverageNotReal,boolean queryInflight) {
        if(coverageNotReal)COUNTS.incrementAndGet(C_BEGIN_EMPTY_COVERAGE);
        if(queryInflight)COUNTS.incrementAndGet(C_BEGIN_EMPTY_INFLIGHT);
    }
    public static void serverRealRegionPins(int count) {if(count>0)COUNTS.addAndGet(C_REAL_REGION_PINS,count);}
    public static void serverGauges(int peers,int leases,int shared,int ingress) {
        SERVER_PEERS.set(peers);SERVER_LEASES.set(leases);SERVER_SHARED.set(shared);SERVER_INGRESS.set(ingress);
    }
    public static void serverCoveragePending(int pending,int capacity) {
        COVERAGE_PENDING.set(pending);COVERAGE_CAPACITY.set(capacity);
    }
    public static void serverAdmissionEntries(int entries,int capacity) {
        ADMISSION_ENTRIES.set(entries);ADMISSION_CAPACITY.set(capacity);
    }
    public static void serverJobs(int coverage,int read,int reserve,int sample,int write) {
        JOB_COVERAGE.set(coverage);JOB_READ.set(read);JOB_RESERVE.set(reserve);JOB_SAMPLE.set(sample);JOB_WRITE.set(write);
    }
    public static void serverHandleDisabled() {COUNTS.incrementAndGet(C_HANDLE_DISABLED);}

    // ---- report ----
    /**
     * Server-process report behind the server-side {@code /ssterrain status} command: at most 16 lines of at
     * most 200 characters, read from this JVM only. Only server slots are rendered, so a client can never
     * produce this text.
     * <p>Counters are cumulative process-local totals (since process start or the last {@link #reset()}), gauges
     * are the latest sampled values, and no value is an interval delta. Zero-valued counters are omitted; the
     * nine coverage causes are split across lines instead of being cut, and slots with no production call site
     * are printed as {@code unsupported} rather than as a fabricated zero. Nothing here is read by a decision,
     * and nothing here mutates state.</p>
     */
    public static List<String> serverReportLines() {
        var lines=new ArrayList<String>(SERVER_LINE_LIMIT);
        if(!serverHasEvents())lines.add("terrain server: no events recorded (process-local snapshot)");
        group(lines,"terrain server sent[","terrain server sent continued[",atoms(
                entry("hello",count(C_SRV_HELLO)),entry("tile",count(C_SRV_TILE)),
                entry("retired",count(C_SRV_RETIRED))));
        group(lines,"terrain server unavail[","terrain server unavail continued[",atoms(
                entry("PREPARING",count(C_SRV_REASON)),entry("UNSUPPORTED",count(C_SRV_REASON+1)),
                entry("BUSY",count(C_SRV_REASON+2)),entry("REAL_OR_UNKNOWN",count(C_SRV_REASON+3)),
                entry("FAILED",count(C_SRV_REASON+4))));
        group(lines,"terrain server gates[","terrain server gates continued[",atoms(
                entry("ingressStopped",count(C_INGRESS_STOPPED)),entry("ingressFull",count(C_INGRESS_FULL)),
                entry("dimOrConnection",count(C_GATE)),entry("staleHello",count(C_GATE+1)),
                entry("leaseGate",count(C_GATE+2)),entry("responseBudget",count(C_GATE+3)),
                entry("leaseExpired",count(C_GATE+4))));
        group(lines,"terrain server gauges[","terrain server gauges continued[",List.of(
                "peers="+SERVER_PEERS.get(),"leases="+SERVER_LEASES.get(),"shared="+SERVER_SHARED.get(),
                "ingress="+SERVER_INGRESS.get()));
        group(lines,"terrain server busy[","terrain server busy continued[",atoms(
                entry("peerCap",count(C_BUSY)),entry("rangeOrLeaseCap",count(C_BUSY+1)),
                entry("sharedCap",count(C_BUSY+2)),entry("sessionRefused",count(C_BUSY+3))));
        group(lines,"terrain server real-or-unknown[","terrain server real-or-unknown continued[",atoms(
                entry("noPublication",count(C_REAL_OR_UNKNOWN)),entry("sessionUnusable",count(C_REAL_OR_UNKNOWN+1)),
                entry("notCurrent",count(C_REAL_OR_UNKNOWN+2))));
        group(lines,"terrain coverage live[","terrain coverage live continued[",atoms(
                entry("UNKNOWN",count(C_LIVE_VERDICT)),entry("NO_REAL_DATA",count(C_LIVE_VERDICT+1)),
                entry("REAL_DATA",count(C_LIVE_VERDICT+2))));
        // Nine causes never fit one line at realistic magnitudes: split the group instead of cutting it.
        group(lines,"terrain coverage unknown causes[","terrain coverage causes continued[",atoms(
                entry("examined",count(C_COVERAGE_CAUSE)),entry("pendingWrites",count(C_COVERAGE_CAUSE+1)),
                entry("diskDir",count(C_COVERAGE_CAUSE+2)),entry("diskRegionBudget",count(C_COVERAGE_CAUSE+3)),
                entry("diskMutated",count(C_COVERAGE_CAUSE+4)),entry("diskUnstable",count(C_COVERAGE_CAUSE+5)),
                entry("revision",count(C_COVERAGE_CAUSE+6)),entry("closed",count(C_COVERAGE_CAUSE+7)),
                entry("queryCapacity",count(C_COVERAGE_CAUSE+8))));
        groupSpace(lines,"terrain coverage ","terrain coverage continued ",atoms(
                beginPreviewEmpty(),entry("realRegionPins",count(C_REAL_REGION_PINS))));
        group(lines,"terrain session requestEmpty[","terrain session requestEmpty continued[",atoms(
                entry("entryCapacity",count(C_REFUSAL)),UNWIRED_QUERY_INFLIGHT));
        group(lines,"terrain session jobs[","terrain session jobs continued[",atoms(
                entry("cov",JOB_COVERAGE.get()),entry("read",JOB_READ.get()),entry("reserve",JOB_RESERVE.get()),
                entry("sample",JOB_SAMPLE.get()),entry("write",JOB_WRITE.get())));
        group(lines,"terrain session gauges[","terrain session gauges continued[",List.of(
                "coveragePending="+COVERAGE_PENDING.get()+"/"+COVERAGE_CAPACITY.get(),
                "admissionEntries="+ADMISSION_ENTRIES.get()+"/"+ADMISSION_CAPACITY.get()));
        groupSpace(lines,"terrain server unwired ","terrain server unwired continued ",
                List.of("handleDisabled=unsupported","nonLiveCoverageVerdict=unsupported"));
        lines.add("terrain server scope: process-local read-only snapshot, counters cumulative since start/reset "
                +"and gauges latest; one static per process so worlds/sessions aggregate, no per-world split");
        return lines;
    }
    public static List<String> reportLines() {
        var lines=new ArrayList<String>(10);
        lines.add(fit("terrain trace: sent["+join(entry("tile",count(C_SENT_TILE)),entry("release",count(C_SENT_RELEASE)),
                entry("retry",count(C_SENT_RETRY)))+"] rsp["+join(entry("tile",count(C_RSP_TILE)),entry("retired",count(C_RSP_RETIRED)),
                entry("welcome",count(C_RSP_WELCOME)),entry("unavail",count(C_RSP_UNAVAIL_KEYED)))+"] pending="+CLIENT_PENDING.get()
                +" tiles="+CLIENT_TILES.get()+" retired="+CLIENT_RETIRED.get()+" stallTicks="+stallTicks()));
        var keyed=space(entry("PREPARING",count(C_KEYED_REASON)),entry("UNSUPPORTED",count(C_KEYED_REASON+1)),
                entry("BUSY",count(C_KEYED_REASON+2)),entry("REAL_OR_UNKNOWN",count(C_KEYED_REASON+3)),entry("FAILED",count(C_KEYED_REASON+4)));
        var hello=space(entry("PREPARING",count(C_HELLO_REASON)),entry("UNSUPPORTED",count(C_HELLO_REASON+1)),
                entry("BUSY",count(C_HELLO_REASON+2)),entry("REAL_OR_UNKNOWN",count(C_HELLO_REASON+3)),entry("FAILED",count(C_HELLO_REASON+4)));
        lines.add(fit("terrain unavail keyed:"+(keyed.isEmpty()?"":" "+keyed)+(hello.isEmpty()?"":" hello["+hello+"]")));
        lines.add(fit("terrain client gates:"+spaced(entry("nettyNullReceipt",count(C_INBOX_NULL_RECEIPT)),
                entry("clockNullReceipt",count(C_CLOCK_NULL_RECEIPT)),entry("clockStaleTicket",count(C_CLOCK_STALE_TICKET)),
                entry("inboxOverflow",PreviewClientInbox.overflowCount()),entry("idleTicks",count(C_TICK_IDLE)))));
        lines.add(fit("terrain accept drops:"+spaced(entry("envelope",count(C_ACCEPT)),entry("welcomeConflict",count(C_ACCEPT+1)),
                entry("epoch",count(C_ACCEPT+2)),entry("noPendingOrSequence",count(C_ACCEPT+3)),entry("tileIdentity",count(C_ACCEPT+4)))
                +spaced(entry("rejectRetired",count(C_REJECT_RETIRED)),entry("rejectRealOrUnknown",count(C_REJECT_REAL_OR_UNKNOWN)))));
        lines.add(fit("terrain server sent:"+spaced(entry("hello",count(C_SRV_HELLO)),entry("tile",count(C_SRV_TILE)),
                entry("retired",count(C_SRV_RETIRED)))+" unavail=["+join(entry("PREPARING",count(C_SRV_REASON)),
                entry("UNSUPPORTED",count(C_SRV_REASON+1)),entry("BUSY",count(C_SRV_REASON+2)),
                entry("REAL_OR_UNKNOWN",count(C_SRV_REASON+3)),entry("FAILED",count(C_SRV_REASON+4)))+"]"));
        lines.add(fit("terrain server gates:"+spaced(entry("ingressStopped",count(C_INGRESS_STOPPED)),entry("ingressFull",count(C_INGRESS_FULL)),
                entry("dimOrConnection",count(C_GATE)),entry("staleHello",count(C_GATE+1)),entry("leaseGate",count(C_GATE+2)),
                entry("responseBudget",count(C_GATE+3)),entry("leaseExpired",count(C_GATE+4)))
                +" gauges[peers="+SERVER_PEERS.get()+",leases="+SERVER_LEASES.get()+",shared="+SERVER_SHARED.get()
                +",ingress="+SERVER_INGRESS.get()+"]"));
        lines.add(fit("terrain server busy:"+spaced(entry("peerCap",count(C_BUSY)),entry("rangeOrLeaseCap",count(C_BUSY+1)),
                entry("sharedCap",count(C_BUSY+2)),entry("sessionRefused",count(C_BUSY+3)))+" realOrUnknown["
                +join(entry("noPublication",count(C_REAL_OR_UNKNOWN)),entry("sessionUnusable",count(C_REAL_OR_UNKNOWN+1)),
                entry("notCurrent",count(C_REAL_OR_UNKNOWN+2)))+"]"));
        lines.add(fit("terrain coverage: live["+join(entry("UNKNOWN",count(C_LIVE_VERDICT)),entry("NO_REAL_DATA",count(C_LIVE_VERDICT+1)),
                entry("REAL_DATA",count(C_LIVE_VERDICT+2)))+"] causes["+join(entry("examined",count(C_COVERAGE_CAUSE)),
                entry("pendingWrites",count(C_COVERAGE_CAUSE+1)),entry("diskDir",count(C_COVERAGE_CAUSE+2)),
                entry("diskRegionBudget",count(C_COVERAGE_CAUSE+3)),entry("diskMutated",count(C_COVERAGE_CAUSE+4)),
                entry("diskUnstable",count(C_COVERAGE_CAUSE+5)),entry("revision",count(C_COVERAGE_CAUSE+6)),
                entry("closed",count(C_COVERAGE_CAUSE+7)),entry("queryCapacity",count(C_COVERAGE_CAUSE+8)))
                +"] beginPreviewEmpty["+join(entry("coverage",count(C_BEGIN_EMPTY_COVERAGE)),entry("inflight",count(C_BEGIN_EMPTY_INFLIGHT)))
                +"] realRegionPins="+count(C_REAL_REGION_PINS)));
        lines.add(fit("terrain session: requestEmpty["+join(entry("entryCapacity",count(C_REFUSAL)),entry("queryInflight",count(C_REFUSAL+1)))
                +"] jobs[cov="+JOB_COVERAGE.get()+",read="+JOB_READ.get()+",reserve="+JOB_RESERVE.get()+",sample="+JOB_SAMPLE.get()
                +",write="+JOB_WRITE.get()+"] coveragePending="+COVERAGE_PENDING.get()+"/"+COVERAGE_CAPACITY.get()
                +" admissionEntries="+ADMISSION_ENTRIES.get()+"/"+ADMISSION_CAPACITY.get()));
        lines.add("terrain trace scope: client main thread + netty + server thread; server-side counters are process-local (same JVM only)");
        return lines;
    }
    public static String summaryLine() {
        if(total()==0)return "";
        return fit("terrain trace: tile="+count(C_SENT_TILE)+" unavail["+join(entry("PREPARING",count(C_KEYED_REASON)),
                entry("UNSUPPORTED",count(C_KEYED_REASON+1)),entry("BUSY",count(C_KEYED_REASON+2)),
                entry("REAL_OR_UNKNOWN",count(C_KEYED_REASON+3)),entry("FAILED",count(C_KEYED_REASON+4)))+"] drops="+acceptDrops()
                +" gates="+gateTotal()+" busy="+busyTotal()+" live[U="+count(C_LIVE_VERDICT)+",N="+count(C_LIVE_VERDICT+1)
                +",R="+count(C_LIVE_VERDICT+2)+"] causes="+coverageCauses()+" refusals="+refusalTotal());
    }
    public static void reset() {
        for(int i=0;i<C_SIZE;i++)COUNTS.set(i,0);
        CLIENT_TICK.set(0);CLIENT_LAST_ACCEPT_TICK.set(-1);CLIENT_PENDING.set(0);CLIENT_TILES.set(0);CLIENT_RETIRED.set(0);
        SERVER_PEERS.set(0);SERVER_LEASES.set(0);SERVER_SHARED.set(0);SERVER_INGRESS.set(0);
        COVERAGE_PENDING.set(0);COVERAGE_CAPACITY.set(0);ADMISSION_ENTRIES.set(0);ADMISSION_CAPACITY.set(0);
        JOB_COVERAGE.set(0);JOB_READ.set(0);JOB_RESERVE.set(0);JOB_SAMPLE.set(0);JOB_WRITE.set(0);
    }

    private static long count(int index) {return COUNTS.get(index);}
    private static long stallTicks() {
        long last=CLIENT_LAST_ACCEPT_TICK.get();return last<0?0:Math.max(0,CLIENT_TICK.get()-last);
    }
    private static long acceptDrops() {return sum(C_ACCEPT,5);}
    private static long gateTotal() {
        return count(C_INGRESS_STOPPED)+count(C_INGRESS_FULL)+sum(C_GATE,5);
    }
    private static long busyTotal() {return sum(C_BUSY,4);}
    private static long coverageCauses() {return sum(C_COVERAGE_CAUSE,9);}
    private static long refusalTotal() {return sum(C_REFUSAL,2);}
    private static long sum(int base,int length) {
        long total=0;for(int i=0;i<length;i++)total+=count(base+i);return total;
    }
    private static long total() {
        long total=sum(C_ACCEPT,5)+sum(C_KEYED_REASON,5)+sum(C_HELLO_REASON,5)+sum(C_SRV_REASON,5)+sum(C_GATE,5)
                +sum(C_BUSY,4)+sum(C_REAL_OR_UNKNOWN,3)+sum(C_LIVE_VERDICT,3)+sum(C_COVERAGE_CAUSE,9)+sum(C_REFUSAL,2);
        for(int i : new int[]{C_SENT_TILE,C_SENT_RELEASE,C_SENT_RETRY,C_RSP_TILE,C_RSP_RETIRED,C_RSP_WELCOME,
                C_RSP_UNAVAIL_KEYED,C_RSP_UNAVAIL_HELLO,C_SRV_HELLO,C_SRV_TILE,C_SRV_RETIRED,C_BEGIN_EMPTY_COVERAGE,
                C_BEGIN_EMPTY_INFLIGHT,C_INGRESS_STOPPED,C_INGRESS_FULL,C_CLOCK_NULL_RECEIPT,C_CLOCK_STALE_TICKET,
                C_INBOX_NULL_RECEIPT,C_TICK_IDLE,C_REAL_REGION_PINS,C_HANDLE_DISABLED,C_REJECT_RETIRED,
                C_REJECT_REAL_OR_UNKNOWN}) total+=count(i);
        return total;
    }
    /** Zero-valued counters are omitted so a line stays inside the report budget; gauges are always shown. */
    private static String entry(String label,long value) {return value==0?null:label+'='+value;}
    private static String join(String... entries) {return separated(entries,',');}
    private static String space(String... entries) {return separated(entries,' ');}
    private static String spaced(String... entries) {
        var body=separated(entries,' ');return body.isEmpty()?"":" "+body;
    }
    private static String separated(String[] entries,char separator) {
        var out=new StringBuilder();
        for(var value:entries) {
            if(value==null)continue;
            if(out.length()>0)out.append(separator);
            out.append(value);
        }
        return out.toString();
    }
    /** Values are never invented: an overlong line loses trailing entries and is marked with "...". */
    private static String fit(String line) {
        if(line.length()<=200)return line;
        int cut=line.lastIndexOf(',',197);
        return line.substring(0,cut<1?197:cut)+"...";
    }
    /** True when any server-side counter or gauge was touched; a quiet server needs an explicit zero state. */
    private static boolean serverHasEvents() {
        for(int index:SERVER_SLOTS)if(count(index)>0)return true;
        for(var gauge:SERVER_GAUGES)if(gauge.get()>0)return true;
        return false;
    }
    /** {@code beginPreviewEmpty[...]} with both sub-slots, or null when no job has finished empty. */
    private static String beginPreviewEmpty() {
        var body=join(entry("coverage",count(C_BEGIN_EMPTY_COVERAGE)),entry("inflight",count(C_BEGIN_EMPTY_INFLIGHT)));
        return body.isEmpty()?null:"beginPreviewEmpty["+body+']';
    }
    private static List<String> atoms(String... entries) {
        var list=new ArrayList<String>(entries.length);
        for(var value:entries)if(value!=null)list.add(value);
        return list;
    }
    /** Comma-separated bracket group, e.g. {@code head[a=1,b=2]}. */
    private static void group(List<String> lines,String head,String continued,List<String> atoms) {
        group(lines,head,continued,',',atoms,"]");
    }
    /** Space-separated group without brackets, e.g. {@code head a=1 b=2}. */
    private static void groupSpace(List<String> lines,String head,String continued,List<String> atoms) {
        group(lines,head,continued,' ',atoms,"");
    }
    /**
     * Packs complete atoms into lines of at most 200 characters. A group may span lines, but an atom is never
     * cut, so an overlong group loses nothing: it continues on the next line under {@code continued} instead.
     */
    private static void group(List<String> lines,String head,String continued,char separator,List<String> atoms,
            String close) {
        if(atoms.isEmpty())return;
        var prefix=head;
        var line=new StringBuilder(prefix);
        for(var atom:atoms) {
            var addition=line.length()>prefix.length()?separator+atom:atom;
            if(line.length()+addition.length()+close.length()>SERVER_CHAR_BUDGET&&line.length()>prefix.length()) {
                lines.add(line.append(close).toString());
                prefix=continued;line=new StringBuilder(prefix);addition=atom;
            }
            line.append(addition);
        }
        lines.add(line.append(close).toString());
    }
}
