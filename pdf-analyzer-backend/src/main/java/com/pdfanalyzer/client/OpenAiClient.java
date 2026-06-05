package com.pdfanalyzer.client;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pdfanalyzer.config.OpenAiConfig;
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
public class OpenAiClient {

    private final OpenAiConfig openAiConfig;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final JsonSanitizer jsonSanitizer;

    private static final int MAX_RETRIES = 3;
    private static final long BASE_BACKOFF_MS = 2000;

    @PostConstruct
    public void verifyConfig() {
        log.info("=================================================");
        log.info("OpenAI Config:");
        log.info("  Model    : {}", openAiConfig.getModel());
        log.info("  Base URL : {}", openAiConfig.getBaseUrl());
        com.pdfanalyzer.util.ApiKeyDiagnostics.logKeyStatus("GPT_API_KEY", openAiConfig.getApiKey());
        log.info("=================================================");
    }

    public boolean isConfigured() {
        String key = openAiConfig.getApiKey();
        return key != null && !key.isBlank() && !key.startsWith("${");
    }

    public AnalysisResult analyze(String pdfText, String documentTypeHint) {
        if (!isConfigured()) {
            throw new AiServiceException("OpenAI is not configured. Set GPT_API_KEY in .env or environment.");
        }

        String prompt = buildPrompt(pdfText, documentTypeHint);
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
                sleep(waitMs);
            }
        }

        throw lastException != null
                ? lastException
                : new AiServiceException("OpenAI analysis failed after all retry attempts.");
    }

    public String summarizeText(String prompt) {
        if (!isConfigured()) {
            throw new AiServiceException("OpenAI is not configured for chunk summarization.");
        }

        try {
            Map<String, Object> message = new LinkedHashMap<>();
            message.put("role", "user");
            message.put("content", prompt);

            Map<String, Object> requestMap = new LinkedHashMap<>();
            requestMap.put("model", openAiConfig.getModel());
            requestMap.put("messages", List.of(message));
            requestMap.put("max_tokens", 512);
            requestMap.put("temperature", 0.1);

            String requestBody = objectMapper.writeValueAsString(requestMap);
            String rawResponse = callOpenAiApi(requestBody);
            JsonNode root = objectMapper.readTree(rawResponse);
            return root.path("choices").path(0).path("message").path("content").asText("").trim();
        } catch (JsonProcessingException ex) {
            throw new AiServiceException("Failed to build OpenAI summary request.");
        }
    }

    private String callOpenAiApi(String requestBody) {
        String url = openAiConfig.getBaseUrl() + "/chat/completions";

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        headers.setBearerAuth(openAiConfig.getApiKey());

        HttpEntity<String> entity = new HttpEntity<>(requestBody, headers);

        try {
            ResponseEntity<String> response = restTemplate.postForEntity(url, entity, String.class);
            String body = response.getBody();

            if (!response.getStatusCode().is2xxSuccessful() || body == null || body.isBlank()) {
                throw new AiServiceException("OpenAI API returned an empty or non-2xx response.");
            }

            return body;

        } catch (HttpClientErrorException ex) {
            log.error("OpenAI client error — status={}, body={}",
                    ex.getStatusCode(), ex.getResponseBodyAsString());

            if (ex.getStatusCode() == HttpStatus.TOO_MANY_REQUESTS) {
                throw new AiServiceException("OpenAI rate limit exceeded. Please wait and retry.");
            }
            if (ex.getStatusCode() == HttpStatus.UNAUTHORIZED || ex.getStatusCode() == HttpStatus.FORBIDDEN) {
                throw new AiServiceException(
                        "OpenAI authentication failed. Verify GPT_API_KEY in .env or environment.");
            }
            if (ex.getStatusCode() == HttpStatus.BAD_REQUEST) {
                throw new AiServiceException(
                        "OpenAI rejected the request. The document text may be too long or malformed.");
            }

            throw new AiServiceException("OpenAI request failed with status: " + ex.getStatusCode());

        } catch (HttpServerErrorException ex) {
            log.error("OpenAI server error — status={}", ex.getStatusCode());
            throw new AiServiceException("OpenAI is temporarily unavailable. Please retry.");

        } catch (AiServiceException ex) {
            throw ex;

        } catch (Exception ex) {
            log.error("Unexpected OpenAI call failure [{}]: {}", ex.getClass().getSimpleName(), ex.getMessage());
            throw new AiServiceException("Unexpected error contacting OpenAI.");
        }
    }

    private AnalysisResult parseOpenAiResponse(String rawResponse) {
        try {
            JsonNode root = objectMapper.readTree(rawResponse);

            if (root.has("error")) {
                String apiError = root.path("error").path("message").asText("Unknown OpenAI error");
                log.error("OpenAI API-level error: {}", apiError);
                throw new AiServiceException("OpenAI error: " + apiError);
            }

            JsonNode choices = root.path("choices");
            if (!choices.isArray() || choices.isEmpty()) {
                throw new AiServiceException("OpenAI returned no analysis choices.");
            }

            String textContent = choices.get(0).path("message").path("content").asText();
            if (textContent == null || textContent.isBlank()) {
                throw new AiServiceException("OpenAI returned empty text content.");
            }

            String cleanJson = jsonSanitizer.extractJson(textContent);
            AnalysisResult result = objectMapper.readValue(cleanJson, AnalysisResult.class);
            validateResult(result);
            return result;

        } catch (AiServiceException ex) {
            throw ex;
        } catch (JsonProcessingException ex) {
            log.error("JSON parse failure from OpenAI response: {}", ex.getMessage());
            throw new AiServiceException("OpenAI returned a malformed JSON response. Please retry.");
        }
    }

    private void validateResult(AnalysisResult result) {
        if (result == null) {
            throw new AiServiceException("OpenAI returned a null analysis result.");
        }
        if (isBlankOrDefault(result.getTitle()) && isBlankOrDefault(result.getSummary())) {
            throw new AiServiceException(
                    "OpenAI returned an incomplete analysis — both title and summary are missing.");
        }
    }

    private boolean isBlankOrDefault(String s) {
        return s == null || s.isBlank()
                || s.equalsIgnoreCase("N/A")
                || s.equalsIgnoreCase("null")
                || s.equalsIgnoreCase("Not Found");
    }


