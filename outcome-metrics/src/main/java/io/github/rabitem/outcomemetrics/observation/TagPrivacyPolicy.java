package io.github.rabitem.outcomemetrics.observation;

import io.github.rabitem.outcomemetrics.MetricTagValues;
import io.micrometer.common.KeyValue;
import io.micrometer.common.KeyValues;
import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.jspecify.annotations.NonNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

/**
 * Opt-in PII sentinel for caller-supplied tag material.
 *
 * <p>{@code sanitizeTagValue} normalizes shape but does not detect identity data. This policy
 * redacts tag <em>values</em> — keys are always kept so label sets stay consistent — when the key
 * is on the deny list or the value trips a heuristic detector, and counts every redaction on
 * {@value #REDACTED_COUNTER_NAME}.
 *
 * <p><b>The deny list is the control; the detectors are the net.</b> Keys are code-controlled and
 * matched on their sanitized form ({@code userId}, {@code user-id} and {@code USER_ID} all match a
 * {@code user_id} entry). Detectors are best-effort: values that already passed
 * {@code sanitizeTagValue} have lost {@code @}, dots and casing, so email, JWT and IPv4 detection
 * only sees raw values, while UUID and long-hex detection tolerates {@code _}-mangling. A raw
 * dotted version string ({@code 1.2.3.4}) is indistinguishable from an IPv4 and redacts — use
 * {@code v1_2_3_4}-style tokens.
 *
 * <p>Runtime enforcement never throws (a privacy bug must not become an outage); use
 * {@link #violations(KeyValues)} in tests to fail builds instead. The policy scrubs caller-supplied
 * material only (dimensions and result tags); schema tags are closed vocabularies, and PII in
 * reason codes is the {@link ReasonRegistry}'s job. Wire via
 * {@code OutcomeObservationConvention.builder().tagPrivacyPolicy(policy)} — deliberately not a
 * MeterFilter, which would retain the raw value inside the registry's pre-filter id cache.
 *
 * @since 0.1.0
 */
public final class TagPrivacyPolicy implements MeterBinder {

    /** Counter of tag values redacted by this policy. */
    public static final String REDACTED_COUNTER_NAME = "outcome.metrics.tag_privacy.redacted";

    private static final Pattern UUID_LIKE = Pattern.compile(
            "[0-9a-fA-F]{8}[-_][0-9a-fA-F]{4}[-_][0-9a-fA-F]{4}[-_][0-9a-fA-F]{4}[-_][0-9a-fA-F]{12}");
    private static final Pattern LONG_HEX = Pattern.compile("[0-9a-fA-F]{16,}");
    private static final Pattern EMAIL = Pattern.compile("[^@\\s]+@[^@\\s]+\\.[^@\\s]+");
    private static final Pattern JWT = Pattern.compile("eyJ[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]*");
    private static final Pattern IPV4 = Pattern.compile("(?<![0-9])(?:\\d{1,3}\\.){3}\\d{1,3}(?![0-9])");
    private static final Pattern DIGIT_RUN = Pattern.compile("\\d{7,}");

    private final Set<String> denyKeys;
    private final AtomicLong redacted = new AtomicLong();

    private TagPrivacyPolicy(final Set<String> denyKeys) {
        this.denyKeys = Collections.unmodifiableSet(denyKeys);
    }

    /**
     * Creates a policy builder.
     *
     * @return a new builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Returns a policy with a starting deny list for typical SaaS identity keys.
     *
     * <p>Denied keys: {@code user_id}, {@code email}, {@code ip}, {@code username},
     * {@code session_id}, {@code device_id}, {@code phone}, {@code account_id}, {@code token},
     * {@code authorization}. Extend with {@link Builder#denyKeys}.
     *
     * @return a policy with the default deny list and all detectors
     */
    public static TagPrivacyPolicy saasDefaults() {
        return builder().denyKeys(
                "user_id", "email", "ip", "username", "session_id",
                "device_id", "phone", "account_id", "token", "authorization").build();
    }

