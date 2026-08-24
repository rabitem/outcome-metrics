package io.github.rabitem.outcomemetrics.test;

import io.github.rabitem.outcomemetrics.observation.ExperimentRegistry;
import io.github.rabitem.outcomemetrics.observation.ReasonRegistry;
import io.github.rabitem.outcomemetrics.observation.SloCatalog;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;

/**
 * CI attestation for the closed vocabularies a binary declares (issue #64).
 *
 * <p>Renders reason codes, SLO ids, and experiment ids as canonical JSON and diffs it against a
 * committed file, so vocabulary changes show up in review instead of drifting silently:
 *
 * <pre>{@code
 * @Test
 * void vocabularyIsAttested() {
 *     VocabularyAttestation.builder()
 *         .reasons(reasonRegistry)
 *         .slos(sloCatalog)
 *         .build()
 *         .assertMatches(Path.of("src/test/resources/outcome-vocabulary.json"));
 * }
 * }</pre>
 *
 * <p>Regenerate deliberately with {@code -D}{@value #UPDATE_PROPERTY}{@code =true}: the assertion
 * then rewrites the committed file instead of failing, and the diff lands in the pull request.
 *
 * @since 0.1.0
 */
public final class VocabularyAttestation {

    /** System property that switches {@link #assertMatches} into regeneration mode. */
    public static final String UPDATE_PROPERTY = "outcome.metrics.attestation.update";

    private final Map<String, Set<String>> sections;

    private VocabularyAttestation(final Map<String, Set<String>> sections) {
        this.sections = sections;
    }

    /**
     * Creates an attestation builder.
     *
     * @return a new builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Renders the canonical JSON document (sorted sections, sorted values, stable across runs).
     *
     * @return canonical JSON, never {@code null}
     */
    public String json() {
        final StringJoiner document = new StringJoiner(",\n", "{\n", "\n}\n");
        for (final Map.Entry<String, Set<String>> section : sections.entrySet()) {
            final StringJoiner values = new StringJoiner(", ", "[", "]");
            for (final String value : section.getValue()) {
                values.add("\"" + value + "\"");
            }
            document.add("  \"" + section.getKey() + "\": " + values);
        }
        return document.toString();
    }

    /**
     * Writes the canonical JSON to a file, creating parent directories.
     *
     * @param file target file; must not be {@code null}
     */
    public void writeTo(final Path file) {
        try {
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
            Files.writeString(file, json());
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Asserts the committed attestation matches the declared vocabularies.
     *
     * <p>With {@code -D}{@value #UPDATE_PROPERTY}{@code =true}, regenerates the file instead of
     * failing so the change lands as a reviewable diff.
     *
     * @param committed committed attestation file
     * @throws AssertionError on mismatch or missing file (unless in update mode)
     */
    public void assertMatches(final Path committed) {
        final String rendered = json();
        if (Boolean.getBoolean(UPDATE_PROPERTY)) {
            writeTo(committed);
            return;
        }
        if (!Files.exists(committed)) {
            throw new AssertionError("No committed vocabulary attestation at " + committed
                    + "; generate it with -D" + UPDATE_PROPERTY + "=true");
        }
        final String onDisk;
        try {
            onDisk = Files.readString(committed);
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
        if (!onDisk.equals(rendered)) {
            throw new AssertionError("Declared vocabularies drifted from " + committed
                    + " — review the change, then regenerate with -D" + UPDATE_PROPERTY + "=true."
                    + System.lineSeparator() + "Declared now:" + System.lineSeparator() + rendered);
        }
    }

    /**
     * Builder collecting vocabularies to attest.
     *
     * @since 0.1.0
     */
    public static final class Builder {

        private final Map<String, Set<String>> sections = new LinkedHashMap<>();

        private Builder() {
        }

        /**
         * Attests the reason vocabulary (sanitized codes including the schema floor).
         *
         * @param registry reason registry; must not be {@code null}
         * @return this builder
         */
        public Builder reasons(final ReasonRegistry registry) {
            sections.put("reasons", registry.codes());
            return this;
        }

        /**
         * Attests the declared SLO ids.
         *
         * @param catalog SLO catalog; must not be {@code null}
         * @return this builder
         */
        public Builder slos(final SloCatalog catalog) {
            sections.put("slos", catalog.ids());
            return this;
        }

        /**
         * Attests the registered experiment ids.
         *
         * @param registry experiment registry; must not be {@code null}
         * @return this builder
         */
        public Builder experiments(final ExperimentRegistry registry) {
            sections.put("experiments", registry.ids());
            return this;
        }

        /**
         * Builds the attestation.
         *
         * @return an immutable attestation
         * @throws IllegalStateException if no vocabulary was added
         */
        public VocabularyAttestation build() {
            if (sections.isEmpty()) {
                throw new IllegalStateException("attest at least one vocabulary");
            }
            return new VocabularyAttestation(new LinkedHashMap<>(sections));
        }
    }
}
