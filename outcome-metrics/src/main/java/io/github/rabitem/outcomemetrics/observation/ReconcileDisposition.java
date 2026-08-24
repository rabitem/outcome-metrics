package io.github.rabitem.outcomemetrics.observation;

/**
 * Closed finding of a reconciliation pass over a locally claimed outcome.
 *
 * <p>An abandoned intent (app closed mid-flow) emits no completion event of its own — abandonment is
 * discovered by the reconciliation job when the commit window expires. It is therefore a
 * <em>finding</em> of a successful reconciliation, carried as a {@code disposition} result tag, not
 * a new {@code outcome} value: {@code outcome} stays binary.
 *
 * <p>Alert on {@code disposition=diverged} and {@code disposition=abandoned} rates. A shop that
 * wants divergence to page can instead throw from its reconcile step with an
 * {@link OutcomeReasonSource} reason and let {@code alertability} route it.
 *
 * @since 0.1.0
 */
public enum ReconcileDisposition {

    /** The local claim matches server state. */
    CONFIRMED("confirmed"),

    /** Local claim and server state disagree (local-only, server-only, or conflicting result). */
    DIVERGED("diverged"),

    /** The intent's commit window expired with no commit: the flow was abandoned mid-way. */
    ABANDONED("abandoned"),

    /** Not yet reconcilable: the intent is still within its commit window. */
    DEFERRED("deferred");

    /** Tag name carrying the reconciliation finding. */
    public static final String TAG_DISPOSITION = "disposition";

    private final String tagValue;

    ReconcileDisposition(final String tagValue) {
        this.tagValue = tagValue;
    }

    /**
     * Returns the stable {@code disposition} tag value.
     *
     * @return lower-case tag value, never {@code null} or blank
     */
    public String tagValue() {
        return tagValue;
    }
}
