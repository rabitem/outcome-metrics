package io.github.rabitem.outcomemetrics.quarkus;

import io.quarkus.runtime.annotations.ConfigPhase;
import io.quarkus.runtime.annotations.ConfigRoot;
import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import io.smallrye.config.WithName;

import java.time.Duration;
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
     * Reason cardinality budget settings (#19).
     *
     * @return reason budget settings
     */
    @WithName("reason-budget")
    ReasonBudget reasonBudget();

    /**
     * Reason vocabulary registry settings (#24); literal codes only.
     *
     * @return reason registry settings
     */
    @WithName("reason-registry")
    ReasonRegistryConfig reasonRegistry();

    /**
     * Combination cardinality guard settings (#26).
     *
     * @return combination guard settings
     */
    @WithName("combination-guard")
    CombinationGuardConfig combinationGuard();

    /**
     * Tag PII sentinel settings (#29).
     *
     * @return privacy settings
     */
    Privacy privacy();

    /**
     * Reason cardinality budget settings.
     */
    interface ReasonBudget {
        /**
         * Whether a reason budget is composed.
         *
         * @return {@code true} when enabled
         */
        @WithDefault("false")
        boolean enabled();

        /**
         * Distinct codes admitted per name when collapsed.
         *
         * @return collapsed limit
         */
        @WithDefault("8")
        int collapsedLimit();

        /**
         * Distinct codes admitted per name when expanded.
         *
         * @return expanded limit
         */
        @WithDefault("64")
        int expandedLimit();
    }

    /**
     * Reason vocabulary registry settings.
     */
    interface ReasonRegistryConfig {
        /**
         * Registered literal reason codes; absent disables the registry.
         *
         * @return literal codes
         */
        Optional<List<String>> codes();
    }

    /**
     * Combination cardinality guard settings.
     */
    interface CombinationGuardConfig {
        /**
         * Guarded tag keys; absent disables the guard.
         *
         * @return guarded keys
         */
        Optional<List<String>> keys();

        /**
         * Minimum events per window before a combination reveals.
         *
         * @return minimum support
         */
        @WithDefault("20")
        int minSupport();

        /**
         * Tumbling support window.
         *
         * @return window
         */
        @WithDefault("PT15M")
        Duration window();

        /**
         * Meter-name prefixes in scope; absent means all names.
         *
         * @return name prefixes
         */
        Optional<List<String>> namePrefixes();
    }

    /**
     * Tag PII sentinel settings.
     */
    interface Privacy {
        /**
         * Whether the privacy policy is composed.
         *
         * @return {@code true} when enabled
         */
        @WithDefault("false")
        boolean enabled();

        /**
         * Whether the SaaS default deny list is included.
         *
         * @return {@code true} to include defaults
         */
        @WithDefault("true")
        boolean saasDefaults();

        /**
         * Additional deny-listed keys.
         *
         * @return extra deny keys
         */
        Optional<List<String>> denyKeys();
    }

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
