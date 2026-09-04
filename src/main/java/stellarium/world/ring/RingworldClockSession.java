package stellarium.world.ring;

import java.util.Objects;

/**
 * Client transport identity gate. The source handler and the concrete network
 * connection are both compared by identity so an old channel cannot inherit a
 * newer session merely because its delayed work observes a newer epoch.
 */
public final class RingworldClockSession {
    private Object activeHandler;
    private Object activeConnection;
    private long epoch;

    public synchronized void beginConnection(Object handler, Object connection) {
        Object validatedHandler = Objects.requireNonNull(handler, "handler");
        Object validatedConnection = Objects.requireNonNull(connection, "connection");
        long nextEpoch = Math.incrementExact(epoch);
        activeHandler = validatedHandler;
        activeConnection = validatedConnection;
        epoch = nextEpoch;
    }

    public synchronized Ticket capture(Object handler) {
        if (activeHandler == null || handler == null || handler != activeHandler) {
            return null;
        }
        return new Ticket(handler, activeConnection, epoch);
    }

    public synchronized boolean isCurrent(Ticket ticket) {
        return activeHandler != null
                && activeConnection != null
                && ticket != null
                && ticket.handler() == activeHandler
                && ticket.connection() == activeConnection
                && ticket.epoch() == epoch;
    }

    public synchronized void endConnection(Object handler, Object connection) {
        if (handler == activeHandler && connection == activeConnection) {
            activeHandler = null;
            activeConnection = null;
            epoch = Math.incrementExact(epoch);
        }
    }

    public synchronized boolean matches(Object handler, Object connection) {
        return handler != null && handler == activeHandler && connection != null && connection == activeConnection;
    }

    public synchronized void invalidateContext() {
        epoch = Math.incrementExact(epoch);
    }

    public record Ticket(Object handler, Object connection, long epoch) {
    }
}
