package io.github.rabitem.outcomemetrics.observation;

import io.micrometer.common.KeyValue;

/**
 * Standardized {@code phase} dimension for multi-phase (intent–commit–reconcile) flows.
 *
 * <p>Offline and edge clients record a local <em>intent</em>, later <em>commit</em> it to the
 * server, and a server-side job <em>reconciles</em> what actually happened. Tagging each phase with
 * a shared vocabulary keeps the three streams joinable on dashboards without inventing per-team
 * phase names.
 *
 * <p>If one observation name spans phases, every recording of that name must carry a {@code phase}
 * tag (label sets must stay consistent per meter name).
 * {@link OutcomeObservations#recordReconciliation} adds {@link #RECONCILE} automatically; intent and
 * commit recordings add {@code OutcomePhase.INTENT.tag()} / {@code OutcomePhase.COMMIT.tag()} to
 * their dimensions.
 *
 * <p>Intent ids, device ids, and sync batch ids must never become tags.
 *
 * @since 0.1.0
 */
public enum OutcomePhase {

    /** Client-side local recording of the user's intent. */
    INTENT("intent"),

    /** Server-side application of the intent. */
    COMMIT("commit"),

    /** Server-side reconciliation of local claims against server state. */
    RECONCILE("reconcile");

    /** Phase tag name. */
    public static final String TAG_PHASE = "phase";

    private final String tagValue;

    OutcomePhase(final String tagValue) {
        this.tagValue = tagValue;
    }

    /**
     * Returns the stable {@code phase} tag value.
     *
     * @return lower-case tag value, never {@code null} or blank
     */
    public String tagValue() {
        return tagValue;
    }

    /**
     * Returns this phase as a dimension tag.
     *
     * @return {@code phase} key value, never {@code null}
     */
    public KeyValue tag() {
        return KeyValue.of(TAG_PHASE, tagValue);
    }
}
