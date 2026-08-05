package io.github.rabitem.outcomemetrics.spring;

import io.github.rabitem.outcomemetrics.MeterTagLimit;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Configuration properties for outcome metrics support.
 *
 * @since 0.1.0
 */
@ConfigurationProperties(prefix = "outcome.metrics")
public class OutcomeMetricsProperties {

    private boolean enabled = true;
    private int maxMeters = 50_000;
    private final Cache cache = new Cache();
    private final Interception annotation = new Interception();
    private final List<TagLimit> tagLimits = new ArrayList<>();

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

        private String meterNamePrefix;
        private String tagKey;
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
