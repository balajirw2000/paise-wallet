package com.paise.wallet.domain;

import java.time.Instant;
import java.util.UUID;

public record Transfer(
        UUID transferId,
        String fromUser,
        String toUser,
        long amountPaise,
        String idempotencyKey,
        String requestHash,
        String status,
        Instant createdAt
) {
}
