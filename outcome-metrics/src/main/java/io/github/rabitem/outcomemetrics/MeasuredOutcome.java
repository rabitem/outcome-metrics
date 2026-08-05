package io.github.rabitem.outcomemetrics;

import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a method or type for outcome-based Micrometer observation.
 *
 * <p>Framework adapters intercept annotated call sites (Spring AOP, Quarkus/CDI).
 * Use explicit, low-cardinality names and tags. Do not put entity ids, user input, or personally
 * identifiable data in {@link #tags()}.
 *
 * @since 0.1.0
 */
@Inherited
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface MeasuredOutcome {

    /**
     * Observation name to record.
     *
     * @return the observation name; may be blank on a type when annotated methods provide a name
     */
    String name() default "";

    /**
     * Static low-cardinality tags in {@code key=value} form.
     *
     * @return static tag pairs, never {@code null}
     */
    String[] tags() default {};
}
