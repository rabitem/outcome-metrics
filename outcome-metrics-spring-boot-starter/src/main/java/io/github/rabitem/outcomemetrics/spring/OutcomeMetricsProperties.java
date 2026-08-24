package io.github.rabitem.outcomemetrics.spring;

import io.github.rabitem.outcomemetrics.MeterTagLimit;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Configuration properties for outcome metrics support.
 *
 * @since 0.1.0
 */
@Validated
@ConfigurationProperties(prefix = "outcome.metrics")
public class OutcomeMetricsProperties {

    private boolean enabled = true;

    @Min(1)
    private int maxMeters = 50_000;

    @Valid
    private final Cache cache = new Cache();

    private final Scope scope = new Scope();

    private final ReasonBudgetProperties reasonBudget = new ReasonBudgetProperties();

    private final ReasonRegistryProperties reasonRegistry = new ReasonRegistryProperties();

    private final CombinationGuardProperties combinationGuard = new CombinationGuardProperties();

    private final PrivacyProperties privacy = new PrivacyProperties();

    @Valid
    private final Interception annotation = new Interception();

    private final List<@Valid TagLimit> tagLimits = new ArrayList<>();

    /**
     * Returns whether outcome metrics auto-configuration is enabled.
     *
     * @return {@code true} when enabled
     */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Sets whether outcome metrics auto-configuration is enabled.
     *
     * @param enabled {@code true} to enable
     */
    public void setEnabled(final boolean enabled) {
        this.enabled = enabled;
    }

    /**
     * Returns the total meter ceiling.
     *
     * @return max meters
     */
    public int getMaxMeters() {
        return maxMeters;
    }

    /**
     * Sets the total meter ceiling.
     *
     * @param maxMeters maximum number of meters; must be positive
     */
    public void setMaxMeters(final int maxMeters) {
        this.maxMeters = maxMeters;
    }

    /**
     * Returns cache meter filter properties.
     *
     * @return cache properties, never {@code null}
     */
    public Cache getCache() {
        return cache;
    }

    /**
     * Returns {@code @MeasuredOutcome} interception settings.
     *
     * <p>Bound from {@code outcome.metrics.annotation.*}.
     *
     * @return interception properties, never {@code null}
     */
    public Interception getAnnotation() {
        return annotation;
    }

    /**
     * Returns tag-value cardinality limits.
     *
     * @return mutable tag limits list, never {@code null}
     */
    public List<TagLimit> getTagLimits() {
        return tagLimits;
    }

    /**
     * Converts configured tag limits to support API records.
     *
     * @return tag limits, never {@code null}
     */
    public List<MeterTagLimit> meterTagLimits() {
        return tagLimits.stream()
                .map(limit -> new MeterTagLimit(
                        limit.getMeterNamePrefix(),
                        limit.getTagKey(),
                        limit.getMaximumValues()))
                .toList();
    }

    /**
     * Cache metric normalization settings.
     */
    /**
     * Returns the request-scope settings.
     *
     * @return scope settings, never {@code null}
     */
    public Scope getScope() {
        return scope;
    }

    /**
     * Returns the reason-budget settings.
     *
     * @return reason budget settings, never {@code null}
     */
    public ReasonBudgetProperties getReasonBudget() {
        return reasonBudget;
    }

    /**
     * Returns the reason-registry settings.
     *
     * @return reason registry settings, never {@code null}
     */
    public ReasonRegistryProperties getReasonRegistry() {
        return reasonRegistry;
    }

    /**
     * Returns the combination-guard settings.
     *
     * @return combination guard settings, never {@code null}
     */
    public CombinationGuardProperties getCombinationGuard() {
        return combinationGuard;
    }

    /**
     * Returns the tag-privacy settings.
     *
     * @return privacy settings, never {@code null}
     */
    public PrivacyProperties getPrivacy() {
        return privacy;
    }

