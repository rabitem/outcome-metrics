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

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("MeasuredOutcomeAspect")
class MeasuredOutcomeAspectTest {

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
    @DisplayName("records annotated method invocations with class and method tags")
    void observeSuccess() throws Throwable {
        final AnnotatedService target = new AnnotatedService();
        final ProceedingJoinPoint joinPoint = joinPoint(target, AnnotatedService.class.getMethod("run"));
        when(joinPoint.proceed()).thenReturn("done");

        final Object result = aspect.observe(joinPoint);

        assertThat(result).isEqualTo("done");
        assertThat(meters.get("method.op")
                .tag("target", "admin")
                .tag("operation", "run")
                .tag("outcome", "success")
                .tag("reason", "none")
                .timer().count()).isEqualTo(1);
    }

    @Test
    @DisplayName("records failures and rethrows the original exception")
    void observeFailure() throws Throwable {
        final AnnotatedService target = new AnnotatedService();
        final ProceedingJoinPoint joinPoint = joinPoint(target, AnnotatedService.class.getMethod("run"));
        when(joinPoint.proceed()).thenThrow(new IllegalStateException("boom"));

        assertThatThrownBy(() -> aspect.observe(joinPoint))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("boom");

        assertThat(meters.get("method.op")
                .tag("outcome", "failure")
                .tag("reason", "unknown")
                .timer().count()).isEqualTo(1);
    }

    @Test
    @DisplayName("requires an explicit observation name")
    void missingName() throws NoSuchMethodException {
        final NamelessService target = new NamelessService();
        final ProceedingJoinPoint joinPoint = joinPoint(target, NamelessService.class.getMethod("run"));

        assertThatThrownBy(() -> aspect.observe(joinPoint))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("@MeasuredOutcome requires an explicit observation name");
    }

    @Test
    @DisplayName("falls back to the type-level name and tags when the method is unannotated")
    void typeLevelFallback() throws Throwable {
        final TypeOnlyService target = new TypeOnlyService();
        final ProceedingJoinPoint joinPoint = joinPoint(target, TypeOnlyService.class.getMethod("run"));
        when(joinPoint.proceed()).thenReturn("done");

        final Object result = aspect.observe(joinPoint);

        assertThat(result).isEqualTo("done");
        assertThat(meters.get("type.op")
                .tag("target", "admin")
                .tag("outcome", "success")
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

    @MeasuredOutcome(name = "type.op", tags = "target=admin")
    private static final class AnnotatedService {

        @MeasuredOutcome(name = "method.op", tags = "operation=run")
        public String run() {
            return "done";
        }
    }

    @MeasuredOutcome(tags = "target=admin")
    private static final class NamelessService {

        public String run() {
            return "done";
        }
    }

    @MeasuredOutcome(name = "type.op", tags = "target=admin")
    private static final class TypeOnlyService {

        public String run() {
            return "done";
        }
    }
}
