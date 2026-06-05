package com.pdfanalyzer.client;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pdfanalyzer.config.OpenAiConfig;
import com.pdfanalyzer.dto.response.AnalysisResult;
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
 * HTTP client for the OpenAI Chat Completions API.
 *
 * - JSON Object response_format enforced at API level
 * - Exponential backoff retry (2s → 4s → 8s) for transient failures
 * - Fail-fast on authentication errors
 * - summarizeText() for large document pipeline (Level 2)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OpenAiClient {

    private final OpenAiConfig openAiConfig;
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
        log.info("OpenAI Config:");
        log.info("  Model    : {}", openAiConfig.getModel());
        log.info("  Base URL : {}", openAiConfig.getBaseUrl());
        ApiKeyDiagnostics.logKeyStatus("GPT_API_KEY", openAiConfig.getApiKey());
        log.info("=================================================");
    }

    public boolean isConfigured() {
        String key = openAiConfig.getApiKey();
        return key != null && !key.isBlank() && !key.startsWith("${");
    }

    // ── Main Analysis ─────────────────────────────────────────────────────────

    public AnalysisResult analyze(String pdfText, String documentTypeHint) {
        if (!isConfigured()) {
            throw new AiServiceException("OpenAI is not configured. Set GPT_API_KEY in environment.");
        }

        String prompt = promptBuilder.buildAnalysisPrompt(pdfText, documentTypeHint);
        String requestBody = buildRequestBody(prompt);
        AiServiceException lastException = null;

        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            try {
                log.info("OpenAI API call — attempt {}/{}", attempt, MAX_RETRIES);
                String rawResponse = callOpenAiApi(requestBody);
                return parseOpenAiResponse(rawResponse);

            } catch (AiServiceException ex) {
                lastException = ex;

                boolean isFatal = ex.getMessage().contains("authentication")
                        || ex.getMessage().contains("API key");
                if (isFatal) {
                    log.error("Fatal OpenAI error — aborting retries: {}", ex.getMessage());
                    throw ex;
                }

                if (attempt == MAX_RETRIES) break;

                long waitMs = BASE_BACKOFF_MS * (long) Math.pow(2, attempt - 1);
                log.warn("OpenAI attempt {} failed: {}. Retrying in {}ms...", attempt, ex.getMessage(), waitMs);
                RetryUtils.sleep(waitMs);
            }
        }

        throw lastException != null
                ? lastException
                : new AiServiceException("OpenAI analysis failed after all retry attempts.");
    }

    // ── Level 2: Chunk Summarization ──────────────────────────────────────────

    /** Plain-text OpenAI call — used for large document chunk summarization. */
    public String summarizeText(String prompt) {
        if (!isConfigured()) {
            throw new AiServiceException("OpenAI is not configured for chunk summarization.");
        }
        try {
            String requestBody = objectMapper.writeValueAsString(Map.of(
                    "model", openAiConfig.getModel(),
                    "messages", List.of(Map.of("role", "user", "content", prompt)),
                    "max_tokens", 512,
                    "temperature", 0.1
            ));
            JsonNode root = objectMapper.readTree(callOpenAiApi(requestBody));
            return root.path("choices").path(0).path("message").path("content").asText("").trim();
        } catch (JsonProcessingException ex) {
            throw new AiServiceException("Failed to build OpenAI summary request.");
        }
    }

    // ── HTTP Call ─────────────────────────────────────────────────────────────

    private String callOpenAiApi(String requestBody) {
        String url = openAiConfig.getBaseUrl() + "/chat/completions";

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        headers.setBearerAuth(openAiConfig.getApiKey());

        try {
            ResponseEntity<String> response = restTemplate.postForEntity(
                    url, new HttpEntity<>(requestBody, headers), String.class);
            String body = response.getBody();

            if (!response.getStatusCode().is2xxSuccessful() || body == null || body.isBlank()) {
                throw new AiServiceException("OpenAI returned an empty or non-2xx response.");
            }
            return body;

        } catch (HttpClientErrorException ex) {
            log.error("OpenAI client error — status={}, body={}", ex.getStatusCode(), ex.getResponseBodyAsString());

            if (ex.getStatusCode() == HttpStatus.TOO_MANY_REQUESTS) {
                throw new AiServiceException("OpenAI rate limit exceeded. Please wait and retry.");
            }
            if (ex.getStatusCode() == HttpStatus.UNAUTHORIZED || ex.getStatusCode() == HttpStatus.FORBIDDEN) {
                throw new AiServiceException("OpenAI authentication failed. Verify GPT_API_KEY.");
            }
            if (ex.getStatusCode() == HttpStatus.BAD_REQUEST) {
                throw new AiServiceException("OpenAI rejected the request. Document text may be too long or malformed.");
            }
            throw new AiServiceException("OpenAI request failed with status: " + ex.getStatusCode());

        } catch (HttpServerErrorException ex) {
            log.error("OpenAI server error — status={}", ex.getStatusCode());
            throw new AiServiceException("OpenAI is temporarily unavailable. Please retry.");

        } catch (AiServiceException ex) {
            throw ex;

        } catch (Exception ex) {
            log.error("Unexpected OpenAI failure [{}]: {}", ex.getClass().getSimpleName(), ex.getMessage());
            throw new AiServiceException("Unexpected error contacting OpenAI.");
        }
    }

    // ── Response Parsing ──────────────────────────────────────────────────────

    private AnalysisResult parseOpenAiResponse(String rawResponse) {
        try {
            JsonNode root = objectMapper.readTree(rawResponse);

            if (root.has("error")) {
                String msg = root.path("error").path("message").asText("Unknown error");
                log.error("OpenAI API-level error: {}", msg);
                throw new AiServiceException("OpenAI error: " + msg);
            }

            JsonNode choices = root.path("choices");
            if (!choices.isArray() || choices.isEmpty()) {
                throw new AiServiceException("OpenAI returned no analysis choices.");
            }

            String textContent = choices.get(0).path("message").path("content").asText();
            if (textContent == null || textContent.isBlank()) {
                throw new AiServiceException("OpenAI returned empty text content.");
            }

            AnalysisResult result = objectMapper.readValue(
                    jsonSanitizer.extractJson(textContent), AnalysisResult.class);
            resultValidator.validate(result, "OpenAI");
            return result;

        } catch (AiServiceException ex) {
            throw ex;
        } catch (JsonProcessingException ex) {
            log.error("JSON parse failure from OpenAI: {}", ex.getMessage());
            throw new AiServiceException("OpenAI returned a malformed JSON response. Please retry.");
        }
    }

    // ── Request Body Builder ──────────────────────────────────────────────────

    private String buildRequestBody(String prompt) {
        try {
            return objectMapper.writeValueAsString(new LinkedHashMap<>(Map.of(
                    "model", openAiConfig.getModel(),
                    "messages", List.of(Map.of("role", "user", "content", prompt)),
                    "response_format", Map.of("type", "json_object"),
                    "max_tokens", openAiConfig.getMaxOutputTokens(),
                    "temperature", openAiConfig.getTemperature()
            )));
        } catch (JsonProcessingException ex) {
            throw new AiServiceException("Failed to build OpenAI request payload.");
        }
    }
}