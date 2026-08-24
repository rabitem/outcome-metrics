package io.github.rabitem.outcomemetrics.test;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import io.github.rabitem.outcomemetrics.observation.OutcomeReason;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("OutcomeArchRules")
class OutcomeArchRulesTest {

    @Test
    @DisplayName("flags named non-enum reason implementations and passes enums")
    void reasonsAreEnums() {
        final JavaClasses good = new ClassFileImporter().importClasses(EnumReason.class);
        final JavaClasses bad = new ClassFileImporter().importClasses(ClassReason.class);

        assertThatCode(() -> OutcomeArchRules.outcomeReasonsAreEnums().check(good))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> OutcomeArchRules.outcomeReasonsAreEnums().check(bad))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("ClassReason");
    }

    @Test
    @DisplayName("flags raw observation creation outside sanctioned packages")
    void observationBoundary() {
        final JavaClasses offender = new ClassFileImporter().importClasses(RawObservationUser.class);

        assertThatThrownBy(() -> OutcomeArchRules
                .observationsOnlyFrom("com.acme.sanctioned..")
                .check(offender))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("RawObservationUser");
        assertThatCode(() -> OutcomeArchRules
                .observationsOnlyFrom("io.github.rabitem.outcomemetrics.test..")
                .check(offender))
                .doesNotThrowAnyException();
    }

    private enum EnumReason implements OutcomeReason {
        FINE;

        @Override
        public String code() {
            return "fine";
        }
    }

    private static final class ClassReason implements OutcomeReason {

        @Override
        public String code() {
            return "not_an_enum";
        }
    }

    private static final class RawObservationUser {

        void observeDirectly(final ObservationRegistry registry) {
            Observation.createNotStarted("rogue", registry).observe(() -> {
            });
        }
    }
}
