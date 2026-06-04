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
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestTemplate;

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
    private static final long RETRY_DELAY_MS = 1500;

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
                log.warn("Gemini attempt {} failed: {}. Retrying...", attempt, e.getMessage());
                sleep(RETRY_DELAY_MS * attempt);
            }
        }

        throw new AiServiceException("AI analysis failed after maximum retry attempts.");
    }

    private String callGeminiApi(String requestBody) {
        String url = geminiConfig.getBaseUrl()
                + "/models/" + geminiConfig.getModel()
                + ":generateContent?key=" + geminiConfig.getApiKey();

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));

        HttpEntity<String> entity = new HttpEntity<>(requestBody, headers);

        try {
            ResponseEntity<String> response =
                    restTemplate.postForEntity(url, entity, String.class);

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
                throw new AiServiceException("AI service authentication failed.");
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
                
                Return ONLY a valid JSON object with exactly these fields and no additional text, commentary, or markdown:
                {
                  "documentType": "<type of document, e.g., Research Paper, Report, Manual, Article>",
                  "title": "<full title of the document, or 'Not Found' if absent>",
                  "authors": "<comma-separated author names, or 'Not Found' if absent>",
                  "summary": "<concise 3-5 sentence professional summary of the document content>",
                  "keyTakeaway": "<single most important insight or conclusion from the document>"
                }
                
                Rules:
                - Output ONLY the JSON. No markdown fences, no explanation.
                - All field values must be strings.
                - Never return null for any field; use "Not Found" if information is absent.
                - summary must be at least 3 sentences.
                
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
                            "temperature", geminiConfig.getTemperature()
                    )
            );
            return objectMapper.writeValueAsString(requestMap);
        } catch (JsonProcessingException e) {
            throw new AiServiceException("Failed to build AI request payload.");
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