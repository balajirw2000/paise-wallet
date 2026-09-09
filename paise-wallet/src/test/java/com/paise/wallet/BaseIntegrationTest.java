package com.paise.wallet;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest
public abstract class BaseIntegrationTest {

    @DynamicPropertySource
    static void overrideProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:postgresql://localhost:5432/paise_wallet");
        registry.add("spring.datasource.username", () -> "paise");
        registry.add("spring.datasource.password", () -> "paise");
        registry.add("app.dev-tokens-enabled", () -> "true");
        registry.add("app.jwt.secret", () -> "test-secret-key-that-is-at-least-32-chars-long!!");
    }
}
