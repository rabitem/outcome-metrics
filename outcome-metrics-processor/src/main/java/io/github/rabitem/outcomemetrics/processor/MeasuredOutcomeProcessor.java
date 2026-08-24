package io.github.rabitem.outcomemetrics.processor;

import io.github.rabitem.outcomemetrics.MeasuredOutcome;
import io.github.rabitem.outcomemetrics.MetricTagValues;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.TypeElement;
import javax.tools.Diagnostic;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Compile-time validation of {@link MeasuredOutcome} constants.
 *
 * <p>Annotation attributes are compile-time constants, so they cannot explode cardinality at
 * runtime — but malformed constants (a tag without {@code =}, a blank key or value, a missing
 * observation name) throw from the interceptor on the first production invocation. This processor
 * moves those failures to the build:
 *
 * <ul>
 * <li><b>Errors</b> (fail the build): tag pairs without {@code =} or with a blank key, blank tag
 * values (they silently become {@code unknown}), and an annotated method with no resolvable
 * observation name on method or type.</li>
 * <li><b>Warnings</b>: names, keys, or values that are legal but not in canonical lower-case token
 * form — tag values are sanitized at runtime ({@code Web} → {@code web}); names and keys are used
 * as written.</li>
 * </ul>
 *
 * <p>Opt-in: add this artifact to {@code annotationProcessorPaths}. Dynamic dimensions belong in
 * {@code OutcomeObservations.record(...)}, where sanitization and bounded-tag meter filters apply.
 *
 * @since 0.1.0
 */
public final class MeasuredOutcomeProcessor extends AbstractProcessor {

    private static final Pattern CANONICAL_TOKEN = Pattern.compile("[a-z0-9]+(?:[._-][a-z0-9]+)*");

    @Override
    public Set<String> getSupportedAnnotationTypes() {
        return Set.of(MeasuredOutcome.class.getCanonicalName());
    }

    @Override
    public SourceVersion getSupportedSourceVersion() {
        return SourceVersion.latestSupported();
    }

    @Override
    public boolean process(final Set<? extends TypeElement> annotations, final RoundEnvironment roundEnv) {
        for (final TypeElement annotation : annotations) {
            for (final Element element : roundEnv.getElementsAnnotatedWith(annotation)) {
                validate(element);
            }
        }
        return false;
    }

    private void validate(final Element element) {
        final MeasuredOutcome annotation = element.getAnnotation(MeasuredOutcome.class);
        if (annotation == null) {
            return;
        }
        validateName(element, annotation);
        for (final String pair : annotation.tags()) {
            validateTagPair(element, pair);
        }
    }

    private void validateName(final Element element, final MeasuredOutcome annotation) {
        final String name = annotation.name();
        if (name != null && !name.isBlank()) {
            warnIfNotCanonical(element, "@MeasuredOutcome name \"" + name + "\"", name.strip());
            return;
        }
        if (element.getKind() != ElementKind.METHOD) {
            // A blank type-level name is legal: annotated methods may provide their own names.
            return;
        }
        final Element enclosing = element.getEnclosingElement();
        final MeasuredOutcome typeAnnotation =
                enclosing == null ? null : enclosing.getAnnotation(MeasuredOutcome.class);
        if (typeAnnotation == null || typeAnnotation.name() == null || typeAnnotation.name().isBlank()) {
            error(element, "@MeasuredOutcome requires an explicit observation name on the method or its type");
        }
    }

    private void validateTagPair(final Element element, final String pair) {
        if (pair == null || pair.isBlank()) {
            error(element, "@MeasuredOutcome tag pair must not be blank");
            return;
        }
        final int separator = pair.indexOf('=');
        if (separator <= 0) {
            error(element, "@MeasuredOutcome tag pair \"" + pair + "\" must use key=value format");
            return;
        }
        final String key = pair.substring(0, separator).strip();
        final String value = pair.substring(separator + 1).strip();
        if (key.isBlank()) {
            error(element, "@MeasuredOutcome tag pair \"" + pair + "\" has a blank key");
            return;
        }
        if (value.isBlank()) {
            error(element, "@MeasuredOutcome tag pair \"" + pair
                    + "\" has a blank value; it would silently become \"unknown\"");
            return;
        }
        warnIfNotCanonical(element, "@MeasuredOutcome tag key \"" + key + "\"", key);
        if (!CANONICAL_TOKEN.matcher(value).matches()) {
            warn(element, "@MeasuredOutcome tag value \"" + value + "\" will be sanitized to \""
                    + MetricTagValues.sanitizeTagValue(value) + "\" at runtime");
        }
    }

    private void warnIfNotCanonical(final Element element, final String what, final String token) {
        if (!CANONICAL_TOKEN.matcher(token).matches()) {
            warn(element, what + " is not a canonical lower-case token and is used as written");
        }
    }

    private void error(final Element element, final String message) {
        processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, message, element);
    }

    private void warn(final Element element, final String message) {
        processingEnv.getMessager().printMessage(Diagnostic.Kind.WARNING, message, element);
    }
}
