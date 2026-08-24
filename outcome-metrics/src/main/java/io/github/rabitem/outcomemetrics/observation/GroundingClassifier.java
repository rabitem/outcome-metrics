package io.github.rabitem.outcomemetrics.observation;

/**
 * Classifies a successful RAG result into a closed {@link GroundingFidelity}.
 *
 * <p>Success-path classifier with the fail-loud contract of {@link IntegrityClassifier}: a
 * {@code null} return or a thrown exception fails the observation. Use it for verdicts available
 * at completion (citation heuristics, retrieval-usage checks); asynchronous LLM-as-judge verdicts
 * record their own evaluation observation instead.
 *
 * @param <T> result type
 * @since 0.1.0
 */
@FunctionalInterface
public interface GroundingClassifier<T> {

    /**
     * Classifies a successful result.
     *
     * @param result the successful result; may be {@code null} if the operation can return {@code null}
     * @return the grounding verdict, never {@code null}
     */
    GroundingFidelity classify(T result);
}
