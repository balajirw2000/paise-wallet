package com.paise.wallet.web;

import com.paise.wallet.domain.ErrorResponse;
import com.paise.wallet.domain.IdempotencyConflictException;
import com.paise.wallet.domain.InsufficientFundsException;
import com.paise.wallet.domain.InvalidTransferException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.slf4j.Marker;
import org.slf4j.MarkerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private static final Marker ALERT = MarkerFactory.getMarker("ALERT");

    @ExceptionHandler(InvalidTransferException.class)
    public ResponseEntity<ErrorResponse> handleInvalidTransfer(InvalidTransferException ex) {
        log.warn("exception.invalid_request type={} message={}", ex.getClass().getSimpleName(), ex.getMessage());
        return ResponseEntity.badRequest().body(
                new ErrorResponse("INVALID_REQUEST", ex.getMessage(), MDC.get("request_id"))
        );
    }

    @ExceptionHandler(InsufficientFundsException.class)
    public ResponseEntity<ErrorResponse> handleInsufficientFunds(InsufficientFundsException ex) {
        log.warn(ALERT, "exception.insufficient_funds type={} message={}", ex.getClass().getSimpleName(), ex.getMessage(), ex);
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(
                new ErrorResponse("INSUFFICIENT_FUNDS", ex.getMessage(), MDC.get("request_id"))
        );
    }

    @ExceptionHandler(IdempotencyConflictException.class)
    public ResponseEntity<ErrorResponse> handleConflict(IdempotencyConflictException ex) {
        log.warn(ALERT, "exception.idempotency_conflict type={} message={}", ex.getClass().getSimpleName(), ex.getMessage(), ex);
        return ResponseEntity.status(HttpStatus.CONFLICT).body(
                new ErrorResponse("IDEMPOTENCY_CONFLICT", ex.getMessage(), MDC.get("request_id"))
        );
    }

    @ExceptionHandler(UnauthorizedTransferAccessException.class)
    public ResponseEntity<ErrorResponse> handleUnauthorized(UnauthorizedTransferAccessException ex) {
        log.warn("exception.forbidden type={} message={}", ex.getClass().getSimpleName(), ex.getMessage());
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
        log.warn("exception.validation type=MethodArgumentNotValidException message={}", msg);
        return ResponseEntity.badRequest().body(
                new ErrorResponse("VALIDATION_ERROR", msg, MDC.get("request_id"))
        );
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGeneric(Exception ex) {
        log.error(ALERT, "exception.internal type={} message={}", ex.getClass().getSimpleName(), ex.getMessage(), ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
                new ErrorResponse("INTERNAL_ERROR", "An unexpected error occurred", MDC.get("request_id"))
        );
    }
}