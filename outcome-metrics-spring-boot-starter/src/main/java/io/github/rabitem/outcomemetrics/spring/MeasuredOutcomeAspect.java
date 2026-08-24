package io.github.rabitem.outcomemetrics.spring;

import io.github.rabitem.outcomemetrics.MeasuredOutcome;
import io.github.rabitem.outcomemetrics.MeasuredOutcomeSupport;
import io.github.rabitem.outcomemetrics.observation.CheckedSupplier;
import io.github.rabitem.outcomemetrics.observation.OutcomeObservations;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.core.annotation.AnnotatedElementUtils;

import java.lang.reflect.Method;

/**
 * Spring AOP interceptor for {@link MeasuredOutcome}.
 *
 * @since 0.1.0
 */
@Aspect
public class MeasuredOutcomeAspect {

    private static final boolean REACTOR_BINDING_AVAILABLE = reactorBindingPresent();

    private final OutcomeObservations outcomeObservations;

    /**
     * Creates an aspect.
     *
     * @param outcomeObservations the observation helper used to record invocations; must not be {@code null}
     */
    public MeasuredOutcomeAspect(final OutcomeObservations outcomeObservations) {
        this.outcomeObservations = outcomeObservations;
    }

    /**
     * Records annotated method invocations as outcome observations.
     *
     * @param joinPoint the intercepted method invocation; must not be {@code null}
     * @return the value returned by the intercepted method, which may be {@code null}
     * @throws Throwable if the intercepted method throws
     */
    @Around("@within(io.github.rabitem.outcomemetrics.MeasuredOutcome) || "
            + "@annotation(io.github.rabitem.outcomemetrics.MeasuredOutcome)")
    public Object observe(final ProceedingJoinPoint joinPoint) throws Throwable {
        final Method method = ((MethodSignature) joinPoint.getSignature()).getMethod();
        final Class<?> targetClass = joinPoint.getTarget() != null
                ? joinPoint.getTarget().getClass()
                : method.getDeclaringClass();
        final MeasuredOutcome typeAnnotation = AnnotatedElementUtils.findMergedAnnotation(
                targetClass,
                MeasuredOutcome.class);
        final MeasuredOutcome methodAnnotation = AnnotatedElementUtils.findMergedAnnotation(method, MeasuredOutcome.class);
        final String name = MeasuredOutcomeSupport.resolveName(typeAnnotation, methodAnnotation);
        final var tags = MeasuredOutcomeSupport.resolveTags(typeAnnotation, methodAnnotation);
        if (REACTOR_BINDING_AVAILABLE && ReactorBinding.isReactive(method.getReturnType())) {
            // Terminal-signal binding: wrapping a publisher in observe() would time the assembly
            // and stamp success before anything ran. Assembly runs unobserved; each subscription
            // is observed and settles on onComplete/onError/cancel.
            return ReactorBinding.bind(outcomeObservations, name, tags, joinPoint.proceed());
        }
        return outcomeObservations.recordChecked(name, tags, (CheckedSupplier<Object>) joinPoint::proceed);
    }

    private static boolean reactorBindingPresent() {
        try {
            Class.forName("io.github.rabitem.outcomemetrics.reactor.ReactorOutcomes", false,
                    MeasuredOutcomeAspect.class.getClassLoader());
            Class.forName("reactor.core.publisher.Flux", false,
                    MeasuredOutcomeAspect.class.getClassLoader());
            return true;
        } catch (final ClassNotFoundException | LinkageError absent) {
            return false;
        }
    }

    /**
     * Loaded only when the optional {@code outcome-metrics-reactor} module is on the classpath.
     */
    private static final class ReactorBinding {

        private ReactorBinding() {
        }

        static boolean isReactive(final Class<?> returnType) {
            return io.github.rabitem.outcomemetrics.reactor.ReactorOutcomes.isReactiveReturn(returnType);
        }

        static Object bind(
                final OutcomeObservations observations,
                final String name,
                final io.micrometer.common.KeyValues tags,
                final Object publisher) {
            return io.github.rabitem.outcomemetrics.reactor.ReactorOutcomes.bind(
                    observations, name, tags, publisher);
        }
    }
}
