package com.paise.wallet.domain;

import com.fasterxml.jackson.annotation.JsonProperty;

public record ErrorResponse(
        @JsonProperty("error_code") String errorCode,
        String message,
        @JsonProperty("request_id") String requestId
) {
}
