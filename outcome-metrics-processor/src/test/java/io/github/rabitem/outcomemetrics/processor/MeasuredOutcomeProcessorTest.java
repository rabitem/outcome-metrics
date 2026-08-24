package io.github.rabitem.outcomemetrics.processor;

import io.github.rabitem.outcomemetrics.MeasuredOutcome;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("MeasuredOutcomeProcessor")
class MeasuredOutcomeProcessorTest {

    @Test
    @DisplayName("accepts canonical names and tags without diagnostics")
    void acceptsCanonical() {
        final Compilation result = compile("""
                import io.github.rabitem.outcomemetrics.MeasuredOutcome;
                @MeasuredOutcome(name = "order.flow", tags = {"step=reserve"})
                public class Sample {
                    @MeasuredOutcome(name = "order.place", tags = {"channel=web", "step=place"})
                    public void place() { }
                }
                """);

        assertThat(result.succeeded()).isTrue();
        assertThat(result.messages(Diagnostic.Kind.ERROR)).isEmpty();
        assertThat(result.messages(Diagnostic.Kind.WARNING)).isEmpty();
    }

    @Test
    @DisplayName("fails the build on malformed tag pairs and blank parts")
    void rejectsMalformedTags() {
        final Compilation result = compile("""
                import io.github.rabitem.outcomemetrics.MeasuredOutcome;
                public class Sample {
                    @MeasuredOutcome(name = "order.place", tags = {"channelweb", "=web", "step=", " "})
                    public void place() { }
                }
                """);

        assertThat(result.succeeded()).isFalse();
        assertThat(result.messages(Diagnostic.Kind.ERROR))
                .hasSize(4)
                .anySatisfy(message -> assertThat(message).contains("must use key=value format"))
                .anySatisfy(message -> assertThat(message).contains("has a blank value"))
                .anySatisfy(message -> assertThat(message).contains("must not be blank"));
    }

    @Test
    @DisplayName("fails the build when no observation name is resolvable")
    void rejectsMissingName() {
        final Compilation result = compile("""
                import io.github.rabitem.outcomemetrics.MeasuredOutcome;
                public class Sample {
                    @MeasuredOutcome(tags = {"step=reserve"})
                    public void place() { }
                }
                """);

        assertThat(result.succeeded()).isFalse();
        assertThat(result.messages(Diagnostic.Kind.ERROR))
                .singleElement().asString().contains("requires an explicit observation name");
    }

    @Test
    @DisplayName("resolves a method name from the type-level annotation")
    void acceptsTypeLevelName() {
        final Compilation result = compile("""
                import io.github.rabitem.outcomemetrics.MeasuredOutcome;
                @MeasuredOutcome(name = "order.flow")
                public class Sample {
                    @MeasuredOutcome(tags = {"step=reserve"})
                    public void place() { }
                }
                """);

        assertThat(result.succeeded()).isTrue();
        assertThat(result.messages(Diagnostic.Kind.ERROR)).isEmpty();
    }

    @Test
    @DisplayName("warns on non-canonical names, keys, and values instead of failing")
    void warnsOnNonCanonicalTokens() {
        final Compilation result = compile("""
                import io.github.rabitem.outcomemetrics.MeasuredOutcome;
                public class Sample {
                    @MeasuredOutcome(name = "Order.Place", tags = {"Channel=Web"})
                    public void place() { }
                }
                """);

        assertThat(result.succeeded()).isTrue();
        assertThat(result.messages(Diagnostic.Kind.ERROR)).isEmpty();
        assertThat(result.messages(Diagnostic.Kind.WARNING))
                .hasSize(3)
                .anySatisfy(message -> assertThat(message).contains("name \"Order.Place\""))
                .anySatisfy(message -> assertThat(message).contains("tag key \"Channel\""))
                .anySatisfy(message -> assertThat(message).contains("will be sanitized to \"web\""));
    }

    private static Compilation compile(final String source) {
        final JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        final DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try (StandardJavaFileManager fileManager =
                     compiler.getStandardFileManager(diagnostics, null, StandardCharsets.UTF_8)) {
            final String classpath = MeasuredOutcome.class
                    .getProtectionDomain().getCodeSource().getLocation().getPath();
            final JavaCompiler.CompilationTask task = compiler.getTask(
                    null,
                    fileManager,
                    diagnostics,
                    List.of("-proc:only", "-classpath", classpath),
                    null,
                    List.of(new StringSource(source)));
            task.setProcessors(List.of(new MeasuredOutcomeProcessor()));
            final boolean succeeded = Boolean.TRUE.equals(task.call());
            return new Compilation(succeeded, diagnostics.getDiagnostics());
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private record Compilation(boolean succeeded, List<Diagnostic<? extends JavaFileObject>> diagnostics) {

        List<String> messages(final Diagnostic.Kind kind) {
            return diagnostics.stream()
                    .filter(diagnostic -> diagnostic.getKind() == kind)
                    .map(diagnostic -> diagnostic.getMessage(null))
                    .toList();
        }
    }

    private static final class StringSource extends SimpleJavaFileObject {

        private final String source;

        private StringSource(final String source) {
            super(URI.create("string:///Sample.java"), Kind.SOURCE);
            this.source = source;
        }

        @Override
        public CharSequence getCharContent(final boolean ignoreEncodingErrors) {
            return source;
        }
    }
}
