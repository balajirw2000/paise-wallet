package com.paise.wallet.service;

import com.paise.wallet.domain.Wallet;
import com.paise.wallet.repo.WalletRepository;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.UUID;

/**
 * Owns all single-wallet operations (wallets aggregate). Methods mutate or read
 * one wallet at a time; atomic multi-wallet flows belong to TransferService.
 * The methods are intentionally not transactional: they participate in the
 * caller's transaction when invoked from one, and run as single statements
 * (auto-commit) when invoked standalone.
 */
@Service
public class WalletService {

    private final WalletRepository walletRepo;

    public WalletService(WalletRepository walletRepo) {
        this.walletRepo = walletRepo;
    }

    public Wallet getOrCreate(String userId) {
        walletRepo.getOrCreate(userId);
        return walletRepo.findById(userId).orElseThrow();
    }

    public Optional<Wallet> getBalance(String userId) {
        return walletRepo.findById(userId);
    }

    public Optional<Wallet> findByWalletId(UUID walletId) {
        return walletRepo.findByWalletId(walletId);
    }

    public Optional<Wallet> lockForUpdate(String userId) {
        return walletRepo.findByIdForUpdate(userId);
    }

    public void credit(String userId, long amount) {
        walletRepo.credit(userId, amount);
    }

    public void debit(String userId, long amount) {
        walletRepo.debit(userId, amount);
    }
}