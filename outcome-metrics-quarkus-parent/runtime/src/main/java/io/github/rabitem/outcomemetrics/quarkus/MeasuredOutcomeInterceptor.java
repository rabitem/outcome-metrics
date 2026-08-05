package io.github.rabitem.outcomemetrics.quarkus;

import io.github.rabitem.outcomemetrics.MeasuredOutcome;
import io.github.rabitem.outcomemetrics.MeasuredOutcomeSupport;
import io.github.rabitem.outcomemetrics.observation.CheckedSupplier;
import io.github.rabitem.outcomemetrics.observation.OutcomeObservations;
import io.quarkus.arc.lookup.LookupIfProperty;
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
@LookupIfProperty.List({
        @LookupIfProperty(name = "outcome.metrics.enabled", stringValue = "true", lookupIfMissing = true),
        @LookupIfProperty(name = "outcome.metrics.annotation.enabled", stringValue = "true", lookupIfMissing = true)
})
public class MeasuredOutcomeInterceptor {

    private final OutcomeObservations outcomeObservations;

    /**
     * Creates the interceptor.
     *
     * @param outcomeObservations observation helper
     */
    @Inject
    public MeasuredOutcomeInterceptor(final OutcomeObservations outcomeObservations) {
        this.outcomeObservations = outcomeObservations;
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
        final Method method = context.getMethod();
        final MeasuredOutcome typeAnnotation = MeasuredOutcomeSupport.findTypeAnnotation(method.getDeclaringClass());
        final MeasuredOutcome methodAnnotation = MeasuredOutcomeSupport.findMethodAnnotation(method);
        try {
            return outcomeObservations.recordChecked(
                    MeasuredOutcomeSupport.resolveName(typeAnnotation, methodAnnotation),
                    MeasuredOutcomeSupport.resolveTags(typeAnnotation, methodAnnotation),
                    (CheckedSupplier<Object>) context::proceed);
        } catch (final Exception ex) {
            throw ex;
        } catch (final Throwable throwable) {
            return sneakyThrow(throwable);
        }
    }

    @SuppressWarnings("unchecked")
    private static <T, E extends Throwable> T sneakyThrow(final Throwable throwable) throws E {
        throw (E) throwable;
    }
}
