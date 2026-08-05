package io.github.rabitem.outcomemetrics;

import io.micrometer.common.KeyValues;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("MeasuredOutcomeSupport")
class MeasuredOutcomeSupportTest {

    @Test
    @DisplayName("prefers method name and merges tags")
    void resolveNameAndTags() throws Exception {
        final MeasuredOutcome type = TypeAnnotated.class.getAnnotation(MeasuredOutcome.class);
        final MeasuredOutcome method = TypeAnnotated.class.getMethod("run").getAnnotation(MeasuredOutcome.class);

        assertThat(MeasuredOutcomeSupport.resolveName(type, method)).isEqualTo("method.op");
        final KeyValues tags = MeasuredOutcomeSupport.resolveTags(type, method);
        assertThat(tags.stream().map(kv -> kv.getKey() + "=" + kv.getValue())).containsExactly(
                "frame=type",
                "layer=method");
        assertThat(MeasuredOutcomeSupport.resolveName(type, null)).isEqualTo("type.op");
        assertThat(MeasuredOutcomeSupport.resolveTags(null, null)).isEqualTo(KeyValues.empty());
    }

    @Test
    @DisplayName("rejects missing observation names")
    void missingName() {
        assertThatThrownBy(() -> MeasuredOutcomeSupport.resolveName(null, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("@MeasuredOutcome");
    }

    @Test
    @DisplayName("finds type and method annotations on interfaces")
    void interfaceAnnotations() throws Exception {
        assertThat(MeasuredOutcomeSupport.findTypeAnnotation(Impl.class)).isNotNull()
                .extracting(MeasuredOutcome::name).isEqualTo("iface.type");
        assertThat(MeasuredOutcomeSupport.findMethodAnnotation(Impl.class.getMethod("work")))
                .isNotNull()
                .extracting(MeasuredOutcome::name)
                .isEqualTo("iface.method");
    }

    @MeasuredOutcome(name = "type.op", tags = "frame=type")
    private static final class TypeAnnotated {
        @MeasuredOutcome(name = "method.op", tags = "layer=method")
        public void run() {
        }
    }

    @MeasuredOutcome(name = "iface.type")
    private interface Contract {
        @MeasuredOutcome(name = "iface.method")
        void work();
    }

    private static final class Impl implements Contract {
        @Override
        public void work() {
        }
    }
}