    /**
     * Returns the deny-listed keys in sanitized form.
     *
     * @return unmodifiable sorted keys, for tests and attestation
     */
    public Set<String> denyKeys() {
        return denyKeys;
    }

    /**
     * Returns human-readable descriptions of every violation in the given tags.
     *
     * <p>Test hook (see #31): assert this is empty to fail builds on PII in tags instead of relying
     * on runtime redaction.
     *
     * @param tags tags to check; must not be {@code null}
     * @return violation descriptions, empty when clean
     */
    public List<String> violations(final KeyValues tags) {
        Objects.requireNonNull(tags, "tags must not be null");
        final List<String> found = new ArrayList<>();
        for (final KeyValue tag : tags) {
            final String reason = violation(tag);
            if (reason != null) {
                found.add(reason);
            }
        }
        return found;
    }

    /**
     * Scrubs the tags, optionally counting redactions (exactly once per observation).
     */
    KeyValues apply(final KeyValues tags, final boolean count) {
        List<KeyValue> scrubbed = null;
        int index = 0;
        for (final KeyValue tag : tags) {
            if (violation(tag) != null) {
                if (scrubbed == null) {
                    scrubbed = new ArrayList<>();
                    for (final KeyValue original : tags) {
                        scrubbed.add(original);
                    }
                }
                scrubbed.set(index, KeyValue.of(tag.getKey(), MetricTagValues.REDACTED));
                if (count) {
                    redacted.incrementAndGet();
                }
            }
            index++;
        }
        return scrubbed == null ? tags : KeyValues.of(scrubbed);
    }

    private String violation(final KeyValue tag) {
        if (denyKeys.contains(MetricTagValues.toSnakeCase(tag.getKey()))) {
            return "deny-listed key: " + tag.getKey();
        }
        final String value = tag.getValue();
        if (UUID_LIKE.matcher(value).find()) {
            return "UUID-shaped value in key: " + tag.getKey();
        }
        if (JWT.matcher(value).find()) {
            return "JWT-shaped value in key: " + tag.getKey();
        }
        if (EMAIL.matcher(value).find()) {
            return "email-shaped value in key: " + tag.getKey();
        }
        if (IPV4.matcher(value).find()) {
            return "IPv4-shaped value in key: " + tag.getKey();
        }
        if (LONG_HEX.matcher(value).find()) {
            return "long hexadecimal value in key: " + tag.getKey();
        }
        if (DIGIT_RUN.matcher(value).find()) {
            return "long digit run in key: " + tag.getKey();
        }
        return null;
    }

    @Override
    public void bindTo(final @NonNull MeterRegistry registry) {
        FunctionCounter.builder(REDACTED_COUNTER_NAME, this, policy -> policy.redacted.doubleValue())
                .description("Tag values redacted by the privacy policy")
                .register(registry);
    }

    /**
     * Builder for {@link TagPrivacyPolicy}.
     *
     * @since 0.1.0
     */
    public static final class Builder {

        private final Set<String> denyKeys = new TreeSet<>();

        private Builder() {
        }

        /**
         * Adds deny-listed tag keys (matched on sanitized form).
         *
         * @param keys keys whose values are always redacted; must not contain blanks
         * @return this builder
         */
        public Builder denyKeys(final String... keys) {
            Objects.requireNonNull(keys, "keys must not be null");
            for (final String key : keys) {
                if (key == null || key.isBlank()) {
                    throw new IllegalArgumentException("deny key must not be blank");
                }
                denyKeys.add(MetricTagValues.toSnakeCase(key));
            }
            return this;
        }

        /**
         * Builds the policy.
         *
         * @return an immutable policy
         */
        public TagPrivacyPolicy build() {
            return new TagPrivacyPolicy(new TreeSet<>(denyKeys));
        }
    }
}
