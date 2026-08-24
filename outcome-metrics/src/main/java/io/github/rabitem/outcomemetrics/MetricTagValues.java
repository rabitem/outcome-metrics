package io.github.rabitem.outcomemetrics;

import io.github.rabitem.outcomemetrics.observation.OutcomeReason;
import io.github.rabitem.outcomemetrics.observation.OutcomeReasonSource;

import java.util.Locale;
import java.util.Objects;

/**
 * Utilities for bounded, low-cardinality metric tag values.
 *
 * @since 0.1.0
 */
public final class MetricTagValues {

    /** Tag value used when a reason is absent. */
    public static final String NONE = "none";

    /** Tag value used when a value cannot be classified more precisely. */
    public static final String UNKNOWN = "unknown";

    /** Tag value used when a cardinality limit remaps an overflowing value. */
    public static final String OTHER = "other";

    private MetricTagValues() {
    }

    /**
     * Returns a stable reason code for a throwable.
     *
     * <p>Walks the cause chain for the first {@link OutcomeReasonSource}. Only those implementations
     * contribute open reason codes. All other throwables map to {@link #UNKNOWN} so exception class
     * names cannot explode cardinality. Callers that intentionally want an exception-derived code may
     * use {@link #exceptionCode}.
     *
     * @param error throwable to classify; may be {@code null}
     * @return a stable reason code, never {@code null} or blank
     */
    public static String reasonCode(final Throwable error) {
        if (error == null) {
            return NONE;
        }
        final OutcomeReason reason = outcomeReason(error);
        return reason == null ? UNKNOWN : sanitizeTagValue(reason.code());
    }

    /**
     * Resolves the {@link OutcomeReason} explaining a throwable.
     *
     * <p>Walks the cause chain for the first {@link OutcomeReasonSource} whose reason carries a
     * usable code; sources with a {@code null} reason or a blank code are skipped, matching
     * {@link #reasonCode}.
     *
     * @param error throwable to classify; may be {@code null}
     * @return the first usable reason, or {@code null} when the chain has none
     */
    public static OutcomeReason outcomeReason(final Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof OutcomeReasonSource source) {
                final OutcomeReason reason = source.outcomeReason();
                if (reason != null && reason.code() != null && !reason.code().isBlank()) {
                    return reason;
                }
            }
            current = current.getCause();
        }
        return null;
    }

    /**
     * Derives a snake-case code from an exception's simple name.
     *
     * <p>Opt-in helper for closed exception vocabularies. Prefer {@link OutcomeReasonSource} for
     * observation reason tags.
     *
     * @param error throwable to classify; must not be {@code null}
     * @return a stable exception code, never {@code null} or blank
     */
    public static String exceptionCode(final Throwable error) {
        Objects.requireNonNull(error, "error must not be null");
        return toSnakeCase(error.getClass().getSimpleName());
    }

    /**
     * Converts an enum constant to a lower snake-case tag value.
     *
     * @param value enum value; may be {@code null}
     * @return a stable tag value, never {@code null} or blank
     */
    public static String enumValue(final Enum<?> value) {
        return value == null ? UNKNOWN : toSnakeCase(value.name());
    }

    /**
     * Normalizes an arbitrary value to a safe low-cardinality tag token.
     *
     * <p>This method is intended for static vocabularies such as enum names or configuration values,
     * not for user input, UUIDs, names, emails, or other high-cardinality data.
     *
     * @param value value to normalize; may be {@code null}
     * @return a lower snake-case token, never {@code null} or blank
     */
    public static String sanitizeTagValue(final Object value) {
        if (value == null) {
            return UNKNOWN;
        }
        return toSnakeCase(String.valueOf(value));
    }

    /**
     * Converts a value to lower snake case.
     *
     * @param value value to convert; may be {@code null}
     * @return converted value, or {@link #UNKNOWN} when the input is blank
     */
    public static String toSnakeCase(final String value) {
        if (value == null || value.isBlank()) {
            return UNKNOWN;
        }
        final String stripped = value.strip();
        final StringBuilder code = new StringBuilder(stripped.length() + 8);
        char previous = 0;
        for (int i = 0; i < stripped.length(); i++) {
            final char current = stripped.charAt(i);
            if (Character.isLetterOrDigit(current)) {
                final char next = i + 1 < stripped.length() ? stripped.charAt(i + 1) : 0;
                appendWordCharacter(code, previous, current, next);
                previous = current;
            } else {
                appendSeparator(code);
                previous = '_';
            }
        }
        final String normalized = trimSeparators(code.toString()).toLowerCase(Locale.ROOT);
        return normalized.isBlank() ? UNKNOWN : normalized;
    }

    private static void appendWordCharacter(
            final StringBuilder code,
            final char previous,
            final char current,
            final char next) {
        if (Character.isUpperCase(current)
                && code.length() > 0
                && previous != '_'
                && (Character.isLowerCase(previous)
                || Character.isDigit(previous)
                || (Character.isUpperCase(previous) && Character.isLowerCase(next)))) {
            appendSeparator(code);
        }
        code.append(current);
    }

    private static void appendSeparator(final StringBuilder code) {
        if (!code.isEmpty() && code.charAt(code.length() - 1) != '_') {
            code.append('_');
        }
    }

    private static String trimSeparators(final String value) {
        int start = 0;
        int end = value.length();
        while (start < end && value.charAt(start) == '_') {
            start++;
        }
        while (end > start && value.charAt(end - 1) == '_') {
            end--;
        }
        return value.substring(start, end);
    }
}
