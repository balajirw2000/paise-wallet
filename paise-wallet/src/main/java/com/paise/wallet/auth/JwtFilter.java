package com.paise.wallet.auth;

import com.paise.wallet.config.AppProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
public class JwtFilter extends OncePerRequestFilter {
    private static final Logger log = LoggerFactory.getLogger(JwtFilter.class);

    private final JwtUtil jwtUtil;
    private final AppProperties props;

    public JwtFilter(JwtUtil jwtUtil, AppProperties props) {
        this.jwtUtil = jwtUtil;
        this.props = props;
    }

    private boolean isPublic(String path) {
        return path.equals("/")
                || path.equals("/index.html")
                || path.equals("/favicon.ico")
                || path.equals("/healthz")
                || path.equals("/readyz")
                || path.equals("/metrics")
                || (path.startsWith("/dev/token") && props.isDevTokensEnabled())
                || (path.equals("/dev/accounts") && props.isDevTokensEnabled())
                || path.startsWith("/actuator");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String path = request.getRequestURI();

        // Dev endpoints only exist while DEV_TOKENS_ENABLED=true
        if (path.startsWith("/dev/") && !props.isDevTokensEnabled()) {
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.getWriter().write("{\"error_code\":\"NOT_FOUND\",\"message\":\"Dev endpoints are not enabled\",\"request_id\":\"\"}");
            return;
        }

        if (isPublic(path)) {
            filterChain.doFilter(request, response);
            return;
        }

        String authHeader = request.getHeader("Authorization");
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            writeUnauthorized(response, "Missing or invalid Authorization header");
            return;
        }

        String token = authHeader.substring(7);
        try {
            String userId = jwtUtil.verifyAndExtractSub(token);
            CallerContext.set(userId);
            MDC.put("caller_id", userId);
            request.setAttribute("caller_id", userId);
            filterChain.doFilter(request, response);
        } catch (Exception e) {
            log.warn("auth.failure reason={}", e.getMessage());
            writeUnauthorized(response, "Invalid or expired token");
        } finally {
            CallerContext.clear();
            MDC.remove("caller_id");
        }
    }

    private void writeUnauthorized(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        String requestId = MDC.get("request_id");
        response.getWriter().write(
                "{\"error_code\":\"UNAUTHORIZED\",\"message\":\"" + message + "\",\"request_id\":\"" + (requestId != null ? requestId : "") + "\"}"
        );
    }
}
