package com.pdfanalyzer.exception;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.client.ResourceAccessException;

import java.net.SocketTimeoutException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    // ── 400 Bad Request ───────────────────────────────────────────────────────

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(
            MethodArgumentNotValidException ex) {
        String fieldError = ex.getBindingResult().getFieldErrors().stream()
                .map(e -> e.getField() + ": " + e.getDefaultMessage())
                .findFirst()
                .orElse("Validation failed.");
        return buildResponse(HttpStatus.BAD_REQUEST, fieldError);
    }

    // ── 405 Method Not Allowed ────────────────────────────────────────────────

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Map<String, Object>> handleMethodNotSupported(
            HttpRequestMethodNotSupportedException ex) {
        log.warn("Method not allowed: {} — supported methods: {}",
                ex.getMethod(), ex.getSupportedHttpMethods());
        return buildResponse(HttpStatus.METHOD_NOT_ALLOWED,
                "HTTP method '" + ex.getMethod()
                        + "' is not supported on this endpoint. Use POST.");
    }

    // ── 422 Unprocessable Entity ──────────────────────────────────────────────

    @ExceptionHandler(InvalidUrlException.class)
    public ResponseEntity<Map<String, Object>> handleInvalidUrl(InvalidUrlException ex) {
        log.warn("Invalid URL rejected: {}", ex.getMessage()); // ✅ Fix #1 — was silent
        return buildResponse(HttpStatus.UNPROCESSABLE_ENTITY, ex.getMessage());
    }

    @ExceptionHandler(PdfPasswordException.class)
    public ResponseEntity<Map<String, Object>> handlePdfPassword(PdfPasswordException ex) {
        log.warn("Rejected password-protected PDF: {}", ex.getMessage()); // ✅ Fix #2 — was silent
        return buildResponse(HttpStatus.UNPROCESSABLE_ENTITY, ex.getMessage());
    }

    @ExceptionHandler(PdfProcessingException.class)
    public ResponseEntity<Map<String, Object>> handlePdfProcessing(PdfProcessingException ex) {
        log.warn("PDF processing issue: {}", ex.getMessage());
        return buildResponse(HttpStatus.UNPROCESSABLE_ENTITY, ex.getMessage());
    }

    @ExceptionHandler(AiSafetyException.class)
    public ResponseEntity<Map<String, Object>> handleAiSafety(AiSafetyException ex) {
        log.warn("AI safety filter triggered: {}", ex.getMessage()); // ✅ Fix #3 — was silent
        return buildResponse(HttpStatus.UNPROCESSABLE_ENTITY, ex.getMessage());
    }

    // ── 502 / 504 Gateway Errors ──────────────────────────────────────────────

    @ExceptionHandler(PdfDownloadException.class)
    public ResponseEntity<Map<String, Object>> handlePdfDownload(PdfDownloadException ex) {
        log.warn("PDF download failed: {}", ex.getMessage()); // ✅ Fix #4 — was silent
        return buildResponse(HttpStatus.BAD_GATEWAY, ex.getMessage());
    }

    @ExceptionHandler(ResourceAccessException.class)
    public ResponseEntity<Map<String, Object>> handleNetworkTimeout(
            ResourceAccessException ex) {
        log.warn("Network/timeout error contacting external service: {}", ex.getMessage());
        boolean isTimeout = ex.getCause() instanceof SocketTimeoutException;
        String message = isTimeout
                ? "The PDF server did not respond in time. Please try again or use a faster source URL."
                : "Could not reach the PDF server. Check that the URL is accessible.";
        return buildResponse(HttpStatus.GATEWAY_TIMEOUT, message);
    }

    @ExceptionHandler(AiServiceException.class)
    public ResponseEntity<Map<String, Object>> handleAiService(AiServiceException ex) {
        log.error("AI service failure: {}", ex.getMessage());
        return buildResponse(HttpStatus.BAD_GATEWAY, ex.getMessage());
    }

    // ── 500 Internal Server Error ─────────────────────────────────────────────

    @ExceptionHandler(Error.class)
    public ResponseEntity<Map<String, Object>> handleJvmError(Error ex) {
        log.error("JVM error [{}]: {}", ex.getClass().getSimpleName(), ex.getMessage(), ex);
        String message = ex.getMessage() != null
                && ex.getMessage().toLowerCase().contains("memory")
                ? "OCR engine encountered a native failure. "
                        + "Install Tesseract OCR or use a PDF with embedded text."
                : "An unexpected system error occurred. Please try again.";
        return buildResponse(HttpStatus.INTERNAL_SERVER_ERROR, message);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleGeneric(Exception ex) {
        log.error("Unhandled exception [{}]: {}",
                ex.getClass().getSimpleName(), ex.getMessage(), ex);
        return buildResponse(HttpStatus.INTERNAL_SERVER_ERROR,
                "An unexpected error occurred. Please try again.");
    }

    // ── Builder ────────────────────────────────────────────────────────────────

    private ResponseEntity<Map<String, Object>> buildResponse(HttpStatus status, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", status.value());
        body.put("error", status.getReasonPhrase());
        body.put("message", message);
        body.put("timestamp", Instant.now().toString());
        return ResponseEntity.status(status).body(body);
    }
}