package com.paise.wallet.config;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/**
 * Translates libpq-style connection strings (postgres:// or postgresql://,
 * the format used by Railway, Neon, Supabase and Render) into a JDBC URL
 * plus credentials that Spring's DataSource configuration can consume.
 *
 * The PostgreSQL JDBC driver only accepts jdbc:postgresql:// URLs, so the raw
 * DATABASE_URL would otherwise cause startup to fail.
 */
@Order(Ordered.HIGHEST_PRECEDENCE)
public class LibpqUrlEnvironmentPostProcessor implements EnvironmentPostProcessor {

    private static final String DATASOURCE_OVERRIDES = "libpqDatasourceOverrides";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        String raw = environment.getProperty("DATABASE_URL");
        if (raw == null || raw.isBlank()) {
            raw = environment.getProperty("SPRING_DATASOURCE_URL");
        }
        if (raw == null || raw.isBlank() || !isLibpqUrl(raw)) {
            return;
        }
        environment.getPropertySources().addFirst(toJdbcProperties(raw));
    }

    private boolean isLibpqUrl(String raw) {
        String scheme = raw.split(":", 2)[0].toLowerCase(java.util.Locale.ROOT);
        return scheme.equals("postgres") || scheme.equals("postgresql");
    }

    private MapPropertySource toJdbcProperties(String raw) {
        try {
            URI uri = new URI(raw);
            Map<String, Object> props = new LinkedHashMap<>();
            props.put("spring.datasource.url", toJdbcUrl(uri));

            String userInfo = uri.getUserInfo();
            if (userInfo != null) {
                int colon = userInfo.indexOf(':');
                if (colon >= 0) {
                    props.put("spring.datasource.username", userInfo.substring(0, colon));
                    props.put("spring.datasource.password", userInfo.substring(colon + 1));
                } else {
                    props.put("spring.datasource.username", userInfo);
                }
            }
            return new MapPropertySource(DATASOURCE_OVERRIDES, props);
        } catch (URISyntaxException e) {
            throw new IllegalStateException("DATABASE_URL is not a valid postgresql:// URL: " + raw, e);
        }
    }

    private String toJdbcUrl(URI uri) {
        StringBuilder jdbc = new StringBuilder("jdbc:postgresql://").append(uri.getHost());
        if (uri.getPort() != -1) {
            jdbc.append(':').append(uri.getPort());
        }
        if (uri.getPath() != null && !uri.getPath().isEmpty()) {
            jdbc.append(uri.getPath());
        }
        if (uri.getQuery() != null) {
            jdbc.append('?').append(uri.getRawQuery());
        }
        return jdbc.toString();
    }
}