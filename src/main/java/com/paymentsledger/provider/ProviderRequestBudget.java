package com.paymentsledger.provider;

/** A synchronous query batch shares its HTTP budget across ports and pagination. */
public final class ProviderRequestBudget {
    private static final ThreadLocal<Counter> CURRENT = new ThreadLocal<>();
    private ProviderRequestBudget() {}

    public static Scope openOrJoin(int maximumRequests) {
        if (maximumRequests < 1) throw new IllegalArgumentException("Request budget must be positive");
        boolean owner = CURRENT.get() == null;
        if (owner) CURRENT.set(new Counter(maximumRequests));
        return new Scope(owner);
    }

    /** No scope means this call is outside a bounded recovery/query batch. */
    public static boolean tryAcquire() {
        Counter counter = CURRENT.get();
        if (counter == null) return true;
        if (counter.remaining == 0) return false;
        counter.remaining--;
        return true;
    }

    private static final class Counter {
        private int remaining;
        private Counter(int remaining) { this.remaining = remaining; }
    }

    public static final class Scope implements AutoCloseable {
        private final boolean owner;
        private boolean closed;
        private Scope(boolean owner) { this.owner = owner; }
        @Override public void close() {
            if (!closed && owner) CURRENT.remove();
            closed = true;
        }
    }
}