private String buildPrompt(String pdfText, String documentTypeHint) {
    return """
            You are a professional document analyst specializing in structured data extraction.

            STRUCTURAL PRE-CLASSIFICATION (heuristic — may be inaccurate): %s

            Analyze the document text below and return ONLY a valid JSON object.
            No markdown. No code fences. No explanation. No preamble.

            Exactly this JSON structure:
            {
              "documentType": "<determined from content — see rules below>",
              "title": "Full title of the document",
              "authors": "Author One, Author Two (or 'Not Found' if not present)",
              "summary": "Sentence one. Sentence two. Sentence three. At least three sentences.",
              "keyTakeaway": "The single most important insight from this document.",
              "qualityScore": "HIGH or MEDIUM or LOW"
            }

            DOCUMENT TYPE RULES:
            - Determine documentType from TEXT CONTENT, NOT from the structural hint above.
            - The structural hint uses page count and text density — it is often wrong.
            - Override it confidently if the content clearly indicates a different type.
            - Valid values:
                "Research Paper", "Academic Thesis", "Slide Deck / Presentation",
                "Technical Report", "Government Document", "Legal Document",
                "Financial Report", "General Document", "News Article", "Book Chapter"

            QUALITY SCORE RULES:
            - "HIGH"   — clean native text, all fields extractable with confidence
            - "MEDIUM" — OCR text or partial extraction, meaning clear but imperfect
            - "LOW"    — very short, heavily garbled, or insufficient to summarize

            STRICT RULES:
            - Output ONLY the JSON object. Nothing before or after it.
            - ALL output values MUST be in English regardless of source language.
            - All values must be non-empty strings.
            - Use "Not Found" only if a field genuinely cannot be determined.
            - summary must contain at least 3 complete sentences.
            - keyTakeaway must be specific and substantive — not generic.

            Document text:
            ---
            %s
            ---
            """.formatted(documentTypeHint, pdfText);
}

    private String buildRequestBody(String prompt) {
        try {
            Map<String, Object> responseFormat = new LinkedHashMap<>();
            responseFormat.put("type", "json_object");

            Map<String, Object> message = new LinkedHashMap<>();
            message.put("role", "user");
            message.put("content", prompt);

            Map<String, Object> requestMap = new LinkedHashMap<>();
            requestMap.put("model", openAiConfig.getModel());
            requestMap.put("messages", List.of(message));
            requestMap.put("response_format", responseFormat);
            requestMap.put("max_tokens", openAiConfig.getMaxOutputTokens());
            requestMap.put("temperature", openAiConfig.getTemperature());

            return objectMapper.writeValueAsString(requestMap);

        } catch (JsonProcessingException ex) {
            throw new AiServiceException("Failed to build OpenAI request payload.");
        }
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }
}
