package com.paise.wallet.domain;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.UUID;

public record TransferRequest(
        @JsonProperty("to_user") String toUser,
        @JsonProperty("amount_paise") long amountPaise,
        @JsonProperty("idempotency_key") String idempotencyKey,
        @JsonProperty("to_wallet_id") UUID toWalletId
) {
    public TransferRequest(String toUser, long amountPaise, String idempotencyKey) {
        this(toUser, amountPaise, idempotencyKey, null);
    }
}