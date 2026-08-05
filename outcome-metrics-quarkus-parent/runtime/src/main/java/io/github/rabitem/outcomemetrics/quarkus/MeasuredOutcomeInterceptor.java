package io.github.rabitem.outcomemetrics.quarkus;

import io.github.rabitem.outcomemetrics.MeasuredOutcome;
import io.github.rabitem.outcomemetrics.MeasuredOutcomeSupport;
import io.github.rabitem.outcomemetrics.observation.CheckedSupplier;
import io.github.rabitem.outcomemetrics.observation.OutcomeObservations;
import jakarta.annotation.Priority;
import jakarta.inject.Inject;
import jakarta.interceptor.AroundInvoke;
import jakarta.interceptor.Interceptor;
import jakarta.interceptor.InvocationContext;

import java.lang.reflect.Method;

/**
 * CDI interceptor for {@link MeasuredOutcome}.
 *
 * @since 0.1.0
 */
@MeasuredOutcome
@Priority(Interceptor.Priority.LIBRARY_BEFORE + 20)
@Interceptor
public class MeasuredOutcomeInterceptor {

    private final OutcomeObservations outcomeObservations;
    private final OutcomeMetricsConfig config;

    /**
     * Creates the interceptor.
     *
     * @param outcomeObservations observation helper
     * @param config              runtime configuration
     */
    @Inject
    public MeasuredOutcomeInterceptor(
            final OutcomeObservations outcomeObservations,
            final OutcomeMetricsConfig config) {
        this.outcomeObservations = outcomeObservations;
        this.config = config;
    }

    /**
     * Records annotated method invocations as outcome observations.
     *
     * @param context invocation context
     * @return method result
     * @throws Exception if the intercepted method throws a checked {@link Exception}
     */
    @AroundInvoke
    public Object observe(final InvocationContext context) throws Exception {
        if (!config.enabled() || !config.annotation().enabled()) {
            return context.proceed();
        }
        final Method method = context.getMethod();
        final MeasuredOutcome typeAnnotation = MeasuredOutcomeSupport.findTypeAnnotation(method.getDeclaringClass());
        final MeasuredOutcome methodAnnotation = MeasuredOutcomeSupport.findMethodAnnotation(method);
        try {
            return outcomeObservations.recordChecked(
                    MeasuredOutcomeSupport.resolveName(typeAnnotation, methodAnnotation),
                    MeasuredOutcomeSupport.resolveTags(typeAnnotation, methodAnnotation),
                    (CheckedSupplier<Object>) context::proceed);
        } catch (final Exception | Error ex) {
            throw ex;
        } catch (final Throwable throwable) {
            throw new Exception(throwable);
        }
    }
}
