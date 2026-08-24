package io.github.rabitem.outcomemetrics.observation;

import java.util.HashSet;
import java.util.Set;

/**
 * Request-scoped deduplication window for outcome observations.
 *
 * <p>One downstream timeout inside a single user request can produce many identical failure
 * observations, inflating SLI counters. While a scope is open on the current thread, the first
 * observation of each series (name plus full low-cardinality tag set) is tagged
 * {@code occurrence=first} and every identical repeat {@code occurrence=repeat}. All observations
 * are still recorded — timers, spans, and diagnostic counts keep the full stream; SLI queries
 * filter on {@code occurrence="first"}.
 *
 * <p>Open a scope per unit of work, typically per incoming request:
 *
 * <pre>{@code
 * try (OutcomeScope scope = OutcomeScope.open()) {
 *     handleRequest();
 * }
 * }</pre>
 *
 * <p>The scope is thread-confined and fails open: with no scope on the observing thread every
 * observation is {@code occurrence=first}, matching unscoped behavior. Scopes nest as a stack —
 * an inner scope deduplicates independently, and after it closes the outer scope resumes its own
 * bookkeeping (a series first seen only inside the inner scope counts as first again in the outer
 * scope). Close a scope on the thread that opened it; {@link #close()} is idempotent and never
 * throws.
 *
 * <p>Per-scope tracking is bounded: beyond {@value #MAX_TRACKED_SERIES} distinct series, further
 * unseen series are reported as {@code first} rather than risking unbounded memory or hidden
 * signal.
 *
 * <p><b>Virtual threads are the good case</b>: per JEP 444, thread-locals are confined to their
 * virtual thread, so a scope opened on a virtual thread never bleeds to others sharing a carrier —
 * scope-per-request on a virtual-thread-per-request stack is exactly the intended model. Executor
 * handoffs (any thread change) fail open to {@code occurrence=first}. For carrier-pinning
 * observability, pair with {@code micrometer-java21}'s {@code VirtualThreadMetrics}.
 *
 * @since 0.1.0
 */
public final class OutcomeScope implements AutoCloseable {

    /** Maximum distinct series tracked per scope; beyond this, unseen series fail open to first. */
    static final int MAX_TRACKED_SERIES = 1024;

    private static final ThreadLocal<OutcomeScope> CURRENT = new ThreadLocal<>();

    private final OutcomeScope parent;
    private final Set<String> seenSeries = new HashSet<>();

    private OutcomeScope(final OutcomeScope parent) {
        this.parent = parent;
    }

    /**
     * Opens a scope on the current thread.
     *
     * @return the opened scope; close it on this thread, preferably with try-with-resources
     */
    public static OutcomeScope open() {
        final OutcomeScope scope = new OutcomeScope(CURRENT.get());
        CURRENT.set(scope);
        return scope;
    }

    /**
     * Returns the innermost scope open on the current thread.
     *
     * @return current scope, or {@code null} when none is open
     */
    static OutcomeScope current() {
        return CURRENT.get();
    }

    /**
     * Records a series observation and reports whether it is the first within this scope.
     *
     * @param seriesKey stable identity of the observation series; must not be {@code null}
     * @return {@code true} on first occurrence (or when the tracking cap is exceeded), {@code false}
     * on a repeat
     */
    boolean markFirst(final String seriesKey) {
        if (seenSeries.contains(seriesKey)) {
            return false;
        }
        if (seenSeries.size() >= MAX_TRACKED_SERIES) {
            return true;
        }
        seenSeries.add(seriesKey);
        return true;
    }

    /**
     * Closes this scope, restoring the enclosing scope on the current thread.
     *
     * <p>Idempotent. If this scope is not the innermost scope on the current thread (out-of-order
     * close, or close on a different thread), the thread's scope stack is left untouched.
     */
    @Override
    public void close() {
        if (CURRENT.get() != this) {
            return;
        }
        if (parent == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(parent);
        }
    }
}
