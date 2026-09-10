package com.paise.wallet.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.slf4j.Marker;
import org.slf4j.MarkerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

/**
 * Access log for every request: method, path, query, status, duration and caller.
 * Emits an ALERT-marked WARN for slow requests or 5xx responses so they can be
 * routed separately; INFO otherwise. Response times are also exposed via the
 * {@code http.server.requests} metric (see /metrics).
 */
@Component
@Order(2)
public class RequestLoggingFilter extends OncePerRequestFilter {
    private static final Logger log = LoggerFactory.getLogger(RequestLoggingFilter.class);
    private static final Marker ALERT = MarkerFactory.getMarker("ALERT");
    private static final long SLOW_REQUEST_MS = 2000;

    @Override
    protected boolean shouldNotFilterErrorDispatch() {
        return true;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        StatusCaptureResponseWrapper wrapped = new StatusCaptureResponseWrapper(response);
        long startNanos = System.nanoTime();
        try {
            filterChain.doFilter(request, wrapped);
        } finally {
            long durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
            int status = wrapped.getStatus();
            String method = request.getMethod();
            String path = request.getRequestURI();
            String query = request.getQueryString();
            String caller = (String) request.getAttribute("caller_id");

            String requestId = MDC.get("request_id");
            if (requestId != null) {
                MDC.put("request_id", requestId);
            }
            if (caller != null) {
                MDC.put("caller_id", caller);
            }

            if (status >= 500) {
                log.warn(ALERT, "request.error method={} path={} query={} status={} duration_ms={}",
                        method, path, query, status, durationMs);
            } else if (durationMs >= SLOW_REQUEST_MS) {
                log.warn(ALERT, "request.slow method={} path={} query={} status={} duration_ms={}",
                        method, path, query, status, durationMs);
            } else {
                log.info("request.completed method={} path={} query={} status={} duration_ms={}",
                        method, path, query, status, durationMs);
            }

            MDC.remove("caller_id");
        }
    }

    private static class StatusCaptureResponseWrapper extends HttpServletResponseWrapper {
        private int status = HttpServletResponse.SC_OK;

        StatusCaptureResponseWrapper(HttpServletResponse response) {
            super(response);
        }

        @Override
        public void setStatus(int sc) {
            status = sc;
            super.setStatus(sc);
        }

        @Override
        public void sendError(int sc) throws IOException {
            status = sc;
            super.sendError(sc);
        }

        @Override
        public void sendError(int sc, String msg) throws IOException {
            status = sc;
            super.sendError(sc, msg);
        }

        @Override
        public void sendRedirect(String location) throws IOException {
            status = HttpServletResponse.SC_MOVED_TEMPORARILY;
            super.sendRedirect(location);
        }
    }
}