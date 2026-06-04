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

/**
 * HTTP client for the Gemini generative AI API.
 *
 * Features:
 * - Startup configuration verification with masked key logging
 * - Gemini Structured Output mode (responseMimeType + responseSchema)
 * - Schema-based output with all required fields enforced at API level
 * - Exponential backoff retry (2s → 4s → 8s) for transient failures
 * - Fail-fast on auth (401/403), not-found (404), and bad-request (400) errors
 * - Safety filter detection (promptFeedback.blockReason + finishReason=SAFETY)
 * - Foreign-language normalization rule in prompt
 * - documentType forced to match classification hint
 * - JsonSanitizer as secondary fallback for fence-wrapped responses
 * - summarizeChunk() + summarizeText() for chunk summarization pipeline (Level 2)
 */
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

    /**
     * Returns true if the API key is configured and not an unresolved placeholder.
     * Used by health checks or pre-flight validation.
     */
    public boolean isConfigured() {
        String key = geminiConfig.getApiKey();
        return key != null && !key.isBlank() && !key.startsWith("${");
    }

    // ── Main Analysis Entry Point ─────────────────────────────────────────────

    /**
     * Sends extracted PDF text to Gemini for structured analysis.
     * Retries up to MAX_RETRIES times with exponential backoff.
     * Fails fast on authentication, authorization, and model-not-found errors.
     */
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
                // Safety blocks are final — do not retry
                throw ex;

            } catch (AiServiceException ex) {
                lastException = ex;

                // Fatal errors that must not be retried
                boolean isFatal = ex.getMessage().contains("authentication")
                        || ex.getMessage().contains("not found")
                        || ex.getMessage().contains("API key")
                        || ex.getMessage().contains("403")
                        || ex.getMessage().contains("malformed");
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

    // ── Level 2: Chunk Summarization ──────────────────────────────────────────

    /**
     * Summarizes a single text chunk as plain prose.
     * Used by ChunkSummarizationService for large document processing.
     *
     * Deliberately NOT using structured output mode here —
     * chunk summaries are intermediate prose, not final JSON.
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

        String requestBody = buildPlainTextRequestBody(prompt);
        String rawResponse = callGeminiApi(requestBody);
        return extractTextFromResponse(rawResponse);
    }

    /**
     * Lightweight text-only call for general summarization tasks.
     * Used when callers need a plain-text Gemini response without structured output.
     */
    public String summarizeText(String prompt) {
        String requestBody = buildPlainTextRequestBody(prompt);
        String rawResponse = callGeminiApi(requestBody);
        return extractTextFromResponse(rawResponse);
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

        HttpEntity<String> entity = new HttpEntity<>(requestBody, headers);

        try {
            ResponseEntity<String> response = restTemplate.postForEntity(url, entity, String.class);
            String body = response.getBody();

            if (!response.getStatusCode().is2xxSuccessful() || body == null || body.isBlank()) {
                throw new AiServiceException(
                        "Gemini API returned an empty or non-2xx response.");
            }

            return body;

        } catch (HttpClientErrorException ex) {
            log.error("Gemini client error — status={}, body={}",
                    ex.getStatusCode(), ex.getResponseBodyAsString());

            if (ex.getStatusCode() == HttpStatus.TOO_MANY_REQUESTS) {
                throw new AiServiceException(
                        "AI service rate limit exceeded. Please wait and retry.");
            }
            if (ex.getStatusCode() == HttpStatus.UNAUTHORIZED) {
                throw new AiServiceException(
                        "AI service authentication failed. Verify GEMINI_API_KEY environment variable.");
            }
            if (ex.getStatusCode() == HttpStatus.FORBIDDEN) {
                throw new AiServiceException(
                        "AI service authentication failed (403). "
                                + "Verify GEMINI_API_KEY is valid and billing is enabled.");
            }
            if (ex.getStatusCode() == HttpStatus.NOT_FOUND) {
                throw new AiServiceException(
                        "AI model not found. Check configured model name: "
                                + geminiConfig.getModel());
            }
            if (ex.getStatusCode() == HttpStatus.BAD_REQUEST) {
                throw new AiServiceException(
                        "AI service rejected the request. "
                                + "The document text may be too long or malformed.");
            }

            throw new AiServiceException(
                    "AI service request failed with status: " + ex.getStatusCode());

        } catch (HttpServerErrorException ex) {
            log.error("Gemini server error — status={}", ex.getStatusCode());
            throw new AiServiceException(
                    "AI service is temporarily unavailable. Please retry.");

        } catch (AiServiceException ex) {
            throw ex;

        } catch (Exception ex) {
            log.error("Unexpected Gemini call failure [{}]: {}",
                    ex.getClass().getSimpleName(), ex.getMessage());
            throw new AiServiceException("Unexpected error contacting AI service.");
        }
    }

    // ── Response Parsing ──────────────────────────────────────────────────────

    private AnalysisResult parseGeminiResponse(String rawResponse) {
        try {
            JsonNode root = objectMapper.readTree(rawResponse);

            // API-level error block in response body
            if (root.has("error")) {
                String apiError = root.path("error").path("message").asText("Unknown AI error");
                log.error("Gemini API-level error: {}", apiError);
                throw new AiServiceException("AI service error: " + apiError);
            }

            JsonNode candidates = root.path("candidates");
            if (!candidates.isArray() || candidates.isEmpty()) {
                // Check promptFeedback for safety block reason
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

            // Check finishReason for in-response safety filter
            String finishReason = firstCandidate.path("finishReason").asText("");
            if ("SAFETY".equalsIgnoreCase(finishReason)) {
                log.warn("Gemini response blocked — finishReason=SAFETY");
                throw new AiSafetyException(
                        "This document could not be analyzed due to AI safety policy restrictions.");
            }

            JsonNode parts = firstCandidate.path("content").path("parts");

            if (!parts.isArray() || parts.isEmpty()) {
                throw new AiServiceException("AI response parts array is empty.");
            }

            String textContent = parts.get(0).path("text").asText();
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
            throw new AiServiceException(
                    "AI returned a malformed JSON response. Please retry.");
        }
    }

    // ── Result Validation ─────────────────────────────────────────────────────

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
        return s == null
                || s.isBlank()
                || s.equalsIgnoreCase("N/A")
                || s.equalsIgnoreCase("null")
                || s.equalsIgnoreCase("Not Found");
    }

    // ── Prompt Builder ────────────────────────────────────────────────────────

    /**
     * Builds the analysis prompt with document-type-aware instructions.
     *
     * Key rules injected:
     * 1. documentType MUST match the provided hint (fixes "General Document" regression).
     * 2. All output values MUST be in English (foreign-language normalization).
     * 3. summary requires minimum 3 sentences.
     * 4. keyTakeaway must be specific and substantive.
     */
    private String buildPrompt(String pdfText, String documentTypeHint) {
        boolean hasStrongHint = documentTypeHint != null
                && !documentTypeHint.equalsIgnoreCase("General Document")
                && !documentTypeHint.equalsIgnoreCase("Unknown Document");

        String documentTypeInstruction = hasStrongHint
                ? "- The documentType MUST be \""
                        + documentTypeHint
                        + "\" — do not override this with your own classification."
                : "- Determine the documentType from the content. Use one of: "
                        + "Research Paper, Slide Deck / Presentation, Business Report, "
                        + "Legal Document, Technical Manual, Invoice or Form, General Document.";

        return """
                You are a professional document analyst specializing in structured data extraction.

                Document type hint: %s

                Analyze the document text below and return ONLY a valid JSON object.
                No markdown. No code fences. No explanation. No preamble.

                Required JSON structure:
                {
                  "documentType": "<document type>",
                  "title": "<full title of the document>",
                  "authors": "<Author One, Author Two — or 'Not Found' if absent>",
                  "summary": "<Sentence one. Sentence two. Sentence three. Minimum three sentences.>",
                  "keyTakeaway": "<The single most important insight from this document.>"
                }

                                Strict rules:
                - Output ONLY the JSON object. Nothing before or after it.
                - ALL output values MUST be in English, regardless of the document's source language.
                  Preserve proper nouns, technical terms, and titles as-is.
                - All values must be non-empty strings.
                - Use "Not Found" only if a field genuinely cannot be determined.
                - summary must contain at least 3 complete, substantive sentences.
                - keyTakeaway must be specific to this document — not generic filler.
                - title: Extract ONLY the exact title as it appears printed in the document.
                  Do NOT paraphrase, infer, or rewrite the title under any circumstances.
                  If the title cannot be found, return "Not Found".
                - authors: If more than 5 authors are listed, return the first 3 names
                  followed by "et al." (e.g., "Mark Chen, Jerry Tworek, Heewoo Jun et al.").
                  Do NOT return "Not Found" for papers that visibly list authors.
                %s

                Document text:
                ---
                %s
                ---
                """.formatted(documentTypeHint, documentTypeInstruction, pdfText);
    }

    // ── Request Body Builders ─────────────────────────────────────────────────

    /**
     * Structured request body using Gemini's schema-based output.
     * responseMimeType + responseSchema enforces JSON at the API level.
     * JsonSanitizer in parseGeminiResponse() is the secondary fallback.
     */
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

    /**
     * Plain-text request body for chunk summarization tasks.
     * Lower token budget (512) since summaries are intermediate, not final.
     */
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

    private String buildRequestBodyWithConfig(String prompt,
                                               Map<String, Object> generationConfig)
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

    /**
     * Defines the Gemini responseSchema for structured output.
     * All 5 analysis fields are declared as required strings.
     * The API enforces this contract before returning the response.
     */
    private Map<String, Object> buildAnalysisResultSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("required",
                List.of("documentType", "title", "authors", "summary", "keyTakeaway"));

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("documentType", stringProperty());
        properties.put("title",        stringProperty());
        properties.put("authors",      stringProperty());
        properties.put("summary",      stringProperty());
        properties.put("keyTakeaway",  stringProperty());
        schema.put("properties", properties);

        return schema;
    }

    private Map<String, String> stringProperty() {
        return Map.of("type", "string");
    }

    // ── Plain Text Response Extractor ─────────────────────────────────────────

    /**
     * Extracts plain text from a Gemini response.
     * Used by summarizeChunk() and summarizeText() — not by analyze().
     */
    private String extractTextFromResponse(String rawResponse) {
        try {
            JsonNode root = objectMapper.readTree(rawResponse);
            JsonNode parts = root.path("candidates")
                                 .path(0)
                                 .path("content")
                                 .path("parts");
            if (parts.isArray() && !parts.isEmpty()) {
                String text = parts.get(0).path("text").asText("").trim();
                if (!text.isBlank()) return text;
            }
            throw new AiServiceException("AI returned no summary text.");
        } catch (JsonProcessingException ex) {
            throw new AiServiceException("Failed to parse AI summary response.");
        }
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