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
        return outcomeObservations.recordChecked(
                MeasuredOutcomeSupport.resolveName(typeAnnotation, methodAnnotation),
                MeasuredOutcomeSupport.resolveTags(typeAnnotation, methodAnnotation),
                (CheckedSupplier<Object>) joinPoint::proceed);
    }
}
