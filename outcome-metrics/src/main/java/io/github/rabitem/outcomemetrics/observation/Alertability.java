package io.github.rabitem.outcomemetrics.observation;

/**
 * Closed routing classification for failure reasons.
 *
 * <p>Not every failure should page: client aborts and expected business declines mix with actionable
 * outages and cause alert fatigue. Each level maps one-to-one onto alert routing behavior, so
 * alerting rules can select on the {@code alertability} tag instead of maintaining fragile regexes
 * over reason codes.
 *
 * <p>The fail-loud rule: reasons that do not declare an alertability, unclassified failures
 * ({@code reason=unknown}), and broken {@link OutcomeReason#alertability()} implementations all
 * resolve to {@link #PAGE}. Authors downgrade expected failures explicitly; nothing is silenced by
 * omission.
 *
 * @since 0.1.0
 */
public enum Alertability {

    /** Wake a human now: actionable, urgent. The default for every failure. */
    PAGE("page"),

    /** Needs action, but not at 3 a.m.: route to a ticket queue. */
    TICKET("ticket"),

    /** Expected outcome of normal operation (business decline, client abort): dashboards only. */
    NONE("none");

    private final String tagValue;

    Alertability(final String tagValue) {
        this.tagValue = tagValue;
    }

    /**
     * Returns the stable {@code alertability} tag value.
     *
     * @return lower-case tag value, never {@code null} or blank
     */
    public String tagValue() {
        return tagValue;
    }
}
