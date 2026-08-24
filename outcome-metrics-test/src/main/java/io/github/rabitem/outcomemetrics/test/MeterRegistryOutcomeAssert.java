package io.github.rabitem.outcomemetrics.test;

import io.github.rabitem.outcomemetrics.observation.OutcomeObservationConvention;
import io.github.rabitem.outcomemetrics.observation.TagPrivacyPolicy;
import io.micrometer.common.KeyValue;
import io.micrometer.common.KeyValues;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import org.assertj.core.api.AbstractAssert;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * AssertJ assertions over the meters recorded in a {@link MeterRegistry}.
 *
 * <p>The headline check is {@link #hasConsistentLabelSets()}: inconsistent label sets under one
 * meter name are rejected by Prometheus-style registries in production at the first divergence
 * (see issue #60) — this is the test-time detector.
 *
 * @since 0.1.0
 */
public final class MeterRegistryOutcomeAssert
        extends AbstractAssert<MeterRegistryOutcomeAssert, MeterRegistry> {

    private static final List<String> SCHEMA_TAGS = List.of(
            OutcomeObservationConvention.TAG_OUTCOME,
            OutcomeObservationConvention.TAG_REASON,
            OutcomeObservationConvention.TAG_INTEGRITY,
            OutcomeObservationConvention.TAG_ALERTABILITY,
            OutcomeObservationConvention.TAG_OCCURRENCE);

    MeterRegistryOutcomeAssert(final MeterRegistry meterRegistry) {
        super(meterRegistry, MeterRegistryOutcomeAssert.class);
    }

    /**
     * Asserts that every meter name carries one consistent set of tag keys.
     *
     * @return this assert
     */
    public MeterRegistryOutcomeAssert hasConsistentLabelSets() {
        isNotNull();
        final Map<String, Set<String>> keySetByName = new LinkedHashMap<>();
        for (final Meter meter : actual.getMeters()) {
            final String name = meter.getId().getName();
            final Set<String> keys = tagKeys(meter);
            final Set<String> known = keySetByName.putIfAbsent(name, keys);
            if (known != null && !known.equals(keys)) {
                failWithMessage(
                        "Meter name <%s> has inconsistent label sets: <%s> vs <%s>."
                                + " Prometheus-style registries reject this at the first divergence.",
                        name, known, keys);
            }
        }
        return this;
    }

    /**
     * Asserts that every series of the given name carries the full outcome schema
     * ({@code outcome}, {@code reason}, {@code integrity}, {@code alertability},
     * {@code occurrence}).
     *
     * @param meterName observation meter name
     * @return this assert
     */
    public MeterRegistryOutcomeAssert hasOutcomeSchema(final String meterName) {
        isNotNull();
        boolean found = false;
        for (final Meter meter : actual.getMeters()) {
            if (!meter.getId().getName().equals(meterName)) {
                continue;
            }
            found = true;
            final Set<String> keys = tagKeys(meter);
            for (final String schemaTag : SCHEMA_TAGS) {
                if (!keys.contains(schemaTag)) {
                    failWithMessage("Meter <%s> is missing schema tag <%s>; present keys: <%s>",
                            meterName, schemaTag, keys);
                }
            }
        }
        if (!found) {
            failWithMessage("No meter named <%s> was recorded", meterName);
        }
        return this;
    }

    /**
     * Asserts a cardinality budget: at most {@code maxSeries} distinct series for the name.
     *
     * @param meterName observation meter name
     * @param maxSeries budget of distinct series
     * @return this assert
     */
    public MeterRegistryOutcomeAssert hasSeriesCardinalityAtMost(final String meterName, final int maxSeries) {
        isNotNull();
        final long series = actual.getMeters().stream()
                .filter(meter -> meter.getId().getName().equals(meterName))
                .count();
        if (series > maxSeries) {
            failWithMessage("Meter <%s> has <%s> series, exceeding the budget of <%s>",
                    meterName, series, maxSeries);
        }
        return this;
    }

    /**
     * Asserts that no recorded tag violates the given privacy policy.
     *
     * @param policy privacy policy whose {@link TagPrivacyPolicy#violations} defines the contract
     * @return this assert
     */
    public MeterRegistryOutcomeAssert hasNoPrivacyViolations(final TagPrivacyPolicy policy) {
        isNotNull();
        final List<String> found = new ArrayList<>();
        for (final Meter meter : actual.getMeters()) {
            final List<KeyValue> keyValues = new ArrayList<>();
            for (final Tag tag : meter.getId().getTags()) {
                keyValues.add(KeyValue.of(tag.getKey(), tag.getValue()));
            }
            for (final String violation : policy.violations(KeyValues.of(keyValues))) {
                found.add(meter.getId().getName() + ": " + violation);
            }
        }
        if (!found.isEmpty()) {
            failWithMessage("PII in recorded tags:%n%s", String.join(System.lineSeparator(), found));
        }
        return this;
    }

    private static Set<String> tagKeys(final Meter meter) {
        final Set<String> keys = new TreeSet<>();
        for (final Tag tag : meter.getId().getTags()) {
            keys.add(tag.getKey());
        }
        return keys;
    }
}
