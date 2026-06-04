package com.pdfanalyzer.exception;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.assertj.core.api.Assertions.*;

@DisplayName("GlobalExceptionHandler — HTTP Status Mapping")
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    @DisplayName("InvalidUrlException → 422")
    void invalidUrlException_returns422() {
        ResponseEntity<Map<String, Object>> response =
            handler.handleInvalidUrl(new InvalidUrlException("Bad URL"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(response.getBody()).containsEntry("message", "Bad URL");
    }

    @Test
    @DisplayName("PdfPasswordException → 422")
    void pdfPasswordException_returns422() {
        ResponseEntity<Map<String, Object>> response =
            handler.handlePdfPassword(new PdfPasswordException("Password protected"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    }

    @Test
    @DisplayName("PdfDownloadException → 502")
    void pdfDownloadException_returns502() {
        ResponseEntity<Map<String, Object>> response =
            handler.handlePdfDownload(new PdfDownloadException("Download failed"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
    }

    @Test
    @DisplayName("AiSafetyException → 422")
    void aiSafetyException_returns422() {
        ResponseEntity<Map<String, Object>> response =
            handler.handleAiSafety(new AiSafetyException("Safety block"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    }

    @Test
    @DisplayName("AiServiceException → 502")
    void aiServiceException_returns502() {
        ResponseEntity<Map<String, Object>> response =
            handler.handleAiService(new AiServiceException("AI down"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
    }

    @Test
    @DisplayName("Generic Exception → 500 with safe message")
    void genericException_returns500() {
        ResponseEntity<Map<String, Object>> response =
            handler.handleGeneric(new RuntimeException("Something crashed"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        // Must NOT expose internal stack trace to client
        assertThat(response.getBody().get("message").toString())
            .doesNotContain("Something crashed");
    }

    @Test
    @DisplayName("Response body always contains status, error, message, timestamp")
    void responseBodyContainsRequiredFields() {
        ResponseEntity<Map<String, Object>> response =
            handler.handleInvalidUrl(new InvalidUrlException("Test"));

        assertThat(response.getBody()).containsKeys("status", "error", "message", "timestamp");
    }
}