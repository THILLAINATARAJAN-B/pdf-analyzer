package com.pdfanalyzer.client;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pdfanalyzer.config.GeminiConfig;
import com.pdfanalyzer.dto.response.AnalysisResult;
import com.pdfanalyzer.exception.AiServiceException;
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
    private static final long RETRY_BASE_DELAY_MS = 20_000;

    // ─── Startup Verification ────────────────────────────────────────────────
    @PostConstruct
    public void verifyConfig() {
        String key = geminiConfig.getApiKey();
        boolean unresolvedPlaceholder = key != null && key.startsWith("${");
        String maskedKey = (key != null && !key.isBlank() && !unresolvedPlaceholder)
                ? key.substring(0, Math.min(10, key.length())) + "..."
                : "MISSING ← FIX THIS";

        log.info("=================================================");
        log.info("Gemini Config Loaded:");
        log.info("  Model    : {}", geminiConfig.getModel());
        log.info("  Base URL : {}", geminiConfig.getBaseUrl());
        log.info("  API Key  : {}", maskedKey);
        if (unresolvedPlaceholder) {
            log.warn("API key is an unresolved placeholder. Set GEMINI_API_KEY in .env");
        }
        log.info("=================================================");
    }

    // ─── Main Entry Point ────────────────────────────────────────────────────
    public AnalysisResult analyze(String pdfText) {
        String requestBody = buildRequestBody(buildPrompt(pdfText));
        AiServiceException lastException = null;

        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            try {
                log.info("Calling Gemini API, attempt {}/{}", attempt, MAX_RETRIES);
                String rawResponse = callGeminiApi(requestBody);
                return parseGeminiResponse(rawResponse);
            } catch (AiServiceException e) {
                lastException = e;
                if (attempt == MAX_RETRIES) {
                    log.error("All {} Gemini API attempts exhausted", MAX_RETRIES);
                    break;
                }
                long waitMs = RETRY_BASE_DELAY_MS * attempt;
                log.warn("Gemini attempt {} failed: {}. Waiting {}ms before retry...",
                        attempt, e.getMessage(), waitMs);
                sleep(waitMs);
            }
        }

        throw lastException != null
                ? lastException
                : new AiServiceException("AI analysis failed after maximum retry attempts.");
    }

    // ─── HTTP Call ───────────────────────────────────────────────────────────
    private String callGeminiApi(String requestBody) {
        String url = geminiConfig.getBaseUrl()
                + "/models/" + geminiConfig.getModel()
                + ":generateContent"
                + "?key=" + geminiConfig.getApiKey();

        log.info("Calling Gemini: {}/models/{}:generateContent",
                geminiConfig.getBaseUrl(), geminiConfig.getModel());

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

        } catch (HttpClientErrorException e) {
            log.error("Gemini client error. Status: {}, Body: {}",
                    e.getStatusCode(), e.getResponseBodyAsString());

            if (e.getStatusCode() == HttpStatus.TOO_MANY_REQUESTS) {
                throw new AiServiceException("AI service rate limit exceeded. Please wait and retry.");
            }
            if (e.getStatusCode() == HttpStatus.UNAUTHORIZED) {
                throw new AiServiceException("AI service authentication failed. Verify GEMINI_API_KEY.");
            }
            if (e.getStatusCode() == HttpStatus.NOT_FOUND) {
                throw new AiServiceException("AI model not found. Check configured Gemini model.");
            }
            if (e.getStatusCode() == HttpStatus.BAD_REQUEST) {
                throw new AiServiceException("AI service rejected the request. Check payload or model configuration.");
            }

            throw new AiServiceException("AI service request failed: " + e.getStatusCode());

        } catch (HttpServerErrorException e) {
            log.error("Gemini server error. Status: {}, Body: {}",
                    e.getStatusCode(), e.getResponseBodyAsString());
            throw new AiServiceException("AI service is temporarily unavailable. Please retry.");

        } catch (Exception e) {
            log.error("Unexpected Gemini call failure [{}]: {}", e.getClass().getSimpleName(), e.getMessage());
            throw new AiServiceException("Unexpected error contacting AI service. Please retry.");
        }
    }

    // ─── Response Parsing ────────────────────────────────────────────────────
    private AnalysisResult parseGeminiResponse(String rawResponse) {
        try {
            JsonNode root = objectMapper.readTree(rawResponse);

            if (root.has("error")) {
                String apiError = root.path("error").path("message").asText("Unknown AI error");
                log.error("Gemini API-level error: {}", apiError);
                throw new AiServiceException("AI service error: " + apiError);
            }

            JsonNode candidates = root.path("candidates");
            if (!candidates.isArray() || candidates.isEmpty()) {
                log.error("Gemini response has no candidates. Full: {}", rawResponse);
                throw new AiServiceException("AI returned no analysis candidates.");
            }

            String textContent = candidates.get(0)
                    .path("content")
                    .path("parts")
                    .get(0)
                    .path("text")
                    .asText();

            if (textContent == null || textContent.isBlank()) {
                log.error("Gemini text content empty. Full response: {}", rawResponse);
                throw new AiServiceException("AI returned empty text content.");
            }

            String cleanJson = jsonSanitizer.extractJson(textContent);
            AnalysisResult result = objectMapper.readValue(cleanJson, AnalysisResult.class);
            validateResult(result);
            return result;

        } catch (AiServiceException e) {
            throw e;
        } catch (JsonProcessingException e) {
            log.error("JSON parse failure: {}", e.getMessage());
            throw new AiServiceException("AI returned a malformed response. Please retry.");
        }
    }

    // ─── Validation ──────────────────────────────────────────────────────────
    private void validateResult(AnalysisResult result) {
        if (result == null) {
            throw new AiServiceException("AI returned a null analysis result.");
        }
        if (isBlank(result.getTitle()) && isBlank(result.getSummary())) {
            throw new AiServiceException("AI returned an incomplete analysis. Please retry.");
        }
    }

    private boolean isBlank(String s) {
        return s == null || s.isBlank()
                || s.equalsIgnoreCase("N/A")
                || s.equalsIgnoreCase("null");
    }

    // ─── Prompt Builder ──────────────────────────────────────────────────────
    private String buildPrompt(String pdfText) {
        return """
                You are a professional document analyst. Analyze the document text below.

                Return ONLY a valid JSON object. No markdown. No code fences. No explanation.
                Exactly this structure:
                {
                  "documentType": "Research Paper",
                  "title": "Full title here",
                  "authors": "Author One, Author Two",
                  "summary": "Sentence one. Sentence two. Sentence three.",
                  "keyTakeaway": "The single most important insight."
                }

                Rules:
                - Output ONLY the JSON object. Nothing before or after it.
                - All values must be non-empty strings.
                - Use "Not Found" if a field cannot be determined.
                - summary must contain at least 3 sentences.

                Document text:
                ---
                """ + pdfText + "\n---";
    }

    // ─── Request Body Builder ────────────────────────────────────────────────
    private String buildRequestBody(String prompt) {
        try {
            Map<String, Object> generationConfig = new LinkedHashMap<>();
            generationConfig.put("maxOutputTokens", geminiConfig.getMaxOutputTokens());
            generationConfig.put("temperature", geminiConfig.getTemperature());

            Map<String, Object> part = new LinkedHashMap<>();
            part.put("text", prompt);

            Map<String, Object> content = new LinkedHashMap<>();
            content.put("parts", List.of(part));

            Map<String, Object> requestMap = new LinkedHashMap<>();
            requestMap.put("contents", List.of(content));
            requestMap.put("generationConfig", generationConfig);

            return objectMapper.writeValueAsString(requestMap);

        } catch (JsonProcessingException e) {
            throw new AiServiceException("Failed to build AI request payload.");
        }
    }

    // ─── Sleep Utility ───────────────────────────────────────────────────────
    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }
}