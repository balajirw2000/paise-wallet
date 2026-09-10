package com.paise.wallet.service;

import com.paise.wallet.domain.*;
import com.paise.wallet.repo.TransferRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

import static org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRED;
import static org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED;

@Service
public class TransferService {
    private static final Logger log = LoggerFactory.getLogger(TransferService.class);

    private final TransactionTemplate txTemplate;
    private final WalletService walletService;
    private final TransferRepository transferRepo;
    private final Counter appliedCounter;
    private final Counter rejectedCounter;

    public TransferService(WalletService walletService,
                           TransferRepository transferRepo, MeterRegistry meterRegistry,
                           org.springframework.transaction.PlatformTransactionManager txManager) {
        this.walletService = walletService;
        this.transferRepo = transferRepo;
        this.appliedCounter = Counter.builder("transfers_applied_total").register(meterRegistry);
        this.rejectedCounter = Counter.builder("transfers_rejected_total").register(meterRegistry);
        this.txTemplate = new TransactionTemplate(txManager);
        this.txTemplate.setIsolationLevel(ISOLATION_READ_COMMITTED);
        this.txTemplate.setPropagationBehavior(PROPAGATION_REQUIRED);
    }

    public TransferResponse transfer(String callerId, TransferRequest request) {
        // Validate BEFORE entering transaction
        if (request.amountPaise() <= 0) {
            throw new InvalidTransferException("amount_paise must be greater than 0");
        }
        if (request.idempotencyKey() == null || request.idempotencyKey().isBlank()) {
            throw new InvalidTransferException("idempotency_key must not be blank");
        }
        boolean hasUser = request.toUser() != null && !request.toUser().isBlank();
        boolean hasWalletId = request.toWalletId() != null;
        if (hasUser == hasWalletId) {
            throw new InvalidTransferException("Provide exactly one of to_user or to_wallet_id");
        }
        // Fast-path check for the common user-id form. The wallet-id form is checked
        // against the resolved identity inside the transaction (executeTransfer).
        if (hasUser && callerId.equals(request.toUser())) {
            throw new InvalidTransferException("Self-transfer is not allowed");
        }

        UUID transferId = UUID.randomUUID();

        return executeTransfer(callerId, request, transferId);
    }

    private TransferResponse executeTransfer(String callerId, TransferRequest request,
                                              UUID transferId) {
        return txTemplate.execute(status -> {
            // 1. Resolve the recipient to a user id.
            //    wallet_id -> wallet MUST already exist (never auto-created).
            //    username/email/phone (to_user) -> user resolver: create the wallet if absent.
            String recipientUserId;
            if (request.toWalletId() != null) {
                recipientUserId = walletService.findByWalletId(request.toWalletId())
                        .map(Wallet::userId)
                        .orElseThrow(() -> new InvalidTransferException("Recipient wallet not found"));
            } else {
                walletService.getOrCreate(request.toUser());
                recipientUserId = request.toUser();
            }

            // Self-transfer must be rejected regardless of how the recipient was addressed.
            if (callerId.equals(recipientUserId)) {
                throw new InvalidTransferException("Self-transfer is not allowed");
            }

            // 2. Get-or-create the caller's wallet (INSERT ON CONFLICT DO NOTHING)
            walletService.getOrCreate(callerId);

            String requestHash = sha256(recipientUserId + ":" + request.amountPaise());

            // 3. Lock both wallets in deterministic order (user_id lex order)
            String first = callerId.compareTo(recipientUserId) <= 0 ? callerId : recipientUserId;
            String second = callerId.compareTo(recipientUserId) <= 0 ? recipientUserId : callerId;

            var wallet1Opt = walletService.lockForUpdate(first);
            var wallet2Opt = walletService.lockForUpdate(second);

            if (wallet1Opt.isEmpty() || wallet2Opt.isEmpty()) {
                throw new RuntimeException("Wallet not found after get-or-create");
            }

            var callerWallet = callerId.equals(first) ? wallet1Opt.get() : wallet2Opt.get();

            // 4. Check idempotency
            Optional<Transfer> existing = transferRepo.findByFromUserAndIdempotencyKey(callerId, request.idempotencyKey());
            if (existing.isPresent()) {
                Transfer existingTransfer = existing.get();
                if (existingTransfer.requestHash().equals(requestHash)) {
                    // Replay — return original result without mutating
                    log.info("transfer.idempotent_replay from={} idem_key={}", callerId, request.idempotencyKey());
                    return new TransferResponse(existingTransfer.transferId(), callerWallet.balancePaise());
                } else {
                    // Different body with same key — conflict
                    log.warn("transfer.conflict from={} idem_key={}", callerId, request.idempotencyKey());
                    throw new IdempotencyConflictException("Same idempotency key with different request body");
                }
            }

            // 5. Check balance
            if (callerWallet.balancePaise() < request.amountPaise()) {
                log.info("transfer.insufficient_funds from={} balance={} amount={}",
                        callerId, callerWallet.balancePaise(), request.amountPaise());
                rejectedCounter.increment();
                throw new InsufficientFundsException("Insufficient funds");
            }

            // 6. Insert transfer record (may throw DuplicateKeyException in race — caught in repo)
            Transfer transfer = new Transfer(transferId, callerId, recipientUserId,
                    request.amountPaise(), request.idempotencyKey(), requestHash, "APPLIED", null);
            Optional<Transfer> inserted = transferRepo.insert(transfer);
            if (inserted.isEmpty()) {
                // Race: another concurrent request inserted first with same key
                // Re-read and verify
                Optional<Transfer> raceExisting = transferRepo.findByFromUserAndIdempotencyKey(callerId, request.idempotencyKey());
                if (raceExisting.isPresent()) {
                    Transfer raceTransfer = raceExisting.get();
                    if (raceTransfer.requestHash().equals(requestHash)) {
                        log.info("transfer.idempotent_replay from={} idem_key={} (race)", callerId, request.idempotencyKey());
                        return new TransferResponse(raceTransfer.transferId(), callerWallet.balancePaise());
                    } else {
                        throw new IdempotencyConflictException("Same idempotency key with different request body");
                    }
                }
                throw new RuntimeException("Transfer insert failed unexpectedly");
            }

            // 7. Mutate balances
            walletService.debit(callerId, request.amountPaise());
            walletService.credit(recipientUserId, request.amountPaise());

            log.info("transfer.applied from={} to={} amount={} transfer_id={}",
                    callerId, recipientUserId, request.amountPaise(), transferId);
            appliedCounter.increment();

            // 8. Read back caller's new balance
            var updatedCallerWallet = walletService.lockForUpdate(callerId);
            long newBalance = updatedCallerWallet.map(Wallet::balancePaise).orElse(0L);

            return new TransferResponse(transferId, newBalance);
        });
    }

    public Optional<Transfer> getTransfer(UUID transferId, String callerId) {
        Optional<Transfer> transfer = transferRepo.findByTransferId(transferId);
        if (transfer.isPresent()) {
            Transfer t = transfer.get();
            if (!t.fromUser().equals(callerId) && !t.toUser().equals(callerId)) {
                return Optional.empty(); // Not a participant
            }
        }
        return transfer;
    }

    private String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }
}
