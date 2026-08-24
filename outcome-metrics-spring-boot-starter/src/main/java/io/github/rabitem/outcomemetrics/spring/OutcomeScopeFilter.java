package io.github.rabitem.outcomemetrics.spring;

import io.github.rabitem.outcomemetrics.observation.OutcomeScope;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;

import java.io.IOException;

/**
 * Opens an {@link OutcomeScope} per servlet request so repeat observations within one request
 * coalesce ({@code occurrence=first|repeat}, issue #18/#54).
 *
 * <p>Opt-in via {@code outcome.metrics.scope.enabled=true} — enabling changes the occurrence split
 * on existing series, so it is never switched on silently. Servlet (blocking) dispatch only:
 * async servlet completions after the chain returns, WebFlux, and reactive Quarkus dispatch hop
 * threads, where a ThreadLocal scope would corrupt rather than help — those paths fail open to
 * {@code occurrence=first} by design.
 *
 * @since 0.1.0
 */
public final class OutcomeScopeFilter implements Filter {

    @Override
    public void doFilter(final ServletRequest request, final ServletResponse response, final FilterChain chain)
            throws IOException, ServletException {
        try (OutcomeScope scope = OutcomeScope.open()) {
            chain.doFilter(request, response);
        }
    }
}
