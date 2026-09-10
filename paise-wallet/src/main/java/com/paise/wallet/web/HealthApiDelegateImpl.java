package com.paise.wallet.web;

import com.paise.wallet.web.api.HealthApiDelegate;
import com.paise.wallet.web.model.HealthStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class HealthApiDelegateImpl implements HealthApiDelegate {
    private static final Logger log = LoggerFactory.getLogger(HealthApiDelegateImpl.class);

    private final JdbcTemplate jdbc;

    public HealthApiDelegateImpl(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public ResponseEntity<HealthStatus> healthz() {
        return ResponseEntity.ok(new HealthStatus().status(HealthStatus.StatusEnum.UP));
    }

    @Override
    public ResponseEntity<HealthStatus> readyz() {
        try {
            jdbc.queryForObject("SELECT 1", Integer.class);
            return ResponseEntity.ok(new HealthStatus().status(HealthStatus.StatusEnum.UP));
        } catch (Exception e) {
            log.warn("readiness check failed: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(new HealthStatus().status(HealthStatus.StatusEnum.DOWN));
        }
    }
}