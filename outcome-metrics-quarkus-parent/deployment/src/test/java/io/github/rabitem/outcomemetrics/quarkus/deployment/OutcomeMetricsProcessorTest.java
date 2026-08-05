package io.github.rabitem.outcomemetrics.quarkus.deployment;

import io.github.rabitem.outcomemetrics.MeasuredOutcome;
import io.github.rabitem.outcomemetrics.observation.OutcomeObservations;
import io.micrometer.core.instrument.MeterRegistry;
import io.quarkus.test.QuarkusUnitTest;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("OutcomeMetrics Quarkus extension")
class OutcomeMetricsProcessorTest {

    @RegisterExtension
    static final QuarkusUnitTest TEST = new QuarkusUnitTest()
            .setArchiveProducer(() -> ShrinkWrap.create(JavaArchive.class)
                    .addClass(DemoService.class))
            .withConfigurationResource("application.properties");

    @Inject
    DemoService demoService;

    @Inject
    OutcomeObservations outcomeObservations;

    @Inject
    MeterRegistry meterRegistry;

    @Test
    @DisplayName("registers OutcomeObservations and intercepts @MeasuredOutcome")
    void interceptsAnnotatedBean() {
        assertThat(outcomeObservations).isNotNull();
        assertThat(demoService.run()).isEqualTo("ok");
        assertThat(meterRegistry.find("demo.op").timers()).isNotEmpty();
    }

    @ApplicationScoped
    public static class DemoService {
        @MeasuredOutcome(name = "demo.op")
        public String run() {
            return "ok";
        }
    }
}
