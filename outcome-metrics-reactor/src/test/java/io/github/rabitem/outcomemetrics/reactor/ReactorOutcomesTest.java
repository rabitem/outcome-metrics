package io.github.rabitem.outcomemetrics.reactor;

import io.github.rabitem.outcomemetrics.observation.OutcomeObservations;
import io.micrometer.common.KeyValues;
import io.micrometer.core.instrument.observation.DefaultMeterObservationHandler;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ReactorOutcomes")
class ReactorOutcomesTest {

    private SimpleMeterRegistry meters;
    private OutcomeObservations observations;

    @BeforeEach
    void setUp() {
        meters = new SimpleMeterRegistry();
        final ObservationRegistry registry = ObservationRegistry.create();
        registry.observationConfig().observationHandler(new DefaultMeterObservationHandler(meters));
        observations = new OutcomeObservations(registry);
    }

    @Test
    @DisplayName("settles at the terminal signal, not at assembly")
    void terminalNotAssembly() {
        final Mono<String> bound = ReactorOutcomes.record(
                observations, "flow.mono", KeyValues.of("channel", "web"), Mono.just("value"));

        // assembled but never subscribed: nothing recorded
        assertThat(meters.find("flow.mono").timer()).isNull();

        StepVerifier.create(bound).expectNext("value").verifyComplete();
        assertThat(meters.get("flow.mono")
                .tag("outcome", "success").tag("channel", "web")
                .timer().count()).isEqualTo(1);
    }

    @Test
    @DisplayName("records errors and empty completions correctly")
    void errorsAndEmpty() {
        StepVerifier.create(ReactorOutcomes.record(observations, "flow.error", KeyValues.empty(),
                        Mono.error(new IllegalStateException("boom"))))
                .verifyError(IllegalStateException.class);
        StepVerifier.create(ReactorOutcomes.record(observations, "flow.empty", KeyValues.empty(),
                        Mono.empty()))
                .verifyComplete();

        assertThat(meters.get("flow.error")
                .tag("outcome", "failure").tag("reason", "unknown")
                .timer().count()).isEqualTo(1);
        assertThat(meters.get("flow.empty")
                .tag("outcome", "success").timer().count()).isEqualTo(1);
    }

    @Test
    @DisplayName("records cancellation as reason cancelled, not success")
    void cancellation() {
        StepVerifier.create(ReactorOutcomes.record(observations, "flow.cancel", KeyValues.empty(),
                        Mono.never()))
                .expectSubscription()
                .thenCancel()
                .verify(Duration.ofSeconds(5));

        assertThat(meters.get("flow.cancel")
                .tag("outcome", "failure")
                .tag("reason", "cancelled")
                .tag("alertability", "none")
                .timer().count()).isEqualTo(1);
        assertThat(meters.find("flow.cancel").tags("outcome", "success").timer()).isNull();
    }

    @Test
    @DisplayName("observes each subscription: a retry counts as two attempts")
    void oneObservationPerSubscription() {
        final java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        final Mono<String> flaky = ReactorOutcomes.record(observations, "flow.retry", KeyValues.empty(),
                Mono.defer(() -> calls.incrementAndGet() == 1
                        ? Mono.error(new IllegalStateException("first fails"))
                        : Mono.just("ok")));

        StepVerifier.create(flaky.retry(1)).expectNext("ok").verifyComplete();

        assertThat(meters.get("flow.retry").tag("outcome", "failure").timer().count()).isEqualTo(1);
        assertThat(meters.get("flow.retry").tag("outcome", "success").timer().count()).isEqualTo(1);
    }

    @Test
    @DisplayName("binds fluxes with completion after all elements")
    void fluxCompletion() {
        StepVerifier.create(ReactorOutcomes.record(observations, "flow.flux", KeyValues.empty(),
                        Flux.just(1, 2, 3)))
                .expectNext(1, 2, 3)
                .verifyComplete();

        assertThat(meters.get("flow.flux").tag("outcome", "success").timer().count()).isEqualTo(1);
    }

    @Test
    @DisplayName("bind dispatches by concrete type and passes unknowns through")
    void bindDispatch() {
        assertThat(ReactorOutcomes.bind(observations, "flow.bind", KeyValues.empty(), Mono.just(1)))
                .isInstanceOf(Mono.class);
        assertThat(ReactorOutcomes.bind(observations, "flow.bind", KeyValues.empty(), Flux.just(1)))
                .isInstanceOf(Flux.class);
        assertThat(ReactorOutcomes.bind(observations, "flow.bind", KeyValues.empty(), "not a publisher"))
                .isEqualTo("not a publisher");
        assertThat(ReactorOutcomes.isReactiveReturn(Mono.class)).isTrue();
        assertThat(ReactorOutcomes.isReactiveReturn(String.class)).isFalse();
        assertThat(ReactorOutcomes.isReactiveReturn(null)).isFalse();
    }
}
