package com.paise.wallet.web;

import com.paise.wallet.domain.ErrorResponse;
import com.paise.wallet.domain.IdempotencyConflictException;
import com.paise.wallet.domain.InsufficientFundsException;
import com.paise.wallet.domain.InvalidTransferException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(InvalidTransferException.class)
    public ResponseEntity<ErrorResponse> handleInvalidTransfer(InvalidTransferException ex) {
        return ResponseEntity.badRequest().body(
                new ErrorResponse("INVALID_REQUEST", ex.getMessage(), MDC.get("request_id"))
        );
    }

    @ExceptionHandler(InsufficientFundsException.class)
    public ResponseEntity<ErrorResponse> handleInsufficientFunds(InsufficientFundsException ex) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(
                new ErrorResponse("INSUFFICIENT_FUNDS", ex.getMessage(), MDC.get("request_id"))
        );
    }

    @ExceptionHandler(IdempotencyConflictException.class)
    public ResponseEntity<ErrorResponse> handleConflict(IdempotencyConflictException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(
                new ErrorResponse("IDEMPOTENCY_CONFLICT", ex.getMessage(), MDC.get("request_id"))
        );
    }

    @ExceptionHandler(UnauthorizedTransferAccessException.class)
    public ResponseEntity<ErrorResponse> handleUnauthorized(UnauthorizedTransferAccessException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(
                new ErrorResponse("FORBIDDEN", ex.getMessage(), MDC.get("request_id"))
        );
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex) {
        String msg = ex.getBindingResult().getFieldErrors().stream()
                .map(e -> e.getField() + ": " + e.getDefaultMessage())
                .reduce((a, b) -> a + "; " + b)
                .orElse("Validation failed");
        return ResponseEntity.badRequest().body(
                new ErrorResponse("VALIDATION_ERROR", msg, MDC.get("request_id"))
        );
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGeneric(Exception ex) {
        log.error("Unhandled exception", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
                new ErrorResponse("INTERNAL_ERROR", "An unexpected error occurred", MDC.get("request_id"))
        );
    }
}
