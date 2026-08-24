package io.github.rabitem.outcomemetrics.observation;

import io.micrometer.common.KeyValues;
import io.micrometer.core.instrument.observation.DefaultMeterObservationHandler;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("TagPrivacyPolicy")
class TagPrivacyPolicyTest {

    private SimpleMeterRegistry meters;
    private ObservationRegistry registry;
    private TagPrivacyPolicy policy;
    private OutcomeObservations outcomeObservations;

    @BeforeEach
    void setUp() {
        meters = new SimpleMeterRegistry();
        registry = ObservationRegistry.create();
        registry.observationConfig().observationHandler(new DefaultMeterObservationHandler(meters));
        policy = TagPrivacyPolicy.saasDefaults();
        policy.bindTo(meters);
        outcomeObservations = new OutcomeObservations(
                registry,
                OutcomeObservationConvention.builder().tagPrivacyPolicy(policy).build());
    }

    @Test
    @DisplayName("redacts deny-listed keys on their sanitized form, keeping the key")
    void denyKeysNormalized() {
        outcomeObservations.record(
                "privacy.op",
                KeyValues.of("userId", "42", "channel", "web"),
                () -> {
                });

        assertThat(meters.get("privacy.op")
                .tag("userId", "redacted")
                .tag("channel", "web")
                .tag("outcome", "success")
                .timer().count()).isEqualTo(1);
        assertThat(meters.get(TagPrivacyPolicy.REDACTED_COUNTER_NAME).functionCounter().count())
                .isEqualTo(1);
    }

    @Test
    @DisplayName("detects identity-shaped values and lets benign vocabulary pass")
    void detectors() {
        assertThat(policy.violations(KeyValues.of(
                "a", "john@example.com",
                "b", "6b309a58-267e-4e79-8e1d-75c0abeb43bf",
                "c", "eyJhbGciOi.eyJzdWIiOi.sig",
                "d", "10.0.0.1",
                "e", "deadbeefdeadbeef01",
                "f", "4915123456789")))
                .hasSize(6);
        assertThat(policy.violations(KeyValues.of(
                "region", "eu_west",
                "pool", "pool_2024",
                "tier", "tier_3",
                "version", "v1_2_3_4")))
                .isEmpty();
    }

    @Test
    @DisplayName("documents the dotted version string false positive")
    void versionStringFalsePositive() {
        assertThat(policy.violations(KeyValues.of("version", "1.2.3.4")))
                .singleElement().asString().contains("IPv4-shaped");
    }

    @Test
    @DisplayName("counts redactions exactly once despite start and stop consultations")
    void countsOncePerObservation() {
        outcomeObservations.record(
                "privacy.once", KeyValues.of("email", "x@y.z"), () -> {
                });

        assertThat(meters.get(TagPrivacyPolicy.REDACTED_COUNTER_NAME).functionCounter().count())
                .isEqualTo(1);
    }

    @Test
    @DisplayName("scrubs failure observations with the full schema intact")
    void failurePathScrubbed() {
        assertThatThrownBy(() -> outcomeObservations.record(
                "privacy.fail", KeyValues.of("ip", "203.0.113.9"), () -> {
                    throw new IllegalStateException("boom");
                })).isInstanceOf(IllegalStateException.class);

        assertThat(meters.get("privacy.fail")
                .tag("ip", "redacted")
                .tag("outcome", "failure")
                .tag("reason", "unknown")
                .tag("alertability", "page")
                .timer().count()).isEqualTo(1);
    }

    @Test
    @DisplayName("exposes saas defaults and validates builder input")
    void defaultsAndValidation() {
        assertThat(policy.denyKeys()).contains(
                "user_id", "email", "ip", "session_id", "token", "authorization");
        assertThatThrownBy(() -> TagPrivacyPolicy.builder().denyKeys(" "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("deny key must not be blank");
        assertThatThrownBy(() -> policy.denyKeys().add("sneaky"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> policy.violations(null))
                .isInstanceOf(NullPointerException.class);
    }
}
