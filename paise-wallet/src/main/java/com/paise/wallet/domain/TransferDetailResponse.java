package com.paise.wallet.domain;

public record TransferDetailResponse(
        java.util.UUID transferId,
        String fromUser,
        String toUser,
        long amountPaise,
        String status,
        String createdAt
) {
}
