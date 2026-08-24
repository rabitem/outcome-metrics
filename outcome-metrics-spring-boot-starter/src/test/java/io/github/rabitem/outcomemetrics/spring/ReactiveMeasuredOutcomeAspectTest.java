package io.github.rabitem.outcomemetrics.spring;

import io.github.rabitem.outcomemetrics.MeasuredOutcome;
import io.github.rabitem.outcomemetrics.observation.OutcomeObservations;
import io.micrometer.core.instrument.observation.DefaultMeterObservationHandler;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("MeasuredOutcomeAspect reactive binding")
class ReactiveMeasuredOutcomeAspectTest {

    private SimpleMeterRegistry meters;
    private MeasuredOutcomeAspect aspect;

    @BeforeEach
    void setUp() {
        meters = new SimpleMeterRegistry();
        final ObservationRegistry observationRegistry = ObservationRegistry.create();
        observationRegistry.observationConfig().observationHandler(new DefaultMeterObservationHandler(meters));
        aspect = new MeasuredOutcomeAspect(new OutcomeObservations(observationRegistry));
    }

    @Test
    @DisplayName("binds Mono returns to the terminal signal instead of assembly")
    void terminalBinding() throws Throwable {
        final ReactiveService target = new ReactiveService();
        final ProceedingJoinPoint joinPoint = joinPoint(target, ReactiveService.class.getMethod("fetch"));
        when(joinPoint.proceed()).thenReturn(Mono.just("value"));

        final Object result = aspect.observe(joinPoint);

        // the annotation no longer lies: assembly alone records nothing
        assertThat(result).isInstanceOf(Mono.class);
        assertThat(meters.find("reactive.op").timer()).isNull();

        StepVerifier.create((Mono<?>) result).expectNextMatches("value"::equals).verifyComplete();
        assertThat(meters.get("reactive.op")
                .tag("outcome", "success")
                .tag("channel", "web")
                .timer().count()).isEqualTo(1);
    }

    @Test
    @DisplayName("records reactive errors at the terminal signal")
    void terminalError() throws Throwable {
        final ReactiveService target = new ReactiveService();
        final ProceedingJoinPoint joinPoint = joinPoint(target, ReactiveService.class.getMethod("fetch"));
        when(joinPoint.proceed()).thenReturn(Mono.error(new IllegalStateException("downstream")));

        final Object result = aspect.observe(joinPoint);
        StepVerifier.create((Mono<?>) result).verifyError(IllegalStateException.class);

        assertThat(meters.get("reactive.op")
                .tag("outcome", "failure")
                .tag("reason", "unknown")
                .timer().count()).isEqualTo(1);
    }

    private ProceedingJoinPoint joinPoint(final Object target, final Method method) {
        final ProceedingJoinPoint joinPoint = mock(ProceedingJoinPoint.class);
        final MethodSignature signature = mock(MethodSignature.class);
        when(joinPoint.getTarget()).thenReturn(target);
        when(joinPoint.getSignature()).thenReturn(signature);
        when(signature.getMethod()).thenReturn(method);
        return joinPoint;
    }

    private static final class ReactiveService {

        @MeasuredOutcome(name = "reactive.op", tags = "channel=web")
        public Mono<String> fetch() {
            return Mono.just("value");
        }
    }
}
