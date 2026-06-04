package com.pdfanalyzer.exception;

/**
 * Thrown when Gemini blocks a response due to safety policy.
 * Maps to HTTP 422 with a clean user-facing message.
 */
public class AiSafetyException extends RuntimeException {
    public AiSafetyException(String message) {
        super(message);
    }
}