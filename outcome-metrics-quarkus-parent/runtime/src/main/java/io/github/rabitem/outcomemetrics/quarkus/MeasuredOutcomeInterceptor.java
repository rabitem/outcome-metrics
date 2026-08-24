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

    private static final boolean MUTINY_BINDING_AVAILABLE = mutinyBindingPresent();

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
        final String name = MeasuredOutcomeSupport.resolveName(typeAnnotation, methodAnnotation);
        final var tags = MeasuredOutcomeSupport.resolveTags(typeAnnotation, methodAnnotation);
        if (MUTINY_BINDING_AVAILABLE && MutinyBinding.isReactive(method.getReturnType())) {
            // Terminal-signal binding (#39/#81): observing assembly would stamp success before
            // anything ran. Assembly runs unobserved; each subscription settles at its terminal.
            return MutinyBinding.bind(outcomeObservations, name, tags, context.proceed());
        }
        try {
            return outcomeObservations.recordChecked(
                    name,
                    tags,
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

    private static boolean mutinyBindingPresent() {
        try {
            Class.forName("io.github.rabitem.outcomemetrics.mutiny.MutinyOutcomes", false,
                    MeasuredOutcomeInterceptor.class.getClassLoader());
            Class.forName("io.smallrye.mutiny.Uni", false,
                    MeasuredOutcomeInterceptor.class.getClassLoader());
            return true;
        } catch (final ClassNotFoundException | LinkageError absent) {
            return false;
        }
    }

    /**
     * Loaded only when the optional {@code outcome-metrics-mutiny} module is on the classpath.
     */
    private static final class MutinyBinding {

        private MutinyBinding() {
        }

        static boolean isReactive(final Class<?> returnType) {
            return io.github.rabitem.outcomemetrics.mutiny.MutinyOutcomes.isReactiveReturn(returnType);
        }

        static Object bind(
                final OutcomeObservations observations,
                final String name,
                final io.micrometer.common.KeyValues tags,
                final Object pipeline) {
            return io.github.rabitem.outcomemetrics.mutiny.MutinyOutcomes.bind(
                    observations, name, tags, pipeline);
        }
    }
}
