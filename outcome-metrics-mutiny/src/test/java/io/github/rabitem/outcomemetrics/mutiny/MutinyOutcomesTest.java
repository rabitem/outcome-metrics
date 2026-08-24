package io.github.rabitem.outcomemetrics.mutiny;

import io.github.rabitem.outcomemetrics.observation.OutcomeObservations;
import io.micrometer.common.KeyValues;
import io.micrometer.core.instrument.observation.DefaultMeterObservationHandler;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.helpers.test.AssertSubscriber;
import io.smallrye.mutiny.helpers.test.UniAssertSubscriber;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("MutinyOutcomes")
class MutinyOutcomesTest {

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
        final Uni<String> bound = MutinyOutcomes.record(
                observations, "uni.op", KeyValues.of("channel", "web"), Uni.createFrom().item("value"));

        assertThat(meters.find("uni.op").timer()).isNull();

        bound.subscribe().withSubscriber(UniAssertSubscriber.create())
                .assertCompleted().assertItem("value");
        assertThat(meters.get("uni.op")
                .tag("outcome", "success").tag("channel", "web")
                .timer().count()).isEqualTo(1);
    }

    @Test
    @DisplayName("records failures, null items, and cancellations correctly")
    void terminals() {
        MutinyOutcomes.record(observations, "uni.fail", KeyValues.empty(),
                        Uni.createFrom().failure(new IllegalStateException("boom")))
                .subscribe().withSubscriber(UniAssertSubscriber.create()).assertFailed();
        MutinyOutcomes.record(observations, "uni.nullitem", KeyValues.empty(),
                        Uni.createFrom().nullItem())
                .subscribe().withSubscriber(UniAssertSubscriber.create()).assertCompleted();
        MutinyOutcomes.record(observations, "uni.cancel", KeyValues.empty(),
                        Uni.createFrom().<String>nothing())
                .subscribe().withSubscriber(UniAssertSubscriber.create()).cancel();

        assertThat(meters.get("uni.fail")
                .tag("outcome", "failure").tag("reason", "unknown").timer().count()).isEqualTo(1);
        assertThat(meters.get("uni.nullitem")
                .tag("outcome", "success").timer().count()).isEqualTo(1);
        assertThat(meters.get("uni.cancel")
                .tag("outcome", "failure").tag("reason", "cancelled").tag("alertability", "none")
                .timer().count()).isEqualTo(1);
    }

    @Test
    @DisplayName("observes each subscription: a retry counts as two attempts")
    void oneObservationPerSubscription() {
        final AtomicInteger calls = new AtomicInteger();
        final Uni<String> flaky = MutinyOutcomes.record(observations, "uni.retry", KeyValues.empty(),
                Uni.createFrom().deferred(() -> calls.incrementAndGet() == 1
                        ? Uni.createFrom().failure(new IllegalStateException("first fails"))
                        : Uni.createFrom().item("ok")));

        flaky.onFailure().retry().atMost(1)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .assertCompleted().assertItem("ok");

        assertThat(meters.get("uni.retry").tag("outcome", "failure").timer().count()).isEqualTo(1);
        assertThat(meters.get("uni.retry").tag("outcome", "success").timer().count()).isEqualTo(1);
    }

    @Test
    @DisplayName("binds multis with completion after all items")
    void multiCompletion() {
        MutinyOutcomes.record(observations, "multi.op", KeyValues.empty(),
                        Multi.createFrom().items(1, 2, 3))
                .subscribe().withSubscriber(AssertSubscriber.create(3))
                .assertCompleted().assertItems(1, 2, 3);

        assertThat(meters.get("multi.op").tag("outcome", "success").timer().count()).isEqualTo(1);
    }

    @Test
    @DisplayName("bind dispatches by concrete type and passes unknowns through")
    void bindDispatch() {
        assertThat(MutinyOutcomes.bind(observations, "b", KeyValues.empty(), Uni.createFrom().item(1)))
                .isInstanceOf(Uni.class);
        assertThat(MutinyOutcomes.bind(observations, "b", KeyValues.empty(), Multi.createFrom().item(1)))
                .isInstanceOf(Multi.class);
        assertThat(MutinyOutcomes.bind(observations, "b", KeyValues.empty(), "plain"))
                .isEqualTo("plain");
        assertThat(MutinyOutcomes.isReactiveReturn(Uni.class)).isTrue();
        assertThat(MutinyOutcomes.isReactiveReturn(Multi.class)).isTrue();
        assertThat(MutinyOutcomes.isReactiveReturn(String.class)).isFalse();
        assertThat(MutinyOutcomes.isReactiveReturn(null)).isFalse();
    }
}
