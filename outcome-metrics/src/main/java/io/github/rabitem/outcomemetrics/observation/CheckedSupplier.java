package io.github.rabitem.outcomemetrics.observation;

/**
 * Supplier operation that may throw a checked throwable.
 *
 * @param <T> supplied value type
 * @since 0.1.0
 */
@FunctionalInterface
public interface CheckedSupplier<T> {

    /**
     * Supplies a value.
     *
     * @return supplied value
     * @throws Throwable if the operation fails
     */
    T get() throws Throwable;
}
