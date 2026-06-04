package com.pdfanalyzer.exception;

/**
 * Thrown when a PDF is encrypted / password-protected.
 * Maps to HTTP 422 Unprocessable Entity.
 */
public class PdfPasswordException extends RuntimeException {
    public PdfPasswordException(String message) {
        super(message);
    }
}