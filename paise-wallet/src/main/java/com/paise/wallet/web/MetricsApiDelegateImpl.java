package com.paise.wallet.web;

import com.paise.wallet.web.api.MetricsApiDelegate;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

@Service
public class MetricsApiDelegateImpl implements MetricsApiDelegate {

    private final PrometheusMeterRegistry prometheusRegistry;

    public MetricsApiDelegateImpl(PrometheusMeterRegistry prometheusRegistry) {
        this.prometheusRegistry = prometheusRegistry;
    }

    @Override
    public ResponseEntity<String> scrapeMetrics() {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, "text/plain; version=0.0.4; charset=utf-8")
                .body(prometheusRegistry.scrape());
    }
}