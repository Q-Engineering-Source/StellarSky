package stellarium.world.ring;

import java.util.Objects;

/**
 * Client-only lifecycle state expressed without client-only types so the
 * common packet handler remains safe to load on a dedicated server.
 */
public final class RingworldClockClientState {
    private static final RingworldClockSession SESSION = new RingworldClockSession();
    private static volatile Context committedContext;

    private RingworldClockClientState() {
    }

    public static synchronized void onClientConnect(Object handler, Object connection) {
        Object validatedHandler = Objects.requireNonNull(handler, "handler");
        Object validatedConnection = Objects.requireNonNull(connection, "connection");
        SESSION.beginConnection(validatedHandler, validatedConnection);
        committedContext = null;
    }

    public static synchronized void onClientDisconnect(Object handler, Object connection) {
        if (SESSION.matches(handler, connection)) {
            committedContext = null;
            SESSION.endConnection(handler, connection);
        }
    }

    public static synchronized void onClientSceneCommitted(Object world, Object scene) {
        Object validatedWorld = Objects.requireNonNull(world, "world");
        Object validatedScene = Objects.requireNonNull(scene, "scene");
        SESSION.invalidateContext();
        committedContext = new Context(validatedWorld, validatedScene);
    }

    public static synchronized void onClientWorldUnloaded(Object world) {
        Context current = committedContext;
        if (current != null && current.world() == world) {
            committedContext = null;
        }
    }

    public static synchronized RingworldClockSession.Ticket capture(Object handler) {
        return SESSION.capture(handler);
    }

    /** Captured on Netty without touching Minecraft World state. */
    public static synchronized Receipt captureReceipt(Object handler) {
        RingworldClockSession.Ticket ticket = SESSION.capture(handler);
        Context context = committedContext;
        return ticket == null || context == null ? null : new Receipt(ticket, context);
    }

    public static synchronized boolean isCurrent(RingworldClockSession.Ticket ticket) {
        return SESSION.isCurrent(ticket);
    }

    public static synchronized boolean isCurrent(Receipt receipt) {
        return receipt != null && committedContext == receipt.context() && SESSION.isCurrent(receipt.ticket());
    }

    public record Context(Object world, Object scene) {
    }

    public record Receipt(RingworldClockSession.Ticket ticket, Context context) {
    }
}
