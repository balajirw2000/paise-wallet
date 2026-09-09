package com.paise.wallet.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AppProperties {
    @Value("${app.jwt.secret}")
    private String jwtSecret;

    @Value("${app.jwt.expiration-hours}")
    private long expirationHours;

    @Value("${app.dev-tokens-enabled}")
    private boolean devTokensEnabled;

    public String getJwtSecret() { return jwtSecret; }
    public long getExpirationHours() { return expirationHours; }
    public boolean isDevTokensEnabled() { return devTokensEnabled; }
}
