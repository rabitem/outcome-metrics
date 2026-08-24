package io.github.rabitem.outcomemetrics.observation;

import io.github.rabitem.outcomemetrics.MetricTagValues;
import io.micrometer.common.KeyValue;
import io.micrometer.common.KeyValues;
import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.jspecify.annotations.NonNull;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Re-identification risk guard for rare tag combinations.
 *
 * <p>Per-tag limits miss rare combinations (region × product × reason) that can point at tiny
 * cohorts. Until a combination of the guarded keys shows sustained volume — at least
 * {@code minSupport} events within one tumbling window — its guarded tag values emit as
 * {@code other}. Reveal is one-way per process: Micrometer counters are monotonic, so the first
 * {@code minSupport - 1} events stay in the collapsed series forever, and re-hiding a revealed
 * series is pointless because the time-series database already stores its history.
 *
 * <p><b>This is not k-anonymity.</b> Support is counted in events, and events are not individuals:
 * one chatty user crosses any threshold alone. The guard reduces re-identification risk as
 * defense-in-depth in front of backend controls; it does not bound cohort sizes.
 *
 * <p>Unlike the signal guards in this library ({@link OutcomeScope}, {@link ReasonBudget}), which
 * fail open, this privacy guard fails <em>closed</em>: over the tracked-tuple cap, unseen
 * combinations stay collapsed. Collapsed events are counted on
 * {@value #COLLAPSED_COUNTER_NAME}.
 *
 * <p>Guarding {@code outcome} or {@code alertability} is rejected at build time: remapping those
 * would break the binary outcome invariant or silently drop pages.
 *
 * <p>Wire via {@code OutcomeObservationConvention.builder().combinationGuard(guard).build()}.
 *
 * @since 0.1.0
 */
public final class CombinationGuard implements MeterBinder {

    /** Counter of observations whose guarded values were collapsed to {@code other}. */
    public static final String COLLAPSED_COUNTER_NAME = "outcome.metrics.combination_guard.collapsed";

    /** Default maximum tuples tracked toward reveal; beyond this, new tuples stay collapsed. */
    public static final int DEFAULT_MAX_TRACKED_TUPLES = 10_000;

    private final Set<String> guardedKeys;
    private final int minSupport;
    private final Duration window;
    private final List<String> namePrefixes;
    private final int maxTrackedTuples;
    private final Clock clock;
    private final ConcurrentMap<String, WindowSupport> pending = new ConcurrentHashMap<>();
    private final Set<String> revealed = ConcurrentHashMap.newKeySet();
    private final AtomicLong collapsed = new AtomicLong();

    private CombinationGuard(final Builder builder) {
        this.guardedKeys = Collections.unmodifiableSet(new LinkedHashSet<>(builder.keys));
        this.minSupport = builder.minSupport;
        this.window = builder.window;
        this.namePrefixes = List.copyOf(builder.namePrefixes);
        this.maxTrackedTuples = builder.maxTrackedTuples;
        this.clock = builder.clock;
    }

    /**
     * Creates a guard builder.
     *
     * @return a new builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Applies the guard to an observation's final tags.
     *
     * @param name observation name
     * @param tags assembled low-cardinality tags
     * @return the tags unchanged when revealed or out of scope, otherwise with guarded values
     * collapsed to {@code other}
     */
    KeyValues apply(final String name, final KeyValues tags) {
        final String tupleKey = tupleKey(name, tags);
        if (tupleKey == null || recordSupport(tupleKey)) {
            return tags;
        }
        collapsed.incrementAndGet();
        return collapse(tags);
    }

    /**
     * Applies the guard without counting support, for provisional (pre-settlement) consultations.
     *
     * @param name observation name
     * @param tags provisional tags
     * @return the tags collapsed unless the tuple is already revealed — privacy guards fail closed
     */
    KeyValues applyProvisional(final String name, final KeyValues tags) {
        final String tupleKey = tupleKey(name, tags);
        if (tupleKey == null || revealed.contains(tupleKey)) {
            return tags;
        }
        return collapse(tags);
    }

    private String tupleKey(final String name, final KeyValues tags) {
        if (!appliesTo(name)) {
            return null;
        }
        final StringBuilder tupleKey = new StringBuilder(name == null ? "" : name);
        boolean anyGuardedKeyPresent = false;
        for (final KeyValue tag : tags) {
            if (guardedKeys.contains(tag.getKey())) {
                anyGuardedKeyPresent = true;
                tupleKey.append('|').append(tag.getKey()).append('=').append(tag.getValue());
            }
        }
        return anyGuardedKeyPresent ? tupleKey.toString() : null;
    }

    private KeyValues collapse(final KeyValues tags) {
        final List<KeyValue> collapsedTags = new ArrayList<>();
        for (final KeyValue tag : tags) {
            collapsedTags.add(guardedKeys.contains(tag.getKey())
                    ? KeyValue.of(tag.getKey(), MetricTagValues.OTHER)
                    : tag);
        }
        return KeyValues.of(collapsedTags);
    }

    private boolean appliesTo(final String name) {
        if (namePrefixes.isEmpty()) {
            return true;
        }
        if (name == null) {
            return false;
        }
        for (final String prefix : namePrefixes) {
            if (name.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private boolean recordSupport(final String tupleKey) {
        if (revealed.contains(tupleKey)) {
            return true;
        }
        final WindowSupport support = pending.computeIfAbsent(tupleKey, key ->
                pending.size() >= maxTrackedTuples ? null : new WindowSupport());
        if (support == null) {
            // Over the tracking cap: privacy guards fail closed — stay collapsed.
            return false;
        }
        if (support.count(clock.instant(), window) >= minSupport) {
            revealed.add(tupleKey);
            pending.remove(tupleKey);
            return true;
        }
        return false;
    }

    @Override
    public void bindTo(final @NonNull MeterRegistry registry) {
        FunctionCounter.builder(COLLAPSED_COUNTER_NAME, this, guard -> guard.collapsed.doubleValue())
                .description("Observations whose guarded tag combination was collapsed to other")
                .register(registry);
    }

    private static final class WindowSupport {

        private Instant windowStart;
        private int count;

        synchronized int count(final Instant now, final Duration window) {
            if (windowStart == null || Duration.between(windowStart, now).compareTo(window) >= 0) {
                windowStart = now;
                count = 0;
            }
            count++;
            return count;
        }
    }

    /**
     * Builder for {@link CombinationGuard}.
     *
     * @since 0.1.0
     */
    public static final class Builder {

        private final Set<String> keys = new LinkedHashSet<>();
        private final List<String> namePrefixes = new ArrayList<>();
        private int minSupport = 2;
        private Duration window = Duration.ofMinutes(15);
        private int maxTrackedTuples = DEFAULT_MAX_TRACKED_TUPLES;
        private Clock clock = Clock.systemUTC();

        private Builder() {
        }

        /**
         * Adds guarded tag keys whose combinations require support before revealing.
         *
         * @param tagKeys tag keys; must not be blank, {@code outcome} and {@code alertability} are
         *                rejected
         * @return this builder
         */
        public Builder keys(final String... tagKeys) {
            Objects.requireNonNull(tagKeys, "tagKeys must not be null");
            for (final String key : tagKeys) {
                if (key == null || key.isBlank()) {
                    throw new IllegalArgumentException("guarded key must not be blank");
                }
                final String stripped = key.strip();
                if (OutcomeObservationConvention.TAG_OUTCOME.equals(stripped)
                        || OutcomeObservationConvention.TAG_ALERTABILITY.equals(stripped)) {
                    throw new IllegalArgumentException("guarding \"" + stripped + "\" is not allowed:"
                            + " collapsing it would break the binary outcome invariant or silently"
                            + " drop pages");
                }
                keys.add(stripped);
            }
            return this;
        }

        /**
         * Sets the minimum events per window before a combination reveals.
         *
         * @param support minimum support; must be at least 2
         * @return this builder
         */
        public Builder minSupport(final int support) {
            if (support < 2) {
                throw new IllegalArgumentException("minSupport must be at least 2");
            }
            this.minSupport = support;
            return this;
        }

        /**
         * Sets the tumbling window within which support must accumulate.
         *
         * @param supportWindow window length; must be positive
         * @return this builder
         */
        public Builder window(final Duration supportWindow) {
            Objects.requireNonNull(supportWindow, "window must not be null");
            if (supportWindow.isZero() || supportWindow.isNegative()) {
                throw new IllegalArgumentException("window must be positive");
            }
            this.window = supportWindow;
            return this;
        }

        /**
         * Restricts the guard to observation names starting with any of the prefixes.
         *
         * @param prefixes name prefixes; empty means all names
         * @return this builder
         */
        public Builder namePrefixes(final String... prefixes) {
            Objects.requireNonNull(prefixes, "prefixes must not be null");
            for (final String prefix : prefixes) {
                if (prefix == null || prefix.isBlank()) {
                    throw new IllegalArgumentException("name prefix must not be blank");
                }
                namePrefixes.add(prefix.strip());
            }
            return this;
        }

        /**
         * Sets the maximum tuples tracked toward reveal; beyond it, new tuples stay collapsed.
         *
         * @param cap tracked-tuple cap; must be positive
         * @return this builder
         */
        public Builder maxTrackedTuples(final int cap) {
            if (cap < 1) {
                throw new IllegalArgumentException("maxTrackedTuples must be positive");
            }
            this.maxTrackedTuples = cap;
            return this;
        }

        /**
         * Sets the clock used for window accounting.
         *
         * @param supportClock clock; must not be {@code null}
         * @return this builder
         */
        public Builder clock(final Clock supportClock) {
            this.clock = Objects.requireNonNull(supportClock, "clock must not be null");
            return this;
        }

        /**
         * Builds the guard.
         *
         * @return an immutable guard
         * @throws IllegalStateException if no guarded keys were configured
         */
        public CombinationGuard build() {
            if (keys.isEmpty()) {
                throw new IllegalStateException("at least one guarded key is required");
            }
            return new CombinationGuard(this);
        }
    }
}