    /**
     * Reason cardinality budget settings (#19); the built bean stays injectable for runtime
     * expand/collapse wiring.
     *
     * @since 0.1.0
     */
    public static class ReasonBudgetProperties {

        private boolean enabled;
        private int collapsedLimit = 8;
        private int expandedLimit = 64;

        /** @return {@code true} when a reason budget is composed */
        public boolean isEnabled() {
            return enabled;
        }

        /** @param enabled {@code true} to compose a reason budget */
        public void setEnabled(final boolean enabled) {
            this.enabled = enabled;
        }

        /** @return distinct codes admitted per name when collapsed */
        public int getCollapsedLimit() {
            return collapsedLimit;
        }

        /** @param collapsedLimit collapsed admission limit */
        public void setCollapsedLimit(final int collapsedLimit) {
            this.collapsedLimit = collapsedLimit;
        }

        /** @return distinct codes admitted per name when expanded */
        public int getExpandedLimit() {
            return expandedLimit;
        }

        /** @param expandedLimit expanded admission limit */
        public void setExpandedLimit(final int expandedLimit) {
            this.expandedLimit = expandedLimit;
        }
    }

    /**
     * Reason vocabulary registry settings (#24). Literal codes only; enum vocabularies are wired as
     * a {@code ReasonRegistry} bean instead.
     *
     * @since 0.1.0
     */
    public static class ReasonRegistryProperties {

        private List<String> codes = new ArrayList<>();

        /** @return registered literal reason codes; empty disables the registry */
        public List<String> getCodes() {
            return codes;
        }

        /** @param codes registered literal reason codes */
        public void setCodes(final List<String> codes) {
            this.codes = codes;
        }
    }

    /**
     * Combination cardinality guard settings (#26).
     *
     * @since 0.1.0
     */
    public static class CombinationGuardProperties {

        private List<String> keys = new ArrayList<>();
        private int minSupport = 20;
        private Duration window = Duration.ofMinutes(15);
        private List<String> namePrefixes = new ArrayList<>();

        /** @return guarded tag keys; empty disables the guard */
        public List<String> getKeys() {
            return keys;
        }

        /** @param keys guarded tag keys */
        public void setKeys(final List<String> keys) {
            this.keys = keys;
        }

        /** @return minimum events per window before a combination reveals */
        public int getMinSupport() {
            return minSupport;
        }

        /** @param minSupport minimum support */
        public void setMinSupport(final int minSupport) {
            this.minSupport = minSupport;
        }

        /** @return tumbling support window */
        public Duration getWindow() {
            return window;
        }

        /** @param window tumbling support window */
        public void setWindow(final Duration window) {
            this.window = window;
        }

        /** @return meter-name prefixes in scope; empty means all names */
        public List<String> getNamePrefixes() {
            return namePrefixes;
        }

        /** @param namePrefixes meter-name prefixes in scope */
        public void setNamePrefixes(final List<String> namePrefixes) {
            this.namePrefixes = namePrefixes;
        }
    }

    /**
     * Tag PII sentinel settings (#29).
     *
     * @since 0.1.0
     */
    public static class PrivacyProperties {

        private boolean enabled;
        private boolean saasDefaults = true;
        private List<String> denyKeys = new ArrayList<>();

        /** @return {@code true} when the privacy policy is composed */
        public boolean isEnabled() {
            return enabled;
        }

        /** @param enabled {@code true} to compose the privacy policy */
        public void setEnabled(final boolean enabled) {
            this.enabled = enabled;
        }

        /** @return whether the SaaS default deny list is included */
        public boolean isSaasDefaults() {
            return saasDefaults;
        }

        /** @param saasDefaults include the SaaS default deny list */
        public void setSaasDefaults(final boolean saasDefaults) {
            this.saasDefaults = saasDefaults;
        }

        /** @return additional deny-listed keys */
        public List<String> getDenyKeys() {
            return denyKeys;
        }

