package io.github.rabitem.outcomemetrics.test;

import io.github.rabitem.outcomemetrics.MetricTagValues;
import io.github.rabitem.outcomemetrics.observation.OutcomeReason;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/**
 * Contract checks for reason vocabularies.
 *
 * <p>Run in tests so vocabulary drift fails builds: codes must be non-blank, stable under
 * sanitization (registered form equals emitted form), unique within the vocabulary, and declare a
 * non-null alertability. Pairs with {@code ReasonRegistry} (#24) and standard PIT mutators: an
 * empty-returns mutation on {@code code()} or {@code alertability()} dies against these checks.
 *
 * @since 0.1.0
 */
public final class ReasonVocabularyContracts {

    private ReasonVocabularyContracts() {
    }

    /**
     * Asserts a well-formed enum reason vocabulary.
     *
     * @param <E>        enum type implementing {@link OutcomeReason}
     * @param vocabulary vocabulary class; must not be {@code null}
     * @throws AssertionError if any constant violates the contract
     */
    public static <E extends Enum<E> & OutcomeReason> void assertWellFormed(final Class<E> vocabulary) {
        Objects.requireNonNull(vocabulary, "vocabulary must not be null");
        final Set<String> seen = new HashSet<>();
        for (final E constant : vocabulary.getEnumConstants()) {
            final String origin = vocabulary.getSimpleName() + "." + constant.name();
            final String code = constant.code();
            if (code == null || code.isBlank()) {
                throw new AssertionError(origin + " has a blank reason code");
            }
            if (!code.equals(MetricTagValues.sanitizeTagValue(code))) {
                throw new AssertionError(origin + " code \"" + code + "\" is not sanitization-stable;"
                        + " it would emit as \"" + MetricTagValues.sanitizeTagValue(code) + "\"");
            }
            if (!seen.add(code)) {
                throw new AssertionError(origin + " duplicates reason code \"" + code + "\"");
            }
            if (constant.alertability() == null) {
                throw new AssertionError(origin + " declares a null alertability; broken"
                        + " implementations page at runtime, but tests should fail instead");
            }
        }
    }
}
