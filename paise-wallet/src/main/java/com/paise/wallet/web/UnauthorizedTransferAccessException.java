package com.paise.wallet.web;

public class UnauthorizedTransferAccessException extends RuntimeException {
    public UnauthorizedTransferAccessException(String message) {
        super(message);
    }
}
