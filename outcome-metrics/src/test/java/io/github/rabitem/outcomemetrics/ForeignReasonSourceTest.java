package io.github.rabitem.outcomemetrics;

import io.github.rabitem.outcomemetrics.observation.OutcomeReason;
import io.github.rabitem.outcomemetrics.observation.OutcomeReasonSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proof for #41: a plugin shipping its own copy of the library implements a <em>different</em>
 * {@code OutcomeReasonSource} class of the same name — {@code instanceof} fails and reasons degrade
 * to {@code unknown}. This test builds that foreign copy for real (javac + child-first loader) and
 * shows the diagnostic detecting it.
 */
@DisplayName("Foreign reason sources")
class ForeignReasonSourceTest {

    @Test
    @DisplayName("degrades foreign sources to unknown and diagnoses the duplicated interface")
    void detectsForeignCopy() throws Exception {
        final Throwable foreign = foreignReasonedException();

        // the misconfiguration: same interface name, different class -> instanceof fails
        assertThat(foreign).isNotInstanceOf(OutcomeReasonSource.class);
        assertThat(MetricTagValues.reasonCode(foreign)).isEqualTo(MetricTagValues.UNKNOWN);
        // the diagnostic makes it visible
        assertThat(MetricTagValues.isForeignReasonSource(foreign)).isTrue();
    }

    @Test
    @DisplayName("reports false for genuine sources, unclassified errors, and null")
    void noFalsePositives() {
        final RuntimeException genuine = new RuntimeException("boom") {
        };
        final class Reasoned extends RuntimeException implements OutcomeReasonSource {
            @Override
            public OutcomeReason outcomeReason() {
                return () -> "db_down";
            }
        }

        assertThat(MetricTagValues.isForeignReasonSource(new Reasoned())).isFalse();
        assertThat(MetricTagValues.isForeignReasonSource(genuine)).isFalse();
        assertThat(MetricTagValues.isForeignReasonSource(null)).isFalse();
    }

    private static Throwable foreignReasonedException() throws Exception {
        final Path sources = Files.createTempDirectory("foreign-reason");
        final Path pkg = sources.resolve("io/github/rabitem/outcomemetrics/observation");
        Files.createDirectories(pkg);
        Files.writeString(pkg.resolve("OutcomeReasonSource.java"), """
                package io.github.rabitem.outcomemetrics.observation;
                public interface OutcomeReasonSource {
                    Object outcomeReason();
                }
                """);
        Files.writeString(pkg.resolve("PluginException.java"), """
                package io.github.rabitem.outcomemetrics.observation;
                public class PluginException extends RuntimeException implements OutcomeReasonSource {
                    public Object outcomeReason() {
                        return "payment_declined";
                    }
                }
                """);
        final JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        final int result = compiler.run(null, null, null,
                pkg.resolve("OutcomeReasonSource.java").toString(),
                pkg.resolve("PluginException.java").toString());
        assertThat(result).isZero();

        final ClassLoader childFirst = new ChildFirstLoader(sources);
        final Class<?> exceptionClass = Class.forName(
                "io.github.rabitem.outcomemetrics.observation.PluginException", true, childFirst);
        return (Throwable) exceptionClass.getDeclaredConstructor().newInstance();
    }

    /** Loads the duplicated package from the temp dir before delegating to the parent. */
    private static final class ChildFirstLoader extends ClassLoader {

        private final Map<String, Path> classFiles = new HashMap<>();

        private ChildFirstLoader(final Path root) throws IOException {
            super(ForeignReasonSourceTest.class.getClassLoader());
            try (Stream<Path> files = Files.walk(root)) {
                files.filter(path -> path.toString().endsWith(".class")).forEach(path -> {
                    final String name = root.relativize(path).toString()
                            .replace(java.io.File.separatorChar, '.')
                            .replaceAll("\\.class$", "");
                    classFiles.put(name, path);
                });
            }
        }

        @Override
        protected Class<?> loadClass(final String name, final boolean resolve) throws ClassNotFoundException {
            synchronized (getClassLoadingLock(name)) {
                final Class<?> loaded = findLoadedClass(name);
                if (loaded != null) {
                    return loaded;
                }
                final Path classFile = classFiles.get(name);
                if (classFile != null) {
                    try {
                        final byte[] bytes = Files.readAllBytes(classFile);
                        return defineClass(name, bytes, 0, bytes.length);
                    } catch (final IOException e) {
                        throw new ClassNotFoundException(name, e);
                    }
                }
                return super.loadClass(name, resolve);
            }
        }
    }
}
