package io.github.rabitem.outcomemetrics.observation;

import io.github.rabitem.outcomemetrics.MetricTagValues;
import io.micrometer.common.KeyValue;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.jspecify.annotations.NonNull;

import java.util.Collections;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * Closed catalog of SLO ids that this binary's instrumentation is bound to.
 *
 * <p>SLOs live in YAML (Sloth, OpenSLO) while instrumentation lives in code, and the two drift.
 * Code can truthfully assert exactly one thing: <em>which</em> SLO an observation site is the
 * instrumentation source of. The binding (the id) lives here; the policy (target, window) stays in
 * the SLO toolchain where error budgets are evaluated.
 *
 * <p>Observation sites obtain their {@code slo} tag through {@link #binding(String)} — an
 * undeclared id throws, so resolve bindings once into constants and a typo fails at wiring time,
 * not on the first request:
 *
 * <pre>{@code
 * private static final KeyValue CHECKOUT_SLO = sloCatalog.binding("checkout-success");
 * ...
 * observations.record("order.place", KeyValues.of(CHECKOUT_SLO).and(dimensions), work);
 * }</pre>
 *
 * <p>{@link #bindTo} registers an info gauge {@value #INFO_GAUGE_NAME} with a {@code slo} tag per
 * declared id, proving at runtime which ids this binary instruments; alerting can then flag rules
 * that reference an id with no info series. {@link #ids()} exposes the declared ids for CI
 * attestation (#64).
 *
 * <p>{@code @MeasuredOutcome} static tags ({@code tags = {"slo=..."}}) are not catalog-checked —
 * the CI export is the check for those sites.
 *
 * @since 0.1.0
 */
public final class SloCatalog implements MeterBinder {

    /** Tag name binding an observation to an SLO id. */
    public static final String TAG_SLO = "slo";

    /** Info gauge proving which SLO ids this binary instruments (value is always 1). */
    public static final String INFO_GAUGE_NAME = "outcome.metrics.slo.info";

    private final Set<String> ids;

    private SloCatalog(final Set<String> ids) {
        this.ids = Collections.unmodifiableSet(ids);
    }

    /**
     * Creates a catalog of declared SLO ids.
     *
     * @param sloIds SLO ids; must not be {@code null} or contain blank entries
     * @return an immutable catalog with sanitized ids
     */
    public static SloCatalog of(final String... sloIds) {
        Objects.requireNonNull(sloIds, "sloIds must not be null");
        final Set<String> sanitized = new TreeSet<>();
        for (final String id : sloIds) {
            if (id == null || id.isBlank()) {
                throw new IllegalArgumentException("SLO id must not be blank");
            }
            sanitized.add(MetricTagValues.sanitizeTagValue(id));
        }
        if (sanitized.isEmpty()) {
            throw new IllegalArgumentException("at least one SLO id is required");
        }
        return new SloCatalog(sanitized);
    }

    /**
     * Returns the {@code slo} tag for a declared id.
     *
     * @param sloId declared SLO id (sanitized form or original spelling)
     * @return the {@code slo} key value, never {@code null}
     * @throws IllegalArgumentException if the id is not declared in this catalog
     */
    public KeyValue binding(final String sloId) {
        final String sanitized = MetricTagValues.sanitizeTagValue(sloId);
        if (!ids.contains(sanitized)) {
            throw new IllegalArgumentException("SLO id \"" + sloId + "\" is not declared in this"
                    + " catalog; declared ids: " + ids);
        }
        return KeyValue.of(TAG_SLO, sanitized);
    }

    /**
     * Returns the declared SLO ids, sorted and sanitized.
     *
     * @return unmodifiable ids, for tests and CI attestation
     */
    public Set<String> ids() {
        return ids;
    }

    @Override
    public void bindTo(final @NonNull MeterRegistry registry) {
        for (final String id : ids) {
            Gauge.builder(INFO_GAUGE_NAME, this, catalog -> 1.0)
                    .tag(TAG_SLO, id)
                    .description("SLO ids this binary's instrumentation is bound to (always 1)")
                    .register(registry);
        }
    }
}