        /** @param denyKeys additional deny-listed keys */
        public void setDenyKeys(final List<String> denyKeys) {
            this.denyKeys = denyKeys;
        }
    }

    /**
     * Request-scoped outcome coalescing settings (issues #18/#54).
     *
     * @since 0.1.0
     */
    public static class Scope {

        private boolean enabled;

        /**
         * Returns whether a per-request {@code OutcomeScope} servlet filter is registered.
         *
         * <p>Off by default: enabling changes the {@code occurrence} split on existing series.
         *
         * @return {@code true} when enabled
         */
        public boolean isEnabled() {
            return enabled;
        }

        /**
         * Sets whether the per-request scope filter is registered.
         *
         * @param enabled {@code true} to enable
         */
        public void setEnabled(final boolean enabled) {
            this.enabled = enabled;
        }
    }

    public static class Cache {

        private boolean normalizeTags = true;
        private Set<String> removedTags = new LinkedHashSet<>(Set.of("cache.manager", "name"));

        /**
         * Returns whether cache tag normalization is enabled.
         *
         * @return {@code true} when enabled
         */
        public boolean isNormalizeTags() {
            return normalizeTags;
        }

        /**
         * Sets whether cache tag normalization is enabled.
         *
         * @param normalizeTags {@code true} to enable
         */
        public void setNormalizeTags(final boolean normalizeTags) {
            this.normalizeTags = normalizeTags;
        }

        /**
         * Returns cache tag keys removed from {@code cache.*} meters.
         *
         * @return removed tag keys, never {@code null}
         */
        public Set<String> getRemovedTags() {
            return removedTags;
        }

        /**
         * Sets cache tag keys removed from {@code cache.*} meters.
         *
         * @param removedTags removed tag keys; {@code null} becomes empty
         */
        public void setRemovedTags(final Set<String> removedTags) {
            this.removedTags = removedTags == null ? new LinkedHashSet<>() : new LinkedHashSet<>(removedTags);
        }
    }

    /**
     * {@code @MeasuredOutcome} interception settings.
     */
    public static class Interception {

        private boolean enabled = true;

        /**
         * Returns whether {@code @MeasuredOutcome} interception is enabled.
         *
         * @return {@code true} when enabled
         */
        public boolean isEnabled() {
            return enabled;
        }

        /**
         * Sets whether {@code @MeasuredOutcome} interception is enabled.
         *
         * @param enabled {@code true} to enable
         */
        public void setEnabled(final boolean enabled) {
            this.enabled = enabled;
        }
    }

    /**
     * One configured tag-value cardinality limit.
     */
    public static class TagLimit {

        @NotBlank
        private String meterNamePrefix;

        @NotBlank
        private String tagKey;

        @Min(1)
        private int maximumValues = 100;

        /**
         * Returns the metric-name prefix.
         *
         * @return prefix, may be {@code null} before binding validation
         */
        public String getMeterNamePrefix() {
            return meterNamePrefix;
        }

        /**
         * Sets the metric-name prefix.
         *
         * @param meterNamePrefix metric prefix
         */
        public void setMeterNamePrefix(final String meterNamePrefix) {
            this.meterNamePrefix = meterNamePrefix;
        }

        /**
         * Returns the tag key to count.
         *
         * @return tag key, may be {@code null} before binding validation
         */
        public String getTagKey() {
            return tagKey;
        }

        /**
         * Sets the tag key to count.
         *
         * @param tagKey tag key
         */
        public void setTagKey(final String tagKey) {
            this.tagKey = tagKey;
        }

        /**
         * Returns the maximum distinct tag values.
         *
         * @return maximum values
         */
        public int getMaximumValues() {
            return maximumValues;
        }

        /**
         * Sets the maximum distinct tag values.
         *
         * @param maximumValues maximum values; must be positive when used
         */
        public void setMaximumValues(final int maximumValues) {
            this.maximumValues = maximumValues;
        }
    }
}
