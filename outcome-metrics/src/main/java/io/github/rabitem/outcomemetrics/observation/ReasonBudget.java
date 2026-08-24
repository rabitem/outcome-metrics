package io.github.rabitem.outcomemetrics.observation;

import io.github.rabitem.outcomemetrics.MetricTagValues;
import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.jspecify.annotations.NonNull;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Bounded admission of reason codes per observation name, with operator-controlled expansion.
 *
 * <p>Static tag limits can hide the failure codes needed during an incident, while always exposing
 * every code burns scrape budget. This budget admits the first distinct reason codes per observation
 * name up to the current limit and emits every further code as {@link MetricTagValues#OTHER}, counted
 * by a suppression counter. {@link #expand()} raises the limit at runtime and takes effect on the
 * next event — including for codes that were previously suppressed, because budgeting happens at the
 * tag-value layer before the meter registry sees the id. {@link #collapse()} re-tightens admission of
 * new codes but never evicts admitted ones: eviction would break series continuity, the very thing an
 * incident responder needs.
 *
 * <p>Expansion and collapse are deliberately manual. Burn-rate policy belongs in the alerting layer
 * that already computes it over the SLO window; this class ships the mechanism and its observability
 * (a 0/1 mode gauge and the suppression counter) and leaves the trigger to the operator.
 *
 * <p>The schema-floor codes {@link MetricTagValues#NONE}, {@link MetricTagValues#UNKNOWN} and
 * {@link MetricTagValues#OTHER} always pass without consuming budget. Admission checks are
 * fail-open: under concurrent first sightings a few codes beyond the limit may be admitted rather
 * than risking suppressed signal.
 *
 * <p>If a bounded tag-value meter filter is also configured on the {@code reason} key, its bound must
 * be at least the expanded limit; a filter remap is pinned by the registry's pre-filter id cache and
 * cannot be undone by expanding this budget.
 *
 * @since 0.1.0
 */
public final class ReasonBudget implements MeterBinder {

    /** Mode gauge name: {@code 0} collapsed, {@code 1} expanded. */
    public static final String MODE_GAUGE_NAME = "outcome.metrics.reason_budget.expanded";

    /** Counter of reason codes emitted as {@code other} because the budget was exhausted. */
    public static final String SUPPRESSED_COUNTER_NAME = "outcome.metrics.reason_budget.suppressed";

    private final int collapsedLimit;
    private final int expandedLimit;
    private final ConcurrentMap<String, Set<String>> admittedByName = new ConcurrentHashMap<>();
    private final AtomicLong suppressed = new AtomicLong();
    private volatile boolean expanded;

    /**
     * Creates a reason budget.
     *
     * @param collapsedLimit distinct reason codes admitted per observation name when collapsed; must
     *                       be positive
     * @param expandedLimit  distinct reason codes admitted per observation name when expanded; must
     *                       be at least {@code collapsedLimit}
     */
    public ReasonBudget(final int collapsedLimit, final int expandedLimit) {
        if (collapsedLimit < 1) {
            throw new IllegalArgumentException("collapsedLimit must be positive");
        }
        if (expandedLimit < collapsedLimit) {
            throw new IllegalArgumentException("expandedLimit must be at least collapsedLimit");
        }
        this.collapsedLimit = collapsedLimit;
        this.expandedLimit = expandedLimit;
    }

    /**
     * Raises the admission limit to the expanded limit, effective on the next event.
     */
    public void expand() {
        expanded = true;
    }

    /**
     * Restores the collapsed admission limit for new codes; already admitted codes keep reporting.
     */
    public void collapse() {
        expanded = false;
    }

    /**
     * Returns whether the budget is expanded.
     *
     * @return {@code true} when expanded
     */
    public boolean isExpanded() {
        return expanded;
    }

    /**
     * Admits a reason code for an observation name or remaps it to {@link MetricTagValues#OTHER}.
     *
     * @param observationName observation name the code belongs to
     * @param reasonCode      sanitized reason code
     * @return the code itself when admitted, otherwise {@link MetricTagValues#OTHER}
     */
    String admit(final String observationName, final String reasonCode) {
        if (MetricTagValues.NONE.equals(reasonCode)
                || MetricTagValues.UNKNOWN.equals(reasonCode)
                || MetricTagValues.OTHER.equals(reasonCode)) {
            return reasonCode;
        }
        final Set<String> admitted = admittedByName.computeIfAbsent(
                observationName == null ? "" : observationName,
                name -> ConcurrentHashMap.newKeySet());
        if (admitted.contains(reasonCode)) {
            return reasonCode;
        }
        if (admitted.size() < (expanded ? expandedLimit : collapsedLimit)) {
            admitted.add(reasonCode);
            return reasonCode;
        }
        suppressed.incrementAndGet();
        return MetricTagValues.OTHER;
    }

    @Override
    public void bindTo(final @NonNull MeterRegistry registry) {
        Gauge.builder(MODE_GAUGE_NAME, this, budget -> budget.expanded ? 1 : 0)
                .description("Reason budget mode: 0 collapsed, 1 expanded")
                .register(registry);
        FunctionCounter.builder(SUPPRESSED_COUNTER_NAME, this, budget -> budget.suppressed.doubleValue())
                .description("Reason codes emitted as 'other' because the reason budget was exhausted")
                .register(registry);
    }
}
