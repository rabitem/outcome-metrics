package io.github.rabitem.outcomemetrics.test;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.lang.ArchRule;
import io.github.rabitem.outcomemetrics.observation.OutcomeReason;
import io.micrometer.observation.Observation;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Optional ArchUnit rules for instrumentation boundaries (requires {@code com.tngtech.archunit}
 * on the test classpath — the dependency is {@code optional} here).
 *
 * <p><b>Honesty note:</b> ArchUnit cannot see lambda implementations ({@code invokedynamic}
 * produces no class to inspect), and this library's own docs show lambda reasons.
 * {@link #outcomeReasonsAreEnums()} is therefore an opt-in strictness posture for teams that want
 * enum-only vocabularies (it pairs with {@code ReasonRegistry.vocabulary(...)}), not a general
 * guarantee.
 *
 * @since 0.1.0
 */
public final class OutcomeArchRules {

    private OutcomeArchRules() {
    }

    /**
     * Requires named {@link OutcomeReason} implementations to be enums.
     *
     * <p>Blind to lambdas by JVM construction; catches named and anonymous classes only.
     *
     * @return the rule
     */
    public static ArchRule outcomeReasonsAreEnums() {
        return classes()
                .that().implement(OutcomeReason.class)
                .should().beEnums()
                .as("named OutcomeReason implementations should be enums (lambdas are invisible"
                        + " to ArchUnit and remain allowed)")
                .allowEmptyShould(true);
    }

    /**
     * Forbids creating Micrometer observations outside the given packages, so all outcome
     * instrumentation flows through {@code OutcomeObservations} (or your own sanctioned wrappers).
     *
     * @param allowedPackageIdentifiers package identifiers allowed to create observations, e.g.
     *                                  {@code "io.github.rabitem.outcomemetrics..", "com.acme.metrics.."}
     * @return the rule
     */
    public static ArchRule observationsOnlyFrom(final String... allowedPackageIdentifiers) {
        return noClasses()
                .that().resideOutsideOfPackages(allowedPackageIdentifiers)
                .should().callMethodWhere(DescribedPredicate.describe(
                        "creates a Micrometer Observation",
                        (JavaMethodCall call) ->
                                call.getTarget().getOwner().isEquivalentTo(Observation.class)
                                        && ("createNotStarted".equals(call.getTarget().getName())
                                        || "start".equals(call.getTarget().getName()))))
                .as("Micrometer observations should only be created from sanctioned packages")
                .allowEmptyShould(true);
    }
}
