package com.pdfanalyzer.client;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pdfanalyzer.config.GeminiConfig;
import com.pdfanalyzer.dto.response.AnalysisResult;
import com.pdfanalyzer.exception.AiServiceException;
import com.pdfanalyzer.util.JsonSanitizer;
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
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
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

    // Exponential backoff base: 15s, 30s, 45s
    // Spread across the 60-second Gemini free tier RPM window
    private static final long RETRY_BASE_DELAY_MS = 15_000;

    public AnalysisResult analyze(String pdfText) {
        String prompt = buildPrompt(pdfText);
        String requestBody = buildRequestBody(prompt);

        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            try {
                log.info("Calling Gemini API, attempt {}/{}", attempt, MAX_RETRIES);
                String rawResponse = callGeminiApi(requestBody);
                return parseGeminiResponse(rawResponse);
            } catch (AiServiceException e) {
                if (attempt == MAX_RETRIES) {
                    log.error("All {} Gemini API attempts exhausted", MAX_RETRIES);
                    throw e;
                }
                long waitMs = RETRY_BASE_DELAY_MS * attempt;
                log.warn("Gemini attempt {} failed: {}. Waiting {}ms before retry...",
                        attempt, e.getMessage(), waitMs);
                sleep(waitMs);
            }
        }

        throw new AiServiceException("AI analysis failed after maximum retry attempts.");
    }

    private String callGeminiApi(String requestBody) {
        // Pass URI object (not String) to RestTemplate — bypasses URI template expansion
        // which would misinterpret curly braces in the API key as template variables.
        URI uri = UriComponentsBuilder
                .fromHttpUrl(geminiConfig.getBaseUrl()
                        + "/models/" + geminiConfig.getModel()
                        + ":generateContent")
                .queryParam("key", geminiConfig.getApiKey())
                .build(false)
                .toUri();

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));

        HttpEntity<String> entity = new HttpEntity<>(requestBody, headers);

        try {
            ResponseEntity<String> response =
                    restTemplate.postForEntity(uri, entity, String.class);

            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                throw new AiServiceException("Gemini API returned an empty or non-2xx response.");
            }

            return response.getBody();

        } catch (HttpClientErrorException e) {
            log.error("Gemini API client error: {}", e.getStatusCode());
            if (e.getStatusCode() == HttpStatus.TOO_MANY_REQUESTS) {
                throw new AiServiceException("AI service rate limit exceeded. Please try again.");
            }
            if (e.getStatusCode() == HttpStatus.UNAUTHORIZED) {
                throw new AiServiceException(
                        "AI service authentication failed. Check your GEMINI_API_KEY.");
            }
            if (e.getStatusCode() == HttpStatus.NOT_FOUND) {
                throw new AiServiceException(
                        "AI model not found. Check the configured model name.");
            }
            throw new AiServiceException("AI service request failed. Please retry.");
        } catch (HttpServerErrorException e) {
            log.error("Gemini API server error: {}", e.getStatusCode());
            throw new AiServiceException("AI service is temporarily unavailable. Please retry.");
        }
    }

    private AnalysisResult parseGeminiResponse(String rawResponse) {
        try {
            JsonNode root = objectMapper.readTree(rawResponse);
            JsonNode candidates = root.path("candidates");

            if (!candidates.isArray() || candidates.isEmpty()) {
                throw new AiServiceException("AI returned no analysis candidates.");
            }

            String textContent = candidates
                    .get(0)
                    .path("content")
                    .path("parts")
                    .get(0)
                    .path("text")
                    .asText();

            if (textContent == null || textContent.isBlank()) {
                throw new AiServiceException("AI returned empty text content.");
            }

            // responseMimeType=application/json means Gemini returns raw JSON directly.
            // JsonSanitizer still strips any accidental markdown fences as a safety net.
            String cleanJson = jsonSanitizer.extractJson(textContent);

            AnalysisResult result = objectMapper.readValue(cleanJson, AnalysisResult.class);
            validateResult(result);
            return result;

        } catch (JsonProcessingException e) {
            log.error("Failed to parse Gemini JSON response: {}", e.getMessage());
            throw new AiServiceException("AI returned a malformed response. Please retry.");
        }
    }

    private void validateResult(AnalysisResult result) {
        if (result == null) {
            throw new AiServiceException("AI returned a null analysis result.");
        }
        if (isBlank(result.getTitle()) && isBlank(result.getSummary())) {
            throw new AiServiceException("AI returned an incomplete analysis. Please retry.");
        }
    }

    private boolean isBlank(String s) {
        return s == null || s.isBlank() || s.equals("N/A") || s.equals("null");
    }

    private String buildPrompt(String pdfText) {
        return """
                You are a professional document analyst. Analyze the following document text carefully.

                Return ONLY a valid JSON object with exactly these fields and no additional text,
                commentary, or markdown fences:
                {
                  "documentType": "<type of document: Research Paper, Report, Manual, Article, etc.>",
                  "title": "<full title of the document, or 'Not Found' if absent>",
                  "authors": "<comma-separated author names, or 'Not Found' if absent>",
                  "summary": "<concise 3-5 sentence professional summary of the document content>",
                  "keyTakeaway": "<single most important insight or conclusion from the document>"
                }

                Rules:
                - Output ONLY the JSON object. No markdown. No explanation. No preamble.
                - All field values must be non-empty strings.
                - Never return null; use "Not Found" if information is absent.
                - summary must be at least 3 complete sentences.

                Document text:
                ---
                """ + pdfText + "\n---";
    }

    private String buildRequestBody(String prompt) {
        try {
            Map<String, Object> requestMap = Map.of(
                    "contents", List.of(Map.of(
                            "parts", List.of(Map.of("text", prompt))
                    )),
                    "generationConfig", Map.of(
                            "maxOutputTokens", geminiConfig.getMaxOutputTokens(),
                            "temperature", geminiConfig.getTemperature(),
                            // Instructs Gemini to return structured JSON directly,
                            // reducing the chance of markdown-wrapped responses
                            "responseMimeType", "application/json"
                    )
            );
            return objectMapper.writeValueAsString(requestMap);
        } catch (JsonProcessingException e) {
            throw new AiServiceException("Failed to build AI request payload.");
        }
    }

    private void sleep(long ms) {
        try {
            log.info("Backing off for {}ms before next Gemini retry...", ms);
            Thread.sleep(ms);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }
}