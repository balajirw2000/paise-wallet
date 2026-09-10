package com.paise.wallet;

import com.paise.wallet.domain.IdempotencyConflictException;
import com.paise.wallet.domain.InsufficientFundsException;
import com.paise.wallet.domain.TransferRequest;
import com.paise.wallet.domain.TransferResponse;
import com.paise.wallet.service.TransferService;
import com.paise.wallet.service.WalletService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
public class IdempotencyTest extends BaseIntegrationTest {

    @Autowired
    private TransferService transferService;

    @Autowired
    private WalletService walletService;

    @Autowired
    private JdbcTemplate jdbc;

    private void fund(String userId, long amount) {
        jdbc.update("UPDATE wallets SET balance_paise = balance_paise + ? WHERE user_id = ?", amount, userId);
    }

    @Test
    void sameKeySameBody_replaysOriginalResult() {
        String a = "idemA_" + UUID.randomUUID().toString().substring(0, 8);
        String b = "idemB_" + UUID.randomUUID().toString().substring(0, 8);
        walletService.getOrCreate(a);
        walletService.getOrCreate(b);
        fund(a, 1000);

        TransferRequest req = new TransferRequest(b, 100, "idem-same");
        TransferResponse first = transferService.transfer(a, req);
        TransferResponse replay = transferService.transfer(a, req);

        assertEquals(first.transferId(), replay.transferId(), "Must return same transfer_id on replay");
        // Money moved exactly once: A lost 100, B gained 100, total unchanged
        long balB = jdbc.queryForObject("SELECT balance_paise FROM wallets WHERE user_id = ?", Long.class, b);
        assertEquals(100, balB, "Money must move exactly once");
    }

    @Test
    void sameKeyDifferentBody_returnsConflict() {
        String a = "idemC_" + UUID.randomUUID().toString().substring(0, 8);
        String b = "idemD_" + UUID.randomUUID().toString().substring(0, 8);
        walletService.getOrCreate(a);
        walletService.getOrCreate(b);
        fund(a, 5000);

        transferService.transfer(a, new TransferRequest(b, 100, "idem-conflict"));
        assertThrows(IdempotencyConflictException.class,
                () -> transferService.transfer(a, new TransferRequest(b, 200, "idem-conflict")));
    }

    @Test
    void moneyMovedExactlyOnce() {
        String a = "exactA_" + UUID.randomUUID().toString().substring(0, 8);
        String b = "exactB_" + UUID.randomUUID().toString().substring(0, 8);
        walletService.getOrCreate(a);
        walletService.getOrCreate(b);
        fund(a, 500);

        String key = "exact_key_" + UUID.randomUUID().toString().substring(0, 8);
        TransferRequest req = new TransferRequest(b, 300, key);
        transferService.transfer(a, req);
        transferService.transfer(a, req);
        transferService.transfer(a, req);

        long balA = jdbc.queryForObject("SELECT balance_paise FROM wallets WHERE user_id = ?", Long.class, a);
        long balB = jdbc.queryForObject("SELECT balance_paise FROM wallets WHERE user_id = ?", Long.class, b);
        assertEquals(200, balA, "A debited exactly once (500-300)");
        assertEquals(300, balB, "B credited exactly once");
        assertEquals(500, balA + balB, "Money conserved");
    }
}
