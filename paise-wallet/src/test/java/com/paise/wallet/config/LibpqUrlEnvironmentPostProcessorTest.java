package com.paise.wallet.config;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

import static org.assertj.core.api.Assertions.assertThat;

class LibpqUrlEnvironmentPostProcessorTest {

    @Test
    void convertsLibpqUrlIntoJdbcUrlAndCredentials() {
        StandardEnvironment env = new StandardEnvironment();
        env.getPropertySources().addFirst(new MapPropertySource("test",
                Map.of("DATABASE_URL", "postgres://alice:s3cret@host.internal:5432/paise_wallet?sslmode=require")));

        new LibpqUrlEnvironmentPostProcessor().postProcessEnvironment(env, new SpringApplication(Object.class));

        assertThat(env.getProperty("spring.datasource.url"))
                .isEqualTo("jdbc:postgresql://host.internal:5432/paise_wallet?sslmode=require");
        assertThat(env.getProperty("spring.datasource.username")).isEqualTo("alice");
        assertThat(env.getProperty("spring.datasource.password")).isEqualTo("s3cret");
    }

    @Test
    void acceptsPostgresqlSchemeAndNoCredentials() {
        StandardEnvironment env = new StandardEnvironment();
        env.getPropertySources().addFirst(new MapPropertySource("test",
                Map.of("DATABASE_URL", "postgresql://host:5432/db")));

        new LibpqUrlEnvironmentPostProcessor().postProcessEnvironment(env, new SpringApplication(Object.class));

        assertThat(env.getProperty("spring.datasource.url")).isEqualTo("jdbc:postgresql://host:5432/db");
        assertThat(env.getProperty("spring.datasource.username")).isNull();
    }

    @Test
    void leavesJdbcUrlsUntouched() {
        StandardEnvironment env = new StandardEnvironment();
        env.getPropertySources().addFirst(new MapPropertySource("test",
                Map.of("DATABASE_URL", "jdbc:postgresql://localhost:5432/paise_wallet")));

        new LibpqUrlEnvironmentPostProcessor().postProcessEnvironment(env, new SpringApplication(Object.class));

        assertThat(env.getProperty("spring.datasource.url")).isNull();
    }

    @Test
    void doesNothingWhenNoDatabaseUrlIsSet() {
        StandardEnvironment env = new StandardEnvironment();

        new LibpqUrlEnvironmentPostProcessor().postProcessEnvironment(env, new SpringApplication(Object.class));

        assertThat(env.getProperty("spring.datasource.url")).isNull();
    }
}