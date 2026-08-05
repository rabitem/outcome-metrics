package io.github.rabitem.outcomemetrics.quarkus.deployment;

import io.github.rabitem.outcomemetrics.MeasuredOutcome;
import io.github.rabitem.outcomemetrics.quarkus.MeasuredOutcomeInterceptor;
import io.github.rabitem.outcomemetrics.quarkus.OutcomeMetricsProducer;
import io.quarkus.arc.deployment.AdditionalBeanBuildItem;
import io.quarkus.arc.deployment.InterceptorBindingRegistrarBuildItem;
import io.quarkus.arc.processor.InterceptorBindingRegistrar;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.builditem.FeatureBuildItem;

import java.util.List;
import java.util.Set;

/**
 * Registers outcome-metrics CDI beans and treats {@link MeasuredOutcome} as an interceptor binding.
 *
 * @since 0.1.0
 */
public class OutcomeMetricsProcessor {

    private static final String FEATURE = "outcome-metrics";

    @BuildStep
    FeatureBuildItem feature() {
        return new FeatureBuildItem(FEATURE);
    }

    @BuildStep
    AdditionalBeanBuildItem registerBeans() {
        return AdditionalBeanBuildItem.builder()
                .addBeanClasses(OutcomeMetricsProducer.class, MeasuredOutcomeInterceptor.class)
                .build();
    }

    @BuildStep
    InterceptorBindingRegistrarBuildItem registerMeasuredOutcomeBinding() {
        return new InterceptorBindingRegistrarBuildItem(new InterceptorBindingRegistrar() {
            @Override
            public List<InterceptorBinding> getAdditionalBindings() {
                return List.of(InterceptorBinding.of(MeasuredOutcome.class, Set.of("name", "tags")));
            }
        });
    }
}
