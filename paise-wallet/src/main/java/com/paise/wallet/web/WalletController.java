package com.paise.wallet.web;

import com.paise.wallet.config.AppProperties;
import com.paise.wallet.auth.CallerContext;
import com.paise.wallet.auth.JwtUtil;
import com.paise.wallet.domain.*;
import com.paise.wallet.service.TransferService;
import jakarta.validation.Valid;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

@RestController
public class WalletController {
    private static final Logger log = LoggerFactory.getLogger(WalletController.class);

    private final TransferService transferService;
    private final JwtUtil jwtUtil;
    private final AppProperties props;
    private final JdbcTemplate jdbc;
    private final PrometheusMeterRegistry prometheusRegistry;

    public WalletController(TransferService transferService, JwtUtil jwtUtil, AppProperties props,
                            JdbcTemplate jdbc, PrometheusMeterRegistry prometheusRegistry) {
        this.transferService = transferService;
        this.jwtUtil = jwtUtil;
        this.props = props;
        this.jdbc = jdbc;
        this.prometheusRegistry = prometheusRegistry;
    }

    @PostMapping("/accounts")
    public ResponseEntity<Map<String, Object>> createOrGetAccount() {
        String callerId = CallerContext.get();
        Wallet wallet = transferService.getOrCreateWallet(callerId);
        return ResponseEntity.ok(Map.of(
                "user_id", wallet.userId(),
                "wallet_id", wallet.walletId(),
                "balance_paise", wallet.balancePaise()
        ));
    }

    @GetMapping("/accounts/me")
    public ResponseEntity<Map<String, Object>> getMyAccount() {
        String callerId = CallerContext.get();
        Wallet wallet = transferService.getBalance(callerId)
                .orElseThrow(() -> new InvalidTransferException("Wallet not found. Create one first with POST /accounts."));
        return ResponseEntity.ok(Map.of(
                "user_id", wallet.userId(),
                "wallet_id", wallet.walletId(),
                "balance_paise", wallet.balancePaise()
        ));
    }

    @PostMapping("/transfers")
    public ResponseEntity<TransferResponse> transfer(@Valid @RequestBody TransferRequest request) {
        String callerId = CallerContext.get();
        TransferResponse response = transferService.transfer(callerId, request);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/transfers/{id}")
    public ResponseEntity<Map<String, Object>> getTransfer(@PathVariable UUID id) {
        String callerId = CallerContext.get();
        Transfer transfer = transferService.getTransfer(id, callerId)
                .orElseThrow(() -> new UnauthorizedTransferAccessException("Transfer not found or you are not a participant"));
        return ResponseEntity.ok(Map.of(
                "transfer_id", transfer.transferId(),
                "from_user", transfer.fromUser(),
                "to_user", transfer.toUser(),
                "amount_paise", transfer.amountPaise(),
                "status", transfer.status(),
                "created_at", transfer.createdAt()
        ));
    }

    @GetMapping("/healthz")
    public ResponseEntity<Map<String, String>> healthz() {
        return ResponseEntity.ok(Map.of("status", "UP"));
    }

    @GetMapping("/readyz")
    public ResponseEntity<Map<String, String>> readyz() {
        try {
            jdbc.queryForObject("SELECT 1", Integer.class);
            return ResponseEntity.ok(Map.of("status", "UP"));
        } catch (Exception e) {
            log.warn("readiness check failed: {}", e.getMessage());
            return ResponseEntity.status(503).body(Map.of("status", "DOWN"));
        }
    }

    @GetMapping("/dev/token")
    public ResponseEntity<Map<String, String>> devToken(@RequestParam String user) {
        if (!props.isDevTokensEnabled()) {
            throw new InvalidTransferException("Dev tokens are not enabled");
        }
        String token = jwtUtil.mint(user);
        return ResponseEntity.ok(Map.of("token", token));
    }

    @GetMapping("/metrics")
    public ResponseEntity<String> metrics() {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, "text/plain; version=0.0.4; charset=utf-8")
                .body(prometheusRegistry.scrape());
    }

    /** Dev-only endpoint (enabled when DEV_TOKENS_ENABLED=true): fund a user's wallet for testing. */
    @PostMapping("/dev/fund")
    public ResponseEntity<Map<String, Object>> devFund(@RequestBody Map<String, Object> body) {
        if (!props.isDevTokensEnabled()) {
            throw new InvalidTransferException("Dev funding is not enabled");
        }
        String userId = (String) body.get("user_id");
        Number amount = (Number) body.get("amount_paise");
        if (userId == null || userId.isBlank() || amount == null || amount.longValue() <= 0) {
            throw new InvalidTransferException("user_id and positive amount_paise required");
        }
        transferService.getOrCreateWallet(userId);
        jdbc.update("UPDATE wallets SET balance_paise = balance_paise + ? WHERE user_id = ?", amount.longValue(), userId);
        Map<String, Object> wallet = jdbc.queryForMap(
                "SELECT wallet_id, user_id, balance_paise FROM wallets WHERE user_id = ?", userId);
        return ResponseEntity.ok(wallet);
    }
}
