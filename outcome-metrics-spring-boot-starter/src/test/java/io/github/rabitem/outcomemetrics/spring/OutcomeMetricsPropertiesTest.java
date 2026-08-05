package io.github.rabitem.outcomemetrics.spring;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("OutcomeMetricsProperties")
class OutcomeMetricsPropertiesTest {

    @Test
    @DisplayName("cache settings round-trip and normalize a null removed-tag set to empty")
    void cacheAccessors() {
        final OutcomeMetricsProperties.Cache cache = new OutcomeMetricsProperties.Cache();

        assertThat(cache.isNormalizeTags()).isTrue();
        cache.setNormalizeTags(false);
        assertThat(cache.isNormalizeTags()).isFalse();

        cache.setRemovedTags(Set.of("cache.manager", "name"));
        assertThat(cache.getRemovedTags()).containsExactlyInAnyOrder("cache.manager", "name");

        cache.setRemovedTags(null);
        assertThat(cache.getRemovedTags()).isEmpty();
    }

    @Test
    @DisplayName("annotation interception enablement round-trips")
    void interceptionAccessors() {
        final OutcomeMetricsProperties.Interception interception = new OutcomeMetricsProperties.Interception();

        assertThat(interception.isEnabled()).isTrue();
        interception.setEnabled(false);
        assertThat(interception.isEnabled()).isFalse();
    }
}
