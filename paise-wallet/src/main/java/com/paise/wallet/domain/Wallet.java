package com.paise.wallet.domain;

import java.time.Instant;
import java.util.UUID;

public record Wallet(UUID walletId, String userId, long balancePaise, Instant createdAt) {
}
