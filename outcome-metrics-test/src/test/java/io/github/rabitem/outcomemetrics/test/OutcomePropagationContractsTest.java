package io.github.rabitem.outcomemetrics.test;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("OutcomePropagationContracts")
class OutcomePropagationContractsTest {

    @Test
    @DisplayName("passes for real executors where scopes stay confined and deferrals settle")
    void confinedExecutorPasses() throws Exception {
        final ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            assertThatCode(() -> {
                OutcomePropagationContracts.assertScopeConfinedAcrossExecutor(executor);
                OutcomePropagationContracts.assertDeferredSettlesAcrossExecutor(executor);
            }).doesNotThrowAnyException();
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    @DisplayName("fails with guidance when the scope effectively propagates (same-thread execution)")
    void propagatingExecutorFails() {
        // a direct executor is behaviorally identical to a thread-local-propagating agent
        assertThatThrownBy(() ->
                OutcomePropagationContracts.assertScopeConfinedAcrossExecutor(Runnable::run))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("propagated across the executor")
                .hasMessageContaining("corrupt occurrence-filtered SLIs");
    }
}
