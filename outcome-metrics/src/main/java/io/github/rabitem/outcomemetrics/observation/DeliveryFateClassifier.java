package io.github.rabitem.outcomemetrics.observation;

/**
 * Classifies a failed delivery's fate from its error.
 *
 * <p>Contract deliberately diverges from {@link IntegrityClassifier} and friends: those run on the
 * success path, where failing loud is safe. This classifier runs on a path that has already failed
 * — a {@code null} return or a thrown exception yields {@code fate=unknown} and the original
 * business exception propagates untouched. Telemetry must not convert one incident into two; a
 * rising {@code fate=unknown} rate is itself the alert for classifier gaps.
 *
 * @since 0.1.0
 */
@FunctionalInterface
public interface DeliveryFateClassifier {

    /**
     * Classifies a failed delivery.
     *
     * @param error the failure; never {@code null}
     * @return the fate ({@link DeliveryFate#RETRY}, {@link DeliveryFate#DEAD_LETTER} or
     * {@link DeliveryFate#DROP}); {@code null} is recorded as {@code unknown}
     */
    DeliveryFate classify(Throwable error);
}
