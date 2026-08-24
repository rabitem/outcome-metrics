package io.github.rabitem.outcomemetrics.observation;

import io.micrometer.common.KeyValues;
import io.micrometer.observation.Observation;

import java.util.Objects;

/**
 * Observation context for an outcome-instrumented operation.
 *
 * <p>Carries the caller-supplied low-cardinality dimensions, such as {@code frame=subscribe} or
 * {@code target=admin}. The {@code outcome} and {@code reason} tags are derived by
 * {@link OutcomeObservationConvention} from the recorded error when the observation stops.
 *
 * <p>For work classified by its return value rather than by a thrown exception, the context is marked
 * {@link #isClassified() classified}; the caller's {@link #resultTags()} are then emitted on success.
 *
 * @since 0.1.0
 */
public class OutcomeObservationContext extends Observation.Context {

    private final KeyValues dimensions;
    private KeyValues resultTags = KeyValues.empty();
    private OutcomeIntegrity integrity = OutcomeIntegrity.OK;
    private boolean classified;
    private String occurrence;
    private String reason;
    private String alertability;
    private KeyValues guardedTags;
    private boolean settled;

    /**
     * Creates a context.
     *
     * @param dimensions low-cardinality dimension tags; must not be {@code null}
     */
    public OutcomeObservationContext(final KeyValues dimensions) {
        this.dimensions = Objects.requireNonNull(dimensions, "dimensions must not be null");
    }

    /**
     * Returns the caller-supplied dimensions.
     *
     * @return dimensions, never {@code null}
     */
    public KeyValues dimensions() {
        return dimensions;
    }

    /**
     * Marks the observation as classified by its return value.
     */
    public void markClassified() {
        this.classified = true;
    }

    /**
     * Returns whether this context is classified by result tags.
     *
     * @return {@code true} when success is classified by {@link #resultTags()}
     */
    public boolean isClassified() {
        return classified;
    }

    /**
     * Sets the successful-result tags.
     *
     * @param resultTags result tags; {@code null} is treated as {@link KeyValues#empty()}
     */
    public void setResultTags(final KeyValues resultTags) {
        this.resultTags = resultTags == null ? KeyValues.empty() : resultTags;
    }

    /**
     * Returns the successful-result tags.
     *
     * @return result tags, never {@code null}
     */
    public KeyValues resultTags() {
        return resultTags;
    }

    /**
     * Sets the integrity classification of the successful result.
     *
     * @param integrity integrity classification; must not be {@code null}
     */
    public void setIntegrity(final OutcomeIntegrity integrity) {
        this.integrity = Objects.requireNonNull(integrity, "integrity must not be null");
    }

    /**
     * Returns the integrity classification of the successful result.
     *
     * <p>Defaults to {@link OutcomeIntegrity#OK}. Only emitted for successful observations; failures
     * carry {@code integrity=none}.
     *
     * @return integrity classification, never {@code null}
     */
    public OutcomeIntegrity integrity() {
        return integrity;
    }

    /**
     * Caches the evaluated {@code occurrence} tag value so repeated convention lookups cannot
     * re-consult the scope and flip a first occurrence into a repeat.
     */
    void cacheOccurrence(final String occurrence) {
        this.occurrence = occurrence;
    }

    /**
     * Returns the cached {@code occurrence} tag value.
     *
     * @return cached value, or {@code null} before the convention evaluated it
     */
    String cachedOccurrence() {
        return occurrence;
    }

    /**
     * Caches the emitted {@code reason} tag value so repeated convention lookups and concurrent
     * budget changes cannot change it mid-observation.
     */
    void cacheReason(final String reason) {
        this.reason = reason;
    }

    /**
     * Returns the cached {@code reason} tag value.
     *
     * @return cached value, or {@code null} before the convention evaluated it
     */
    String cachedReason() {
        return reason;
    }

    /**
     * Caches the emitted {@code alertability} tag value, resolved together with the reason.
     */
    void cacheAlertability(final String alertability) {
        this.alertability = alertability;
    }

    /**
     * Returns the cached {@code alertability} tag value.
     *
     * @return cached value, or {@code null} before the convention evaluated it
     */
    String cachedAlertability() {
        return alertability;
    }

    /**
     * Caches the combination-guarded tags so repeated convention lookups cannot double-count
     * support.
     */
    void cacheGuardedTags(final KeyValues guardedTags) {
        this.guardedTags = guardedTags;
    }

    /**
     * Returns the cached combination-guarded tags.
     *
     * @return cached tags, or {@code null} before the convention evaluated them
     */
    KeyValues cachedGuardedTags() {
        return guardedTags;
    }

    /**
     * Marks the observation state final (work finished, error recorded if any).
     *
     * <p>Micrometer consults the convention at start as well as stop; side-effectful tagging must
     * only act once the state is settled.
     */
    void markSettled() {
        this.settled = true;
    }

    /**
     * Returns whether the observation state is final.
     *
     * @return {@code true} once work has finished and any error is recorded
     */
    boolean isSettled() {
        return settled;
    }
}
