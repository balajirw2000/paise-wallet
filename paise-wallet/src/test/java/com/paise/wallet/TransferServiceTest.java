package com.paise.wallet;

import com.paise.wallet.domain.InsufficientFundsException;
import com.paise.wallet.domain.InvalidTransferException;
import com.paise.wallet.domain.TransferRequest;
import com.paise.wallet.domain.TransferResponse;
import com.paise.wallet.service.TransferService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
public class TransferServiceTest extends BaseIntegrationTest {

    @Autowired
    private TransferService transferService;

    @Autowired
    private JdbcTemplate jdbc;

    private void fund(String userId, long amount) {
        jdbc.update("UPDATE wallets SET balance_paise = balance_paise + ? WHERE user_id = ?", amount, userId);
    }

    @Test
    void happyPath_transfersCorrectly() {
        String a = "a_" + UUID.randomUUID().toString().substring(0, 8);
        String b = "b_" + UUID.randomUUID().toString().substring(0, 8);
        transferService.getOrCreateWallet(a);
        transferService.getOrCreateWallet(b);
        fund(a, 1000);

        TransferResponse resp = transferService.transfer(a, new TransferRequest(b, 250, "hp-1"));
        assertNotNull(resp.transferId());

        long balA = jdbc.queryForObject("SELECT balance_paise FROM wallets WHERE user_id = ?", Long.class, a);
        long balB = jdbc.queryForObject("SELECT balance_paise FROM wallets WHERE user_id = ?", Long.class, b);
        assertEquals(750, balA);
        assertEquals(250, balB);
    }

    @Test
    void insufficientFunds_rejected422() {
        String a = "poor_" + UUID.randomUUID().toString().substring(0, 8);
        String b = "ok_" + UUID.randomUUID().toString().substring(0, 8);
        transferService.getOrCreateWallet(a);
        transferService.getOrCreateWallet(b);
        fund(a, 50);

        assertThrows(InsufficientFundsException.class,
                () -> transferService.transfer(a, new TransferRequest(b, 100, "insuff-1")));
        assertEquals(50, jdbc.queryForObject("SELECT balance_paise FROM wallets WHERE user_id = ?", Long.class, a),
                "No balance change on rejection");
    }

    @Test
    void selfTransfer_rejected400() {
        String a = "self_" + UUID.randomUUID().toString().substring(0, 8);
        transferService.getOrCreateWallet(a);
        fund(a, 1000);
        assertThrows(InvalidTransferException.class,
                () -> transferService.transfer(a, new TransferRequest(a, 10, "self-1")));
    }

    @Test
    void zeroAmount_rejected400() {
        assertThrows(InvalidTransferException.class,
                () -> transferService.transfer("x", new TransferRequest("y", 0, "zero-1")));
    }

    @Test
    void negativeAmount_rejected400() {
        assertThrows(InvalidTransferException.class,
                () -> transferService.transfer("x", new TransferRequest("y", -5, "neg-1")));
    }

    @Test
    void createsCounterpartyWalletAutomatically() {
        String sender = "auto_" + UUID.randomUUID().toString().substring(0, 8);
        String recipient = "auto2_" + UUID.randomUUID().toString().substring(0, 8);
        transferService.getOrCreateWallet(sender);
        fund(sender, 1000);

        // recipient has NO wallet yet — should be auto-created and credited in one tx
        TransferResponse resp = transferService.transfer(sender, new TransferRequest(recipient, 100, "auto-key"));
        assertNotNull(resp.transferId());

        Integer cnt = jdbc.queryForObject("SELECT count(*) FROM wallets WHERE user_id = ?", Integer.class, recipient);
        assertEquals(1, cnt, "Recipient wallet created exactly once");
        long balR = jdbc.queryForObject("SELECT balance_paise FROM wallets WHERE user_id = ?", Long.class, recipient);
        assertEquals(100, balR);
    }

