package io.github.rabitem.outcomemetrics.observation;

/**
 * Runnable operation that may throw a checked throwable.
 *
 * @since 0.1.0
 */
@FunctionalInterface
public interface CheckedRunnable {

    /**
     * Runs the operation.
     *
     * @throws Throwable if the operation fails
     */
    void run() throws Throwable;
}
