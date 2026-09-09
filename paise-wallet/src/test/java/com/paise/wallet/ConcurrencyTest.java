package com.paise.wallet;

import com.paise.wallet.domain.InsufficientFundsException;
import com.paise.wallet.domain.TransferRequest;
import com.paise.wallet.domain.TransferResponse;
import com.paise.wallet.service.TransferService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
public class ConcurrencyTest extends BaseIntegrationTest {

    @Autowired
    private TransferService transferService;

    @Autowired
    private JdbcTemplate jdbc;

    /** Fund a wallet directly via SQL for test setup (should be funded before transfer). */
    private void fund(String userId, long amount) {
        jdbc.update("UPDATE wallets SET balance_paise = balance_paise + ? WHERE user_id = ?", amount, userId);
    }

    private long balance(String userId) {
        Long b = jdbc.queryForObject("SELECT balance_paise FROM wallets WHERE user_id = ?", Long.class, userId);
        return b == null ? 0 : b;
    }

    @Test
    void concurrentFirstTransfers_noDuplicates_noNegatives_no500() throws Exception {
        String userA = "concA_" + UUID.randomUUID().toString().substring(0, 8);
        String userB = "concB_" + UUID.randomUUID().toString().substring(0, 8);

        // Pre-create wallets via get-or-create (as POST /accounts would)
        transferService.getOrCreateWallet(userA);
        transferService.getOrCreateWallet(userB);

        // Fund userA with enough for all transfers (ensure it can afford each attempted)
        int concurrency = 50;
        transferService.getOrCreateWallet("rich");
        fund(userA, concurrency * 1000L + 1000L); // enough balance
        transferService.getOrCreateWallet(userB);

        ExecutorService executor = Executors.newFixedThreadPool(concurrency);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(concurrency);

        // 50 concurrent first-transfers, each with a DIFFERENT idempotency key
        for (int i = 0; i < concurrency; i++) {
            int idx = i;
            String idemKey = "first_" + idx + "_" + UUID.randomUUID().toString().substring(0, 8);
            executor.submit(() -> {
                try {
                    startLatch.await();
                    TransferResponse resp = transferService.transfer(userA, new TransferRequest(userB, 1000, idemKey));
                    assertNotNull(resp);
                } catch (Exception e) {
                    fail("Unexpected exception during concurrent first-transfer: " + e);
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        assertTrue(doneLatch.await(30, TimeUnit.SECONDS), "Timed out waiting for transfers");

        // Wallets exist exactly once (no dupes). Check row counts.
        Integer aCount = jdbc.queryForObject("SELECT count(*) FROM wallets WHERE user_id = ?", Integer.class, userA);
        Integer bCount = jdbc.queryForObject("SELECT count(*) FROM wallets WHERE user_id = ?", Integer.class, userB);
        assertEquals(1, aCount, "Wallet A must exist exactly once");
        assertEquals(1, bCount, "Wallet B must exist exactly once");

        // Money conserved: A + B = initial funding amount
        long balA = balance(userA);
        long balB = balance(userB);
        assertEquals(concurrency * 1000L, balB, "All transfers should have applied");
        assertTrue(balA >= 0, "A balance must be non-negative");
        assertEquals((concurrency * 1000L + 1000L), balA + balB, "Money must be conserved");

        executor.shutdown();
    }

    @Test
    void concurrentRetries_sameIdemKey_appliedExactlyOnce() throws Exception {
        String userC = "retryC_" + UUID.randomUUID().toString().substring(0, 8);
        String userD = "retryD_" + UUID.randomUUID().toString().substring(0, 8);

        transferService.getOrCreateWallet(userC);
        transferService.getOrCreateWallet(userD);
        fund(userC, 1_000_000L); // plenty

        String idemKey = "retry_key_" + UUID.randomUUID().toString().substring(0, 8);
        int concurrency = 50;

        ExecutorService executor = Executors.newFixedThreadPool(concurrency);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(concurrency);

        // 50 concurrent retries with the SAME idempotency key and SAME body
        for (int i = 0; i < concurrency; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    TransferResponse resp = transferService.transfer(userC, new TransferRequest(userD, 500, idemKey));
                    assertNotNull(resp);
                } catch (Exception e) {
                    fail("Unexpected exception during concurrent retry: " + e);
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        assertTrue(doneLatch.await(30, TimeUnit.SECONDS), "Timed out waiting for retries");

        // Exactly ONE transfer row for this key, and balance moved exactly once (500 paise)
        Integer rowCount = jdbc.queryForObject(
                "SELECT count(*) FROM transfers WHERE from_user = ? AND idempotency_key = ?",
                Integer.class, userC, idemKey);
        assertEquals(1, rowCount, "Transfer must be applied exactly once");

        long balD = balance(userD);
        assertEquals(500, balD, "Recipient received exactly 500 paise");

        executor.shutdown();
    }

    @Test
    void sameKeyConcurrent_neverDoubleMove_and_noNegatives() throws Exception {
        String u1 = "never_" + UUID.randomUUID().toString().substring(0, 8);
        String u2 = "never2_" + UUID.randomUUID().toString().substring(0, 8);

        transferService.getOrCreateWallet(u1);
        transferService.getOrCreateWallet(u2);
        fund(u1, 10_000L);

        String idemKey = "double_" + UUID.randomUUID().toString().substring(0, 8);
        int concurrency = 30;
        ExecutorService executor = Executors.newFixedThreadPool(concurrency);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(concurrency);

        for (int i = 0; i < concurrency; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    transferService.transfer(u1, new TransferRequest(u2, 200, idemKey));
                } catch (Exception e) {
                    fail("Unexpected error: " + e);
                } finally {
                    doneLatch.countDown();
                }
            });
        }
        startLatch.countDown();
        assertTrue(doneLatch.await(30, TimeUnit.SECONDS));

        assertEquals(200, balance(u2), "Must move exactly 200 total");
        assertEquals(10_000L - 200, balance(u1), "Sender must be debited exactly once");
        assertTrue(balance(u1) >= 0, "Never negative balance");

        executor.shutdown();
    }

    @Test
    void insufficientFunds_concurrent_noOverspend() throws Exception {
        String s = "poor_" + UUID.randomUUID().toString().substring(0, 8);
        String r = "rich_r_" + UUID.randomUUID().toString().substring(0, 8);
        transferService.getOrCreateWallet(s);
        transferService.getOrCreateWallet(r);
        fund(s, 100); // only 100 paise

        int concurrency = 50;
        ExecutorService executor = Executors.newFixedThreadPool(concurrency);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(concurrency);

        // All try to spend 100 (the exact balance). Only ONE can succeed.
        for (int i = 0; i < concurrency; i++) {
            String idemKey = "spend_" + i;
            executor.submit(() -> {
                try {
                    startLatch.await();
                    try {
                        transferService.transfer(s, new TransferRequest(r, 100, idemKey));
                    } catch (InsufficientFundsException expected) {
                        // fine
                    }
                } catch (Exception e) {
                    fail("No 500s allowed, got: " + e);
                } finally {
                    doneLatch.countDown();
                }
            });
        }
        startLatch.countDown();
        assertTrue(doneLatch.await(30, TimeUnit.SECONDS));

        long balS = balance(s);
        long balR = balance(r);
        assertTrue(balS >= 0, "Sender must never go negative, got " + balS);
        // At most one transfer of 100 applied (sender has 100 total)
        assertEquals(0, balS, "Sender spent all 100 (exactly once)");
        assertEquals(100, balR, "Recipient got exactly 100");
        assertEquals(100, balS + balR, "Money conserved");

        executor.shutdown();
    }
}
