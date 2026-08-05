package io.github.rabitem.outcomemetrics.spring;

import io.github.rabitem.outcomemetrics.MeasuredOutcome;
import io.github.rabitem.outcomemetrics.observation.OutcomeObservations;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.observation.DefaultMeterObservationHandler;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import static org.assertj.core.api.Assertions.assertThat;

@SpringJUnitConfig(MeasuredOutcomeSpringAopIT.TestConfig.class)
@DisplayName("MeasuredOutcome Spring AOP IT")
class MeasuredOutcomeSpringAopIT {

    @org.springframework.beans.factory.annotation.Autowired
    private SampleService sampleService;

    @org.springframework.beans.factory.annotation.Autowired
    private MeterRegistry meterRegistry;

    @Test
    @DisplayName("proxies annotated Spring beans and records outcome timers")
    void recordsThroughSpringProxy() {
        assertThat(sampleService.run()).isEqualTo("ok");
        assertThat(meterRegistry.get("demo.op")
                .tag("outcome", "success")
                .tag("reason", "none")
                .timer()
                .count()).isEqualTo(1);
    }

    @Configuration
    @EnableAspectJAutoProxy(proxyTargetClass = true)
    static class TestConfig {

        @Bean
        SimpleMeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }

        @Bean
        ObservationRegistry observationRegistry(final SimpleMeterRegistry meterRegistry) {
            final ObservationRegistry registry = ObservationRegistry.create();
            registry.observationConfig().observationHandler(new DefaultMeterObservationHandler(meterRegistry));
            return registry;
        }

        @Bean
        OutcomeObservations outcomeObservations(final ObservationRegistry observationRegistry) {
            return new OutcomeObservations(observationRegistry);
        }

        @Bean
        MeasuredOutcomeAspect measuredOutcomeAspect(final OutcomeObservations outcomeObservations) {
            return new MeasuredOutcomeAspect(outcomeObservations);
        }

        @Bean
        SampleService sampleService() {
            return new SampleService();
        }
    }

    static class SampleService {
        @MeasuredOutcome(name = "demo.op")
        public String run() {
            return "ok";
        }
    }
}
