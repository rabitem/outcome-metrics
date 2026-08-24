package io.github.rabitem.outcomemetrics.observation;

import io.github.rabitem.outcomemetrics.MetricTagValues;
import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.jspecify.annotations.NonNull;

import java.util.Collections;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Closed membership registry for failure reason codes.
 *
 * <p>{@link OutcomeReasonSource} is convention-only: nothing stops a runtime from emitting free-text
 * or user-derived reason codes — or, since alertability (#20) rides the same object, from carrying a
 * self-silencing routing downgrade. A registry-enforcing convention distrusts unregistered reasons
 * entirely: their observations emit {@code reason=unknown} <em>and</em> {@code alertability=page},
 * and a rejection counter makes the drift observable.
 *
 * <p>Runtime enforcement never throws — the observation is recording a failure that already
 * happened, and telemetry must not convert one incident into two. Fail-fast validation happens at
 * registration time; {@link #codes()} exposes the sanitized vocabulary for tests and CI attestation.
 *
 * <p>Registration is explicit (enums or literal codes); there is deliberately no classpath scanning.
 * The schema-floor codes ({@code none}, {@code unknown}, {@code other}) are implicitly registered.
 * Wire into recording via
 * {@code OutcomeObservationConvention.builder().reasonRegistry(registry).build()}.
 *
 * @since 0.1.0
 */
public final class ReasonRegistry implements MeterBinder {

    /** Counter of observations whose reason code was not registered and was remapped. */
    public static final String REJECTED_COUNTER_NAME = "outcome.metrics.reason_registry.rejected";

    private final Set<String> codes;
    private final AtomicLong rejected = new AtomicLong();

    private ReasonRegistry(final Set<String> codes) {
        this.codes = Collections.unmodifiableSet(codes);
    }

    /**
     * Creates a registry from a single enum vocabulary.
     *
     * @param <E>        enum type implementing {@link OutcomeReason}
     * @param vocabulary enum class whose constants define the vocabulary; must not be {@code null}
     * @return a registry containing the enum's sanitized codes plus the schema floor
     */
    public static <E extends Enum<E> & OutcomeReason> ReasonRegistry of(final Class<E> vocabulary) {
        return builder().vocabulary(vocabulary).build();
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
     * Returns whether a code is part of the registered vocabulary.
     *
     * @param code sanitized reason code
     * @return {@code true} when registered (schema-floor codes always are)
     */
    public boolean isRegistered(final String code) {
        return codes.contains(code);
    }

    /**
     * Returns the registered vocabulary, sorted, including the schema floor.
     *
     * @return unmodifiable sorted codes, for tests and CI attestation
     */
    public Set<String> codes() {
        return codes;
    }

    /**
     * Counts a rejected (unregistered) reason code.
     */
    void recordRejection() {
        rejected.incrementAndGet();
    }

    @Override
    public void bindTo(final @NonNull MeterRegistry registry) {
        FunctionCounter.builder(REJECTED_COUNTER_NAME, this, r -> r.rejected.doubleValue())
                .description("Observations whose reason code was not registered and was remapped to unknown")
                .register(registry);
    }

    /**
     * Builder collecting vocabularies and literal codes.
     *
     * @since 0.1.0
     */
    public static final class Builder {

        private final Set<String> codes = new TreeSet<>();

        private Builder() {
        }

        /**
         * Registers every constant of an enum vocabulary.
         *
         * @param <E>        enum type implementing {@link OutcomeReason}
         * @param vocabulary enum class; must not be {@code null}
         * @return this builder
         * @throws IllegalArgumentException if a constant's code is {@code null} or blank
         */
        public <E extends Enum<E> & OutcomeReason> Builder vocabulary(final Class<E> vocabulary) {
            Objects.requireNonNull(vocabulary, "vocabulary must not be null");
            for (final E constant : vocabulary.getEnumConstants()) {
                register(constant.code(), vocabulary.getSimpleName() + "." + constant.name());
            }
            return this;
        }

        /**
         * Registers literal reason codes.
         *
         * @param reasonCodes codes to register; must not be {@code null}, blank entries fail fast
         * @return this builder
         * @throws IllegalArgumentException if a code is {@code null} or blank
         */
        public Builder codes(final String... reasonCodes) {
            Objects.requireNonNull(reasonCodes, "reasonCodes must not be null");
            for (final String code : reasonCodes) {
                register(code, "literal code");
            }
            return this;
        }

        /**
         * Builds the registry, adding the schema-floor codes.
         *
         * @return an immutable registry
         */
        public ReasonRegistry build() {
            codes.add(MetricTagValues.NONE);
            codes.add(MetricTagValues.UNKNOWN);
            codes.add(MetricTagValues.OTHER);
            codes.add(MetricTagValues.CANCELLED);
            return new ReasonRegistry(new TreeSet<>(codes));
        }

        private void register(final String code, final String origin) {
            if (code == null || code.isBlank()) {
                throw new IllegalArgumentException("Reason code from " + origin + " must not be blank");
            }
            codes.add(MetricTagValues.sanitizeTagValue(code));
        }
    }
}
