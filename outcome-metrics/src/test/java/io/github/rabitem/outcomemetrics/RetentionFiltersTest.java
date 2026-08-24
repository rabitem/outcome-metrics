package io.github.rabitem.outcomemetrics;

import io.github.rabitem.outcomemetrics.observation.OutcomeObservations;
import io.micrometer.common.KeyValues;
import io.micrometer.core.instrument.composite.CompositeMeterRegistry;
import io.micrometer.core.instrument.observation.DefaultMeterObservationHandler;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("RetentionFilters")
class RetentionFiltersTest {

    @Test
    @DisplayName("routes explicit audit-class series to the audit child and everything to ops")
    void routing() {
        final SimpleMeterRegistry ops = new SimpleMeterRegistry();
        final SimpleMeterRegistry audit = new SimpleMeterRegistry();
        audit.config().meterFilter(RetentionFilters.auditOnly());
        final CompositeMeterRegistry composite = new CompositeMeterRegistry();
        composite.add(ops);
        composite.add(audit);

        final ObservationRegistry registry = ObservationRegistry.create();
        registry.observationConfig().observationHandler(new DefaultMeterObservationHandler(composite));
        final OutcomeObservations observations = new OutcomeObservations(registry);

        observations.record("payment.release",
                KeyValues.of(RetentionClass.AUDIT.tag()).and("channel", "web"), () -> {
                });
        observations.record("cart.view", KeyValues.of("channel", "web"), () -> {
        });

        // ops keeps everything, including audit-class (live alerting), for its shorter TTL
        assertThat(ops.get("payment.release").tag("retention", "audit").timer().count()).isEqualTo(1);
        assertThat(ops.get("cart.view").timer().count()).isEqualTo(1);
        // audit receives only what explicitly declared audit class
        assertThat(audit.get("payment.release").timer().count()).isEqualTo(1);
        assertThat(audit.find("cart.view").timer()).isNull();
    }

    @Test
    @DisplayName("supports a strict split via excludeAudit")
    void strictSplit() {
        final SimpleMeterRegistry opsStrict = new SimpleMeterRegistry();
        opsStrict.config().meterFilter(RetentionFilters.excludeAudit());

        opsStrict.counter("audit.thing", RetentionClass.TAG_RETENTION, RetentionClass.AUDIT.tagValue())
                .increment();
        opsStrict.counter("ops.thing").increment();

        assertThat(opsStrict.find("audit.thing").counter()).isNull();
        assertThat(opsStrict.get("ops.thing").counter().count()).isEqualTo(1.0);
    }
}
