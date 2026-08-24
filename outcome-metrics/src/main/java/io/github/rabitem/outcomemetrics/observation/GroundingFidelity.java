package io.github.rabitem.outcomemetrics.observation;

/**
 * Closed grounding-fidelity verdict for RAG outcomes.
 *
 * <p>Retrieval quality and answer quality are usually evaluated separately, leaving fleet blind
 * spots: evidence retrieved but ignored, answers with hallucinated gaps, retrieval that was never
 * needed. The verdict rides the existing observation series as a {@code grounding} result tag
 * (failures keep {@code grounding=none}), never a parallel counter.
 *
 * <p>Deliberately distinct from {@link OutcomeIntegrity}: integrity grades whether the delivered
 * result is technically trustworthy; grounding grades whether a RAG answer is epistemically backed
 * by its evidence. {@code integrity=ok} with {@code grounding=hallucinated_gap} is exactly the
 * blind spot this vocabulary exists to expose.
 *
 * <p>The verdict is the caller's judge — a heuristic or an LLM-as-judge; the library cannot detect
 * a hallucination. Synchronous heuristics classify through
 * {@code OutcomeObservations.recordGrounded}; asynchronous judges record their own evaluation
 * observation carrying this tag. {@code retrieval_tier} and {@code citation_mode} stay caller-owned
 * bounded dimensions.
 *
 * @since 0.1.0
 */
public enum GroundingFidelity {

    /** The answer is backed by the retrieved evidence. */
    ALIGNED("aligned"),

    /** Evidence was retrieved but the answer did not use it. */
    IGNORED_EVIDENCE("ignored_evidence"),

    /** The answer asserts content the evidence does not support. */
    HALLUCINATED_GAP("hallucinated_gap"),

    /** The answer needed no corpus; retrieval was wasted cost, not a quality failure. */
    NO_CORPUS_NEEDED("no_corpus_needed");

    /** Tag name carrying the grounding verdict. */
    public static final String TAG_GROUNDING = "grounding";

    private final String tagValue;

    GroundingFidelity(final String tagValue) {
        this.tagValue = tagValue;
    }

    /**
     * Returns the stable {@code grounding} tag value.
     *
     * @return lower-case tag value, never {@code null} or blank
     */
    public String tagValue() {
        return tagValue;
    }
}
