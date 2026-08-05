package io.github.rabitem.outcomemetrics.quarkus;

import io.quarkus.runtime.annotations.ConfigPhase;
import io.quarkus.runtime.annotations.ConfigRoot;
import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import io.smallrye.config.WithName;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Quarkus configuration for outcome metrics ({@code outcome.metrics.*}).
 *
 * @since 0.1.0
 */
@ConfigMapping(prefix = "outcome.metrics")
@ConfigRoot(phase = ConfigPhase.RUN_TIME)
public interface OutcomeMetricsConfig {

    /**
     * Whether outcome metrics beans and filters are active.
     *
     * @return {@code true} when enabled
     */
    @WithDefault("true")
    boolean enabled();

    /**
     * Total meter ceiling registered with Micrometer.
     *
     * @return maximum meters
     */
    @WithDefault("50000")
    int maxMeters();

    /**
     * Cache meter tag normalization.
     *
     * @return cache settings
     */
    Cache cache();

    /**
     * {@code @MeasuredOutcome} interception settings.
     *
     * @return interception settings
     */
    @WithName("annotation")
    Interception annotation();

    /**
     * Per-prefix tag-value cardinality limits.
     *
     * @return configured tag limits
     */
    @WithName("tag-limits")
    List<TagLimit> tagLimits();

    /**
     * Cache metric normalization settings.
     */
    interface Cache {
        /**
         * Whether cache tag normalization is enabled.
         *
         * @return {@code true} when enabled
         */
        @WithDefault("true")
        boolean normalizeTags();

        /**
         * Cache tag keys removed from {@code cache.*} meters.
         *
         * @return removed tag keys
         */
        @WithDefault("cache.manager,name")
        Optional<Set<String>> removedTags();
    }

    /**
     * CDI interceptor settings for {@code @MeasuredOutcome}.
     */
    interface Interception {
        /**
         * Whether {@code @MeasuredOutcome} interception is enabled.
         *
         * @return {@code true} when enabled
         */
        @WithDefault("true")
        boolean enabled();
    }

    /**
     * One configured tag-value cardinality limit.
     */
    interface TagLimit {
        /**
         * Metric-name prefix this limit applies to.
         *
         * @return meter name prefix
         */
        String meterNamePrefix();

        /**
         * Tag key whose distinct values are counted.
         *
         * @return tag key
         */
        String tagKey();

        /**
         * Maximum distinct values allowed for the tag key.
         *
         * @return maximum values
         */
        @WithDefault("100")
        int maximumValues();
    }
}
