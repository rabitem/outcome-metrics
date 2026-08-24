package io.github.rabitem.outcomemetrics.test;

import io.github.rabitem.outcomemetrics.observation.ExperimentRegistry;
import io.github.rabitem.outcomemetrics.observation.ReasonRegistry;
import io.github.rabitem.outcomemetrics.observation.SloCatalog;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("VocabularyAttestation")
class VocabularyAttestationTest {

    @TempDir
    Path tempDir;

    @AfterEach
    void clearUpdateFlag() {
        System.clearProperty(VocabularyAttestation.UPDATE_PROPERTY);
    }

    @Test
    @DisplayName("renders canonical sorted JSON across all vocabularies")
    void canonicalJson() {
        final String json = attestation().json();

        assertThat(json).isEqualTo("""
                {
                  "reasons": ["cancelled", "db_down", "none", "other", "payment_declined", "unknown"],
                  "slos": ["checkout_success"],
                  "experiments": ["checkout_v2"]
                }
                """);
    }

    @Test
    @DisplayName("passes when committed, fails with guidance on drift or missing file")
    void diffing() {
        final Path committed = tempDir.resolve("outcome-vocabulary.json");

        assertThatThrownBy(() -> attestation().assertMatches(committed))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("No committed vocabulary attestation");

        attestation().writeTo(committed);
        assertThatCode(() -> attestation().assertMatches(committed)).doesNotThrowAnyException();

        final VocabularyAttestation drifted = VocabularyAttestation.builder()
                .reasons(ReasonRegistry.builder().codes("db_down", "new_code").build())
                .build();
        assertThatThrownBy(() -> drifted.assertMatches(committed))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("drifted")
                .hasMessageContaining(VocabularyAttestation.UPDATE_PROPERTY);
    }

    @Test
    @DisplayName("regenerates instead of failing in update mode")
    void updateMode() throws Exception {
        final Path committed = tempDir.resolve("outcome-vocabulary.json");
        System.setProperty(VocabularyAttestation.UPDATE_PROPERTY, "true");

        attestation().assertMatches(committed);

        assertThat(Files.readString(committed)).contains("\"payment_declined\"");
    }

    @Test
    @DisplayName("requires at least one vocabulary")
    void validation() {
        assertThatThrownBy(() -> VocabularyAttestation.builder().build())
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("attest at least one vocabulary");
    }

    private static VocabularyAttestation attestation() {
        return VocabularyAttestation.builder()
                .reasons(ReasonRegistry.builder().codes("db_down", "payment_declined").build())
                .slos(SloCatalog.of("checkout-success"))
                .experiments(ExperimentRegistry.builder().experiment("checkout-v2").build())
                .build();
    }
}
