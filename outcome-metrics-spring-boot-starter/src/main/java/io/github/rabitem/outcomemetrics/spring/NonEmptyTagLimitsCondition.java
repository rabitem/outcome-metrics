package io.github.rabitem.outcomemetrics.spring;

import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

import java.util.List;
import java.util.Map;

/**
 * Matches when {@code outcome.metrics.tag-limits} binds to a non-empty list.
 */
final class NonEmptyTagLimitsCondition implements Condition {

    @Override
    public boolean matches(final ConditionContext context, final AnnotatedTypeMetadata metadata) {
        return Binder.get(context.getEnvironment())
                .bind("outcome.metrics.tag-limits", Bindable.listOf(Map.class))
                .orElse(List.of())
                .stream()
                .anyMatch(limit -> !limit.isEmpty());
    }
}
