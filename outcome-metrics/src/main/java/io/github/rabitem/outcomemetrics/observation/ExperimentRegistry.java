package io.github.rabitem.outcomemetrics.observation;

import io.github.rabitem.outcomemetrics.MetricTagValues;
import io.micrometer.common.KeyValues;
import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.jspecify.annotations.NonNull;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Pre-registered experiment slices for flag × outcome comparison without combinatorial cardinality.
 *
 * <p>Experiment ids arrive at runtime from a flag SDK, so unlike {@link SloCatalog} this registry
 * never throws at slice time: an unregistered id collapses to {@code experiment=unregistered,
 * variant=unknown} — raw flag keys mechanically never become tag values — and an undeclared arm on
 * a registered experiment keeps the id but collapses the variant to {@code unknown}. Both are
 * counted on {@value #UNREGISTERED_COUNTER_NAME}.
 *
 * <p>Every event of a sliced observation name must carry the bundle — label sets must stay
 * consistent per meter name (mixed sets crash legacy Prometheus clients and silently split
 * aggregations on current ones) — so un-sliced traffic on the same name uses {@link #none()}
 * ({@code experiment=none, variant=none}).
 *
 * <p>Arms are declared per experiment (default {@code control|treatment}; declared arms replace the
 * default, capped at {@value Builder#MAX_ARMS}); the number of registered experiments is capped at
 * build time ({@code maxActive}, default {@value Builder#DEFAULT_MAX_ACTIVE}). For experiment ×
 * surface × reason combinatorics, pair with {@link CombinationGuard}.
 *
 * @since 0.1.0
 */
public final class ExperimentRegistry implements MeterBinder {

    /** Experiment tag name. */
    public static final String TAG_EXPERIMENT = "experiment";

    /** Variant tag name. */
    public static final String TAG_VARIANT = "variant";

    /** Tag value for ids not present in the registry. */
    public static final String UNREGISTERED = "unregistered";

    /** Counter of slices with unregistered ids or undeclared variants. */
    public static final String UNREGISTERED_COUNTER_NAME = "outcome.metrics.experiment_registry.unregistered";

    /** Info gauge proving which experiments this binary slices (value is always 1). */
    public static final String INFO_GAUGE_NAME = "outcome.metrics.experiments.info";

    private static final KeyValues NONE = KeyValues.of(
            TAG_EXPERIMENT, MetricTagValues.NONE, TAG_VARIANT, MetricTagValues.NONE);
    private static final KeyValues UNREGISTERED_SLICE = KeyValues.of(
            TAG_EXPERIMENT, UNREGISTERED, TAG_VARIANT, MetricTagValues.UNKNOWN);

    private final Map<String, Set<String>> armsByExperiment;
    private final AtomicLong unregistered = new AtomicLong();

    private ExperimentRegistry(final Map<String, Set<String>> armsByExperiment) {
        this.armsByExperiment = Collections.unmodifiableMap(armsByExperiment);
    }

    /**
     * Creates a registry builder.
     *
     * @return a new builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Returns the bundle for traffic not in any experiment.
     *
     * @return {@code experiment=none, variant=none}, a shared constant
     */
    public static KeyValues none() {
        return NONE;
    }

    /**
     * Returns the slice tags for a runtime experiment assignment.
     *
     * <p>Never throws: unregistered ids collapse entirely; undeclared arms keep the registered id
     * and collapse the variant.
     *
     * @param experimentId runtime experiment id; may be {@code null}
     * @param variant      runtime arm; may be {@code null}
     * @return {@code experiment}/{@code variant} tags, never {@code null}
     */
    public KeyValues slice(final String experimentId, final String variant) {
        final String id = MetricTagValues.sanitizeTagValue(experimentId);
        final Set<String> arms = armsByExperiment.get(id);
        if (arms == null) {
            unregistered.incrementAndGet();
            return UNREGISTERED_SLICE;
        }
        final String arm = MetricTagValues.sanitizeTagValue(variant);
        if (!arms.contains(arm)) {
            unregistered.incrementAndGet();
            return KeyValues.of(TAG_EXPERIMENT, id, TAG_VARIANT, MetricTagValues.UNKNOWN);
        }
        return KeyValues.of(TAG_EXPERIMENT, id, TAG_VARIANT, arm);
    }

    /**
     * Returns the registered experiment ids, sorted and sanitized.
     *
     * @return unmodifiable ids, for tests and attestation
     */
    public Set<String> ids() {
        return Collections.unmodifiableSet(new TreeSet<>(armsByExperiment.keySet()));
    }

    @Override
    public void bindTo(final @NonNull MeterRegistry registry) {
        for (final String id : armsByExperiment.keySet()) {
            Gauge.builder(INFO_GAUGE_NAME, this, r -> 1.0)
                    .tag(TAG_EXPERIMENT, id)
                    .description("Experiments this binary's instrumentation slices (always 1)")
                    .register(registry);
        }
        FunctionCounter.builder(UNREGISTERED_COUNTER_NAME, this, r -> r.unregistered.doubleValue())
                .description("Slices with unregistered experiment ids or undeclared variants")
                .register(registry);
    }

    /**
     * Builder for {@link ExperimentRegistry}.
     *
     * @since 0.1.0
     */
    public static final class Builder {

        /** Default cap on registered experiments. */
        public static final int DEFAULT_MAX_ACTIVE = 10;

        /** Maximum declared arms per experiment, including control. */
        public static final int MAX_ARMS = 6;

        private final Map<String, Set<String>> experiments = new TreeMap<>();
        private int maxActive = DEFAULT_MAX_ACTIVE;

        private Builder() {
        }

        /**
         * Registers an experiment.
         *
         * @param experimentId experiment id; must not be blank
         * @param arms         declared arms replacing the {@code control|treatment} default; at most
         *                     {@value #MAX_ARMS}, none blank
         * @return this builder
         */
        public Builder experiment(final String experimentId, final String... arms) {
            if (experimentId == null || experimentId.isBlank()) {
                throw new IllegalArgumentException("experiment id must not be blank");
            }
            Objects.requireNonNull(arms, "arms must not be null");
            if (arms.length > MAX_ARMS) {
                throw new IllegalArgumentException("at most " + MAX_ARMS + " arms per experiment");
            }
            final Set<String> declared = new TreeSet<>();
            if (arms.length == 0) {
                declared.add("control");
                declared.add("treatment");
            } else {
                for (final String arm : arms) {
                    if (arm == null || arm.isBlank()) {
                        throw new IllegalArgumentException("experiment arm must not be blank");
                    }
                    declared.add(MetricTagValues.sanitizeTagValue(arm));
                }
            }
            experiments.put(MetricTagValues.sanitizeTagValue(experimentId), declared);
            return this;
        }

        /**
         * Overrides the cap on registered experiments.
         *
         * @param cap maximum experiments; must be positive
         * @return this builder
         */
        public Builder maxActive(final int cap) {
            if (cap < 1) {
                throw new IllegalArgumentException("maxActive must be positive");
            }
            this.maxActive = cap;
            return this;
        }

        /**
         * Builds the registry, enforcing the active-experiment cap.
         *
         * @return an immutable registry
         * @throws IllegalArgumentException if more than {@code maxActive} experiments are registered
         */
        public ExperimentRegistry build() {
            if (experiments.isEmpty()) {
                throw new IllegalArgumentException("at least one experiment is required");
            }
            if (experiments.size() > maxActive) {
                throw new IllegalArgumentException(experiments.size() + " experiments registered,"
                        + " but maxActive is " + maxActive + " - retire experiments before adding more");
            }
            return new ExperimentRegistry(new LinkedHashMap<>(experiments));
        }
    }
}
