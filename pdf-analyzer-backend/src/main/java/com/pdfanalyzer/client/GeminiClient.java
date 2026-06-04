package com.pdfanalyzer.client;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pdfanalyzer.config.GeminiConfig;
import com.pdfanalyzer.dto.response.AnalysisResult;
import com.pdfanalyzer.exception.AiSafetyException;
import com.pdfanalyzer.exception.AiServiceException;
import com.pdfanalyzer.util.ApiKeyDiagnostics;
import com.pdfanalyzer.util.JsonSanitizer;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestTemplate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class GeminiClient {

    private final GeminiConfig geminiConfig;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final JsonSanitizer jsonSanitizer;

    private static final int MAX_RETRIES = 3;
    private static final long BASE_BACKOFF_MS = 2000;

    // ── Startup Verification ─────────────────────────────────────────────────

    @PostConstruct
    public void verifyConfig() {
        log.info("=================================================");
        log.info("Gemini Config:");
        log.info("  Model    : {}", geminiConfig.getModel());
        log.info("  Base URL : {}", geminiConfig.getBaseUrl());
        ApiKeyDiagnostics.logKeyStatus("GEMINI_API_KEY", geminiConfig.getApiKey());
        log.info("=================================================");
    }

    public boolean isConfigured() {
        String key = geminiConfig.getApiKey();
        return key != null && !key.isBlank() && !key.startsWith("${");
    }

    // ── Main Entry Point ─────────────────────────────────────────────────────

    public AnalysisResult analyze(String pdfText, String documentTypeHint) {
        String prompt = buildPrompt(pdfText, documentTypeHint);
        String requestBody = buildStructuredRequestBody(prompt);
        AiServiceException lastException = null;

        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            try {
                log.info("Gemini API call — attempt {}/{}", attempt, MAX_RETRIES);
                String rawResponse = callGeminiApi(requestBody);
                return parseGeminiResponse(rawResponse);

            } catch (AiSafetyException ex) {
                // Safety blocks are non-retryable — surface immediately
                throw ex;

            } catch (AiServiceException ex) {
                lastException = ex;

                // Auth errors and Not Found are not retryable
                boolean isFatal = ex.getMessage().contains("authentication")
                        || ex.getMessage().contains("not found")
                        || ex.getMessage().contains("API key");
                if (isFatal) {
                    log.error("Fatal AI service error — aborting retries: {}", ex.getMessage());
                    throw ex;
                }

                if (attempt == MAX_RETRIES) break;

                // Exponential backoff: 2s, 4s, 8s
                long waitMs = BASE_BACKOFF_MS * (long) Math.pow(2, attempt - 1);
                log.warn("Gemini attempt {} failed: {}. Retrying in {}ms...",
                        attempt, ex.getMessage(), waitMs);
                sleep(waitMs);
            }
        }

        throw lastException != null
                ? lastException
                : new AiServiceException("AI analysis failed after all retry attempts.");
    }

    /**
     * Lightweight text-only call used for chunk summarization before final structured analysis.
     */
    public String summarizeText(String prompt) {
        String requestBody = buildPlainTextRequestBody(prompt);
        String rawResponse = callGeminiApi(requestBody);
        return extractTextFromResponse(rawResponse);
    }

    // ── HTTP Call ────────────────────────────────────────────────────────────

    private String callGeminiApi(String requestBody) {
        String url = geminiConfig.getBaseUrl()
                + "/models/" + geminiConfig.getModel()
                + ":generateContent"
                + "?key=" + geminiConfig.getApiKey();

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));

        HttpEntity<String> entity = new HttpEntity<>(requestBody, headers);

        try {
            ResponseEntity<String> response = restTemplate.postForEntity(url, entity, String.class);
            String body = response.getBody();

            if (!response.getStatusCode().is2xxSuccessful() || body == null || body.isBlank()) {
                throw new AiServiceException("Gemini API returned an empty or non-2xx response.");
            }

            return body;

        } catch (HttpClientErrorException ex) {
            log.error("Gemini client error — status={}, body={}",
                    ex.getStatusCode(), ex.getResponseBodyAsString());

            if (ex.getStatusCode() == HttpStatus.TOO_MANY_REQUESTS) {
                throw new AiServiceException("AI service rate limit exceeded. Please wait and retry.");
            }
            if (ex.getStatusCode() == HttpStatus.UNAUTHORIZED) {
                throw new AiServiceException(
                        "AI service authentication failed. Verify GEMINI_API_KEY environment variable.");
            }
            if (ex.getStatusCode() == HttpStatus.FORBIDDEN) {
                throw new AiServiceException(
                        "AI service authentication failed (403). Verify GEMINI_API_KEY is valid.");
            }
            if (ex.getStatusCode() == HttpStatus.NOT_FOUND) {
                throw new AiServiceException(
                        "AI model not found. Check configured model name: " + geminiConfig.getModel());
            }
            if (ex.getStatusCode() == HttpStatus.BAD_REQUEST) {
                throw new AiServiceException(
                        "AI service rejected the request. The document text may be too long or malformed.");
            }

            throw new AiServiceException("AI service request failed with status: " + ex.getStatusCode());

        } catch (HttpServerErrorException ex) {
            log.error("Gemini server error — status={}", ex.getStatusCode());
            throw new AiServiceException("AI service is temporarily unavailable. Please retry.");

        } catch (AiServiceException ex) {
            throw ex;

        } catch (Exception ex) {
            log.error("Unexpected Gemini call failure [{}]: {}", ex.getClass().getSimpleName(), ex.getMessage());
            throw new AiServiceException("Unexpected error contacting AI service.");
        }
    }

    // ── Response Parsing ─────────────────────────────────────────────────────

    private AnalysisResult parseGeminiResponse(String rawResponse) {
        try {
            JsonNode root = objectMapper.readTree(rawResponse);

            // API-level error in response body
            if (root.has("error")) {
                String apiError = root.path("error").path("message").asText("Unknown AI error");
                log.error("Gemini API-level error: {}", apiError);
                throw new AiServiceException("AI service error: " + apiError);
            }

            JsonNode candidates = root.path("candidates");
            if (!candidates.isArray() || candidates.isEmpty()) {
                // Check for promptFeedback indicating safety block
                JsonNode promptFeedback = root.path("promptFeedback");
                if (!promptFeedback.isMissingNode()) {
                    String blockReason = promptFeedback.path("blockReason").asText("");
                    if (!blockReason.isBlank()) {
                        log.warn("Gemini safety block — reason: {}", blockReason);
                        throw new AiSafetyException(
                                "This document could not be analyzed due to AI safety policy restrictions.");
                    }
                }
                log.error("Gemini response has no candidates. Raw: {}", rawResponse);
                throw new AiServiceException("AI returned no analysis candidates.");
            }

            JsonNode firstCandidate = candidates.get(0);

            // Check finish reason for safety filter
            String finishReason = firstCandidate.path("finishReason").asText("");
            if ("SAFETY".equalsIgnoreCase(finishReason)) {
                log.warn("Gemini response blocked by safety filters — finishReason=SAFETY");
                throw new AiSafetyException(
                        "This document could not be analyzed due to AI safety policy restrictions.");
            }

            JsonNode textNode = firstCandidate
                    .path("content")
                    .path("parts");

            if (!textNode.isArray() || textNode.isEmpty()) {
                throw new AiServiceException("AI response parts array is empty.");
            }

            String textContent = textNode.get(0).path("text").asText();
            if (textContent == null || textContent.isBlank()) {
                throw new AiServiceException("AI returned empty text content.");
            }

            String cleanJson = jsonSanitizer.extractJson(textContent);
            AnalysisResult result = objectMapper.readValue(cleanJson, AnalysisResult.class);
            validateResult(result);
            return result;

        } catch (AiSafetyException | AiServiceException ex) {
            throw ex;
        } catch (JsonProcessingException ex) {
            log.error("JSON parse failure from Gemini response: {}", ex.getMessage());
            throw new AiServiceException("AI returned a malformed JSON response. Please retry.");
        }
    }

    // ── Result Validation ────────────────────────────────────────────────────

    private void validateResult(AnalysisResult result) {
        if (result == null) {
            throw new AiServiceException("AI returned a null analysis result.");
        }
        if (isBlankOrDefault(result.getTitle()) && isBlankOrDefault(result.getSummary())) {
            throw new AiServiceException(
                    "AI returned an incomplete analysis — both title and summary are missing.");
        }
    }

    private boolean isBlankOrDefault(String s) {
        return s == null || s.isBlank()
                || s.equalsIgnoreCase("N/A")
                || s.equalsIgnoreCase("null")
                || s.equalsIgnoreCase("Not Found");
    }

    // ── Prompt Builder ────────────────────────────────────────────────────────

    private String buildPrompt(String pdfText, String documentTypeHint) {
        return """
                You are a professional document analyst specializing in structured data extraction.
                
                Document type hint (use to guide your analysis): %s
                
                Analyze the document text below and return ONLY a valid JSON object.
                No markdown. No code fences. No explanation. No preamble.
                
                Exactly this JSON structure:
                {
                  "documentType": "%s",
                  "title": "Full title of the document",
                  "authors": "Author One, Author Two (or 'Not Found' if not present)",
                  "summary": "Sentence one. Sentence two. Sentence three. At least three sentences.",
                  "keyTakeaway": "The single most important insight from this document."
                }
                
                Strict rules:
                - Output ONLY the JSON object. Nothing before or after it.
                - ALL output values MUST be in English, regardless of the document's source language.
                  Preserve proper names, titles, and technical terms as-is.
                - All values must be non-empty strings.
                - Use "Not Found" only if a field genuinely cannot be determined.
                - summary must contain at least 3 complete sentences.
                - keyTakeaway must be specific and substantive — not generic.
                
                Document text:
                ---
                %s
                ---
                """.formatted(documentTypeHint, documentTypeHint, pdfText);
    }

    // ── Request Body Builder ──────────────────────────────────────────────────

    private String buildStructuredRequestBody(String prompt) {
        try {
            Map<String, Object> generationConfig = new LinkedHashMap<>();
            generationConfig.put("maxOutputTokens", geminiConfig.getMaxOutputTokens());
            generationConfig.put("temperature", geminiConfig.getTemperature());
            generationConfig.put("responseMimeType", "application/json");
            generationConfig.put("responseSchema", buildAnalysisResultSchema());

            return buildRequestBodyWithConfig(prompt, generationConfig);

        } catch (JsonProcessingException ex) {
            throw new AiServiceException("Failed to build AI request payload.");
        }
    }

    private String buildPlainTextRequestBody(String prompt) {
        try {
            Map<String, Object> generationConfig = new LinkedHashMap<>();
            generationConfig.put("maxOutputTokens", 512);
            generationConfig.put("temperature", 0.1);
            return buildRequestBodyWithConfig(prompt, generationConfig);
        } catch (JsonProcessingException ex) {
            throw new AiServiceException("Failed to build AI summary request payload.");
        }
    }

    private String buildRequestBodyWithConfig(String prompt, Map<String, Object> generationConfig)
            throws JsonProcessingException {
        Map<String, Object> part = new LinkedHashMap<>();
        part.put("text", prompt);

        Map<String, Object> content = new LinkedHashMap<>();
        content.put("parts", List.of(part));

        Map<String, Object> requestMap = new LinkedHashMap<>();
        requestMap.put("contents", List.of(content));
        requestMap.put("generationConfig", generationConfig);

        return objectMapper.writeValueAsString(requestMap);
    }

    private Map<String, Object> buildAnalysisResultSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("required", List.of("documentType", "title", "authors", "summary", "keyTakeaway"));

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("documentType", stringProperty());
        properties.put("title", stringProperty());
        properties.put("authors", stringProperty());
        properties.put("summary", stringProperty());
        properties.put("keyTakeaway", stringProperty());
        schema.put("properties", properties);
        return schema;
    }

    private Map<String, String> stringProperty() {
        return Map.of("type", "string");
    }

    private String extractTextFromResponse(String rawResponse) {
        try {
            JsonNode root = objectMapper.readTree(rawResponse);
            JsonNode textNode = root.path("candidates").path(0).path("content").path("parts");
            if (textNode.isArray() && !textNode.isEmpty()) {
                return textNode.get(0).path("text").asText("").trim();
            }
            throw new AiServiceException("AI returned no summary text.");
        } catch (JsonProcessingException ex) {
            throw new AiServiceException("Failed to parse AI summary response.");
        }
    }

    // ── Request Body Builder (legacy name kept for compatibility) ─────────────

    private String buildRequestBody(String prompt) {
        return buildStructuredRequestBody(prompt);
    }

    // ── Sleep Utility ─────────────────────────────────────────────────────────

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }
}