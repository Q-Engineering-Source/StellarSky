package stellarium.world.ring.terrain;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;
import stellarium.world.ring.RingworldClockClientState;

/** Network threads never schedule unbounded client tasks or touch the client world. */
public final class PreviewClientInbox {
    private static final ArrayBlockingQueue<Entry> QUEUE=new ArrayBlockingQueue<>(64);
    private static final AtomicLong OVERFLOW=new AtomicLong();
    private PreviewClientInbox() {}
    public static void offer(RingworldClockClientState.Receipt receipt,PreviewResponsePacket packet) {
        if(!QUEUE.offer(new Entry(receipt,packet))) OVERFLOW.incrementAndGet();
    }
    public static Entry poll() { return QUEUE.poll(); }
    public static void clear() { QUEUE.clear(); }
    public static long overflowCount() { return OVERFLOW.get(); }
    public record Entry(RingworldClockClientState.Receipt receipt,PreviewResponsePacket packet) {}
}