    @Test
    void recipientWalletId_mustAlreadyExist_happyPath() {
        String sender = "wid_owner_" + UUID.randomUUID().toString().substring(0, 8);
        String recipient = "wid_rec_" + UUID.randomUUID().toString().substring(0, 8);
        transferService.getOrCreateWallet(sender);
        transferService.getOrCreateWallet(recipient);
        fund(sender, 1000);
        UUID recipientWalletId = jdbc.queryForObject(
                "SELECT wallet_id FROM wallets WHERE user_id = ?", UUID.class, recipient);

        TransferResponse resp = transferService.transfer(
                sender, new TransferRequest(null, 250, "wid-key", recipientWalletId));
        assertNotNull(resp.transferId());

        long balR = jdbc.queryForObject("SELECT balance_paise FROM wallets WHERE user_id = ?", Long.class, recipient);
        assertEquals(250, balR);
    }

    @Test
    void recipientWalletId_doesNotExist_rejectedAndNotCreated() {
        String sender = "wid_miss_" + UUID.randomUUID().toString().substring(0, 8);
        transferService.getOrCreateWallet(sender);
        fund(sender, 1000);

        UUID randomWalletId = UUID.randomUUID();
        assertThrows(InvalidTransferException.class,
                () -> transferService.transfer(sender, new TransferRequest(null, 100, "wid-miss-key", randomWalletId)));

        // A wallet-id recipient is NEVER auto-created.
        Integer cnt = jdbc.queryForObject(
                "SELECT count(*) FROM wallets WHERE wallet_id = ?", Integer.class, randomWalletId);
        assertEquals(0, cnt, "No wallet may be auto-created for an unknown wallet id");
        long balS = jdbc.queryForObject("SELECT balance_paise FROM wallets WHERE user_id = ?", Long.class, sender);
        assertEquals(1000, balS, "Sender balance unchanged on rejection");
    }

    @Test
    void selfTransfer_viaWalletId_rejected400() {
        String a = "wid_self_" + UUID.randomUUID().toString().substring(0, 8);
        transferService.getOrCreateWallet(a);
        fund(a, 1000);
        UUID ownWalletId = jdbc.queryForObject(
                "SELECT wallet_id FROM wallets WHERE user_id = ?", UUID.class, a);

        assertThrows(InvalidTransferException.class,
                () -> transferService.transfer(a, new TransferRequest(null, 10, "wid-self-key", ownWalletId)));
    }

    @Test
    void sameKey_sameRecipientDifferentIdentifier_replaysInsteadOfDoubleMove() {
        String a = "replay_owner_" + UUID.randomUUID().toString().substring(0, 8);
        String b = "replay_recip_" + UUID.randomUUID().toString().substring(0, 8);
        transferService.getOrCreateWallet(a);
        transferService.getOrCreateWallet(b);
        fund(a, 1000);
        UUID bWalletId = jdbc.queryForObject(
                "SELECT wallet_id FROM wallets WHERE user_id = ?", UUID.class, b);

        String key = "same-target-" + UUID.randomUUID().toString().substring(0, 8);
        TransferRequest byUser = new TransferRequest(b, 300, key);
        TransferRequest byWalletId = new TransferRequest(null, 300, key, bWalletId);

        TransferResponse first = transferService.transfer(a, byUser);
        TransferResponse replay = transferService.transfer(a, byWalletId);

        assertEquals(first.transferId(), replay.transferId(), "Both identifiers target the same user -> replay");
        long balB = jdbc.queryForObject("SELECT balance_paise FROM wallets WHERE user_id = ?", Long.class, b);
        assertEquals(300, balB, "Money moved exactly once");
    }

    @Test
    void bothRecipientIdentifiers_rejected400() {
        assertThrows(InvalidTransferException.class,
                () -> transferService.transfer("x", new TransferRequest("y", 100, "both-key", UUID.randomUUID())));
    }

    @Test
    void noRecipientIdentifier_rejected400() {
        assertThrows(InvalidTransferException.class,
                () -> transferService.transfer("x", new TransferRequest(null, 100, "none-key", null)));
    }
}
