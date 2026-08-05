package io.github.rabitem.outcomemetrics;

import io.micrometer.common.KeyValues;

import java.lang.reflect.Method;

/**
 * Shared helpers for resolving {@link MeasuredOutcome} metadata across frameworks.
 *
 * @since 0.1.0
 */
public final class MeasuredOutcomeSupport {

    private MeasuredOutcomeSupport() {
    }

    /**
     * Resolves the observation name, preferring the method annotation over the type annotation.
     *
     * @param typeAnnotation   type-level annotation; may be {@code null}
     * @param methodAnnotation method-level annotation; may be {@code null}
     * @return non-blank observation name
     * @throws IllegalStateException if neither annotation provides a name
     */
    public static String resolveName(
            final MeasuredOutcome typeAnnotation,
            final MeasuredOutcome methodAnnotation) {
        final String methodName = name(methodAnnotation);
        if (methodName != null) {
            return methodName;
        }
        final String typeName = name(typeAnnotation);
        if (typeName != null) {
            return typeName;
        }
        throw new IllegalStateException("@MeasuredOutcome requires an explicit observation name");
    }

    /**
     * Merges type-level then method-level static tags.
     *
     * @param typeAnnotation   type-level annotation; may be {@code null}
     * @param methodAnnotation method-level annotation; may be {@code null}
     * @return merged tags, never {@code null}
     */
    public static KeyValues resolveTags(
            final MeasuredOutcome typeAnnotation,
            final MeasuredOutcome methodAnnotation) {
        return annotationTags(typeAnnotation).and(annotationTags(methodAnnotation));
    }

    /**
     * Finds a type-level {@link MeasuredOutcome} on {@code type}, its superclasses, or interfaces.
     *
     * @param type bean or declaring type; may be {@code null}
     * @return annotation if present, otherwise {@code null}
     */
    public static MeasuredOutcome findTypeAnnotation(final Class<?> type) {
        Class<?> current = type;
        while (current != null && current != Object.class) {
            final MeasuredOutcome annotation = current.getAnnotation(MeasuredOutcome.class);
            if (annotation != null) {
                return annotation;
            }
            final MeasuredOutcome fromInterfaces = findOnInterfaces(current.getInterfaces());
            if (fromInterfaces != null) {
                return fromInterfaces;
            }
            current = current.getSuperclass();
        }
        return null;
    }

    /**
     * Finds a method-level {@link MeasuredOutcome} on {@code method} or an interface override.
     *
     * @param method intercepted method; may be {@code null}
     * @return annotation if present, otherwise {@code null}
     */
    public static MeasuredOutcome findMethodAnnotation(final Method method) {
        if (method == null) {
            return null;
        }
        final MeasuredOutcome direct = method.getAnnotation(MeasuredOutcome.class);
        if (direct != null) {
            return direct;
        }
        Class<?> current = method.getDeclaringClass();
        while (current != null && current != Object.class) {
            final MeasuredOutcome onInterfaces = findMethodOnInterfaces(current.getInterfaces(), method);
            if (onInterfaces != null) {
                return onInterfaces;
            }
            current = current.getSuperclass();
        }
        return null;
    }

    private static MeasuredOutcome findOnInterfaces(final Class<?>[] interfaces) {
        for (final Class<?> iface : interfaces) {
            final MeasuredOutcome annotation = iface.getAnnotation(MeasuredOutcome.class);
            if (annotation != null) {
                return annotation;
            }
            final MeasuredOutcome nested = findOnInterfaces(iface.getInterfaces());
            if (nested != null) {
                return nested;
            }
        }
        return null;
    }

    private static MeasuredOutcome findMethodOnInterfaces(final Class<?>[] interfaces, final Method method) {
        for (final Class<?> iface : interfaces) {
            final MeasuredOutcome annotation = lookupMethodAnnotation(iface, method);
            if (annotation != null) {
                return annotation;
            }
            final MeasuredOutcome nested = findMethodOnInterfaces(iface.getInterfaces(), method);
            if (nested != null) {
                return nested;
            }
        }
        return null;
    }

    private static MeasuredOutcome lookupMethodAnnotation(final Class<?> type, final Method method) {
        try {
            return type.getMethod(method.getName(), method.getParameterTypes())
                    .getAnnotation(MeasuredOutcome.class);
        } catch (final NoSuchMethodException ignored) {
            return null;
        }
    }

    private static String name(final MeasuredOutcome annotation) {
        if (annotation == null || annotation.name().isBlank()) {
            return null;
        }
        return annotation.name().strip();
    }

    private static KeyValues annotationTags(final MeasuredOutcome annotation) {
        return annotation == null ? KeyValues.empty() : MetricsTags.pairs(annotation.tags());
    }
}
