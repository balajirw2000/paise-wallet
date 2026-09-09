package com.paise.wallet.domain;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.UUID;

public record TransferResponse(
        @JsonProperty("transfer_id") UUID transferId,
        @JsonProperty("new_balance") long newBalance
) {
}
