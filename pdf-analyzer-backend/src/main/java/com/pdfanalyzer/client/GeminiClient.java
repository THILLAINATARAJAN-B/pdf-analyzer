package com.pdfanalyzer.client;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pdfanalyzer.config.GeminiConfig;
import com.pdfanalyzer.dto.response.AnalysisResult;
import com.pdfanalyzer.exception.AiSafetyException;
import com.pdfanalyzer.exception.AiServiceException;
import com.pdfanalyzer.util.AnalysisResultValidator;
import com.pdfanalyzer.util.ApiKeyDiagnostics;
import com.pdfanalyzer.util.JsonSanitizer;
import com.pdfanalyzer.util.PromptBuilder;
import com.pdfanalyzer.util.RetryUtils;
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

/**
 * HTTP client for the Gemini generative AI API.
 *
 * - Structured Output mode (responseMimeType + responseSchema) — JSON enforced at API level
 * - Exponential backoff retry (2s → 4s → 8s) for transient failures
 * - Fail-fast on auth (401/403), model-not-found (404), bad-request (400)
 * - Safety filter detection (promptFeedback.blockReason + finishReason=SAFETY)
 * - summarizeChunk() + summarizeText() for large document pipeline (Level 2)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GeminiClient {

    private final GeminiConfig geminiConfig;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final JsonSanitizer jsonSanitizer;
    private final PromptBuilder promptBuilder;
    private final AnalysisResultValidator resultValidator;

    private static final int MAX_RETRIES = 3;
    private static final long BASE_BACKOFF_MS = 2000;

    // ── Startup Verification ──────────────────────────────────────────────────

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

    // ── Main Analysis ─────────────────────────────────────────────────────────

    public AnalysisResult analyze(String pdfText, String documentTypeHint) {
        String prompt = promptBuilder.buildAnalysisPrompt(pdfText, documentTypeHint);
        String requestBody = buildStructuredRequestBody(prompt);
        AiServiceException lastException = null;

        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            try {
                log.info("Gemini API call — attempt {}/{}", attempt, MAX_RETRIES);
                String rawResponse = callGeminiApi(requestBody);
                return parseGeminiResponse(rawResponse);

            } catch (AiSafetyException ex) {
                throw ex; // safety blocks are final — never retry

            } catch (AiServiceException ex) {
                lastException = ex;

                boolean isFatal = ex.getMessage().contains("authentication")
                        || ex.getMessage().contains("not found")
                        || ex.getMessage().contains("API key")
                        || ex.getMessage().contains("403")
                        || ex.getMessage().contains("malformed");
                if (isFatal) {
                    log.error("Fatal Gemini error — aborting retries: {}", ex.getMessage());
                    throw ex;
                }

                if (attempt == MAX_RETRIES) break;

                long waitMs = BASE_BACKOFF_MS * (long) Math.pow(2, attempt - 1);
                log.warn("Gemini attempt {} failed: {}. Retrying in {}ms...", attempt, ex.getMessage(), waitMs);
                RetryUtils.sleep(waitMs);
            }
        }

        throw lastException != null
                ? lastException
                : new AiServiceException("Gemini analysis failed after all retry attempts.");
    }

    // ── Level 2: Chunk Summarization ──────────────────────────────────────────

    /**
     * Summarizes a single text chunk as plain prose.
     * Used by ChunkSummarizationService — deliberately NOT structured output.
     */
    public String summarizeChunk(String chunkText, String documentTypeHint,
                                  int chunkIndex, int totalChunks) {
        log.info("Summarizing chunk {}/{}", chunkIndex, totalChunks);

        String prompt = """
                You are summarizing part %d of %d of a %s document.

                Write a clear, factual prose summary of the content below.
                Preserve key facts, names, numbers, and findings.
                Output ONLY the summary text — no headings, no JSON, no preamble.
                ALL output must be in English regardless of source language.

                Content:
                ---
                %s
                ---
                """.formatted(chunkIndex, totalChunks, documentTypeHint, chunkText);

        return extractTextFromResponse(callGeminiApi(buildPlainTextRequestBody(prompt)));
    }

    /** Plain-text Gemini call — used for general summarization tasks. */
    public String summarizeText(String prompt) {
        return extractTextFromResponse(callGeminiApi(buildPlainTextRequestBody(prompt)));
    }

    // ── HTTP Call ─────────────────────────────────────────────────────────────

    private String callGeminiApi(String requestBody) {
        String url = geminiConfig.getBaseUrl()
                + "/models/" + geminiConfig.getModel()
                + ":generateContent"
                + "?key=" + geminiConfig.getApiKey();

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));

        try {
            ResponseEntity<String> response = restTemplate.postForEntity(
                    url, new HttpEntity<>(requestBody, headers), String.class);
            String body = response.getBody();

            if (!response.getStatusCode().is2xxSuccessful() || body == null || body.isBlank()) {
                throw new AiServiceException("Gemini returned an empty or non-2xx response.");
            }
            return body;

        } catch (HttpClientErrorException ex) {
            log.error("Gemini client error — status={}, body={}", ex.getStatusCode(), ex.getResponseBodyAsString());

            if (ex.getStatusCode() == HttpStatus.TOO_MANY_REQUESTS) {
                throw new AiServiceException("AI service rate limit exceeded. Please wait and retry.");
            }
            if (ex.getStatusCode() == HttpStatus.UNAUTHORIZED) {
                throw new AiServiceException("Gemini authentication failed. Verify GEMINI_API_KEY.");
            }
            if (ex.getStatusCode() == HttpStatus.FORBIDDEN) {
                throw new AiServiceException("Gemini authentication failed (403). Verify GEMINI_API_KEY is valid and billing is enabled.");
            }
            if (ex.getStatusCode() == HttpStatus.NOT_FOUND) {
                throw new AiServiceException("Gemini model not found: " + geminiConfig.getModel());
            }
            if (ex.getStatusCode() == HttpStatus.BAD_REQUEST) {
                throw new AiServiceException("Gemini rejected the request. Document text may be too long or malformed.");
            }
            throw new AiServiceException("Gemini request failed with status: " + ex.getStatusCode());

        } catch (HttpServerErrorException ex) {
            log.error("Gemini server error — status={}", ex.getStatusCode());
            throw new AiServiceException("Gemini is temporarily unavailable. Please retry.");

        } catch (AiServiceException ex) {
            throw ex;

        } catch (Exception ex) {
            log.error("Unexpected Gemini failure [{}]: {}", ex.getClass().getSimpleName(), ex.getMessage());
            throw new AiServiceException("Unexpected error contacting Gemini.");
        }
    }

    // ── Response Parsing ──────────────────────────────────────────────────────

    private AnalysisResult parseGeminiResponse(String rawResponse) {
        try {
            JsonNode root = objectMapper.readTree(rawResponse);

            if (root.has("error")) {
                String msg = root.path("error").path("message").asText("Unknown error");
                log.error("Gemini API-level error: {}", msg);
                throw new AiServiceException("Gemini error: " + msg);
            }

            JsonNode candidates = root.path("candidates");
            if (!candidates.isArray() || candidates.isEmpty()) {
                JsonNode feedback = root.path("promptFeedback");
                if (!feedback.isMissingNode()) {
                    String blockReason = feedback.path("blockReason").asText("");
                    if (!blockReason.isBlank()) {
                        log.warn("Gemini safety block — reason: {}", blockReason);
                        throw new AiSafetyException(
                                "This document could not be analyzed due to AI safety policy restrictions.");
                    }
                }
                log.error("Gemini response has no candidates. Raw: {}", rawResponse);
                throw new AiServiceException("Gemini returned no analysis candidates.");
            }

            JsonNode firstCandidate = candidates.get(0);
            if ("SAFETY".equalsIgnoreCase(firstCandidate.path("finishReason").asText(""))) {
                log.warn("Gemini response blocked — finishReason=SAFETY");
                throw new AiSafetyException(
                        "This document could not be analyzed due to AI safety policy restrictions.");
            }

            JsonNode parts = firstCandidate.path("content").path("parts");
            if (!parts.isArray() || parts.isEmpty()) {
                throw new AiServiceException("Gemini response parts array is empty.");
            }

            String textContent = parts.get(0).path("text").asText();
            if (textContent == null || textContent.isBlank()) {
                throw new AiServiceException("Gemini returned empty text content.");
            }

            AnalysisResult result = objectMapper.readValue(
                    jsonSanitizer.extractJson(textContent), AnalysisResult.class);
            resultValidator.validate(result, "Gemini");
            return result;

        } catch (AiSafetyException | AiServiceException ex) {
            throw ex;
        } catch (JsonProcessingException ex) {
            log.error("JSON parse failure from Gemini: {}", ex.getMessage());
            throw new AiServiceException("Gemini returned a malformed JSON response. Please retry.");
        }
    }

    // ── Request Body Builders ─────────────────────────────────────────────────

    /** Structured output request — JSON enforced at API level via responseSchema. */
    private String buildStructuredRequestBody(String prompt) {
        try {
            Map<String, Object> generationConfig = new LinkedHashMap<>();
            generationConfig.put("maxOutputTokens", geminiConfig.getMaxOutputTokens());
            generationConfig.put("temperature", geminiConfig.getTemperature());
            generationConfig.put("responseMimeType", "application/json");
            generationConfig.put("responseSchema", buildAnalysisResultSchema());
            return buildRequestBodyWithConfig(prompt, generationConfig);
        } catch (JsonProcessingException ex) {
            throw new AiServiceException("Failed to build Gemini request payload.");
        }
    }

    /** Plain-text request — used for chunk summarization (lower token budget). */
    private String buildPlainTextRequestBody(String prompt) {
        try {
            Map<String, Object> generationConfig = new LinkedHashMap<>();
            generationConfig.put("maxOutputTokens", 512);
            generationConfig.put("temperature", 0.1);
            return buildRequestBodyWithConfig(prompt, generationConfig);
        } catch (JsonProcessingException ex) {
            throw new AiServiceException("Failed to build Gemini summary request payload.");
        }
    }

    private String buildRequestBodyWithConfig(String prompt, Map<String, Object> generationConfig)
            throws JsonProcessingException {
        return objectMapper.writeValueAsString(Map.of(
                "contents", List.of(Map.of("parts", List.of(Map.of("text", prompt)))),
                "generationConfig", generationConfig
        ));
    }

    /**
     * Gemini responseSchema — all 6 analysis fields declared as required strings.
     * API enforces this contract before returning the response.
     */
    private Map<String, Object> buildAnalysisResultSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        for (String field : List.of("documentType", "title", "authors", "summary", "keyTakeaway", "qualityScore")) {
            properties.put(field, Map.of("type", "string"));
        }
        return Map.of(
                "type", "object",
                "required", List.of("documentType", "title", "authors", "summary", "keyTakeaway", "qualityScore"),
                "properties", properties
        );
    }

    // ── Plain Text Response Extractor ─────────────────────────────────────────

    private String extractTextFromResponse(String rawResponse) {
        try {
            JsonNode parts = objectMapper.readTree(rawResponse)
                    .path("candidates").path(0).path("content").path("parts");
            if (parts.isArray() && !parts.isEmpty()) {
                String text = parts.get(0).path("text").asText("").trim();
                if (!text.isBlank()) return text;
            }
            throw new AiServiceException("Gemini returned no summary text.");
        } catch (JsonProcessingException ex) {
            throw new AiServiceException("Failed to parse Gemini summary response.");
        }
    }
}