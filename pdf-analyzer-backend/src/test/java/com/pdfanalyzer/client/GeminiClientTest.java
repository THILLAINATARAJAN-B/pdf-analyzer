package com.pdfanalyzer.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pdfanalyzer.config.GeminiConfig;
import com.pdfanalyzer.dto.response.AnalysisResult;
import com.pdfanalyzer.exception.AiSafetyException;
import com.pdfanalyzer.exception.AiServiceException;
import com.pdfanalyzer.util.JsonSanitizer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("GeminiClient — AI API Integration")
class GeminiClientTest {

    @Mock private GeminiConfig geminiConfig;
    @Mock private RestTemplate restTemplate;
    @InjectMocks private GeminiClient geminiClient;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final JsonSanitizer jsonSanitizer = new JsonSanitizer();

    @BeforeEach
    void setUp() throws Exception {
        // Inject real ObjectMapper and JsonSanitizer via reflection
        var omField = GeminiClient.class.getDeclaredField("objectMapper");
        omField.setAccessible(true);
        omField.set(geminiClient, objectMapper);

        var jsField = GeminiClient.class.getDeclaredField("jsonSanitizer");
        jsField.setAccessible(true);
        jsField.set(geminiClient, jsonSanitizer);

        when(geminiConfig.getApiKey()).thenReturn("test-api-key");
        when(geminiConfig.getBaseUrl()).thenReturn("https://generativelanguage.googleapis.com/v1beta");
        when(geminiConfig.getModel()).thenReturn("gemini-1.5-flash");
        when(geminiConfig.getMaxOutputTokens()).thenReturn(1024);
        when(geminiConfig.getTemperature()).thenReturn(0.2);
    }

    private String buildGeminiSuccessResponse(String documentType, String title) {
        return """
            {
              "candidates": [
                {
                  "content": {
                    "parts": [
                      {
                        "text": "{\\"documentType\\": \\"%s\\", \\"title\\": \\"%s\\", \\"authors\\": \\"Dr. Smith\\", \\"summary\\": \\"This paper covers three key areas. First area. Second area.\\", \\"keyTakeaway\\": \\"Key insight here.\\"}"
                      }
                    ]
                  },
                  "finishReason": "STOP"
                }
              ]
            }
            """.formatted(documentType, title);
    }

    @Test
    @DisplayName("Successful Gemini response is parsed into AnalysisResult")
    void successfulResponse_parsedCorrectly() {
        String mockResponse = buildGeminiSuccessResponse("Research Paper", "Deep Learning Advances");
        when(restTemplate.postForEntity(anyString(), any(), eq(String.class)))
            .thenReturn(ResponseEntity.ok(mockResponse));

        AnalysisResult result = geminiClient.analyze("Sample PDF text", "Research Paper");

        assertThat(result).isNotNull();
        assertThat(result.getTitle()).isEqualTo("Deep Learning Advances");
        assertThat(result.getDocumentType()).isEqualTo("Research Paper");
        assertThat(result.getAuthors()).isEqualTo("Dr. Smith");
        assertThat(result.getSummary()).isNotBlank();
        assertThat(result.getKeyTakeaway()).isNotBlank();
    }

    @Test
    @DisplayName("Safety-blocked response throws AiSafetyException (finishReason=SAFETY)")
    void safetyBlockedFinishReason_throwsAiSafetyException() {
        String blockedResponse = """
            {
              "candidates": [
                {
                  "finishReason": "SAFETY",
                  "content": { "parts": [] }
                }
              ]
            }
            """;
        when(restTemplate.postForEntity(anyString(), any(), eq(String.class)))
            .thenReturn(ResponseEntity.ok(blockedResponse));

        assertThatThrownBy(() -> geminiClient.analyze("Some text", "Unknown"))
            .isInstanceOf(AiSafetyException.class)
            .hasMessageContaining("safety policy");
    }

    @Test
    @DisplayName("Safety block via promptFeedback throws AiSafetyException")
    void promptFeedbackBlock_throwsAiSafetyException() {
        String blockedResponse = """
            {
              "candidates": [],
              "promptFeedback": {
                "blockReason": "SAFETY"
              }
            }
            """;
        when(restTemplate.postForEntity(anyString(), any(), eq(String.class)))
            .thenReturn(ResponseEntity.ok(blockedResponse));

        assertThatThrownBy(() -> geminiClient.analyze("Some text", "Unknown"))
            .isInstanceOf(AiSafetyException.class);
    }

    @Test
    @DisplayName("401 Unauthorized throws AiServiceException with auth message")
    void unauthorizedResponse_throwsAiServiceExceptionWithAuthMessage() {
        when(restTemplate.postForEntity(anyString(), any(), eq(String.class)))
            .thenThrow(HttpClientErrorException.create(
                HttpStatus.UNAUTHORIZED, "Unauthorized", null, null, null));

        assertThatThrownBy(() -> geminiClient.analyze("text", "type"))
            .isInstanceOf(AiServiceException.class)
            .hasMessageContaining("authentication");
    }

    @Test
    @DisplayName("429 Rate Limit throws retryable AiServiceException")
    void rateLimitResponse_throwsAiServiceException() {
        when(restTemplate.postForEntity(anyString(), any(), eq(String.class)))
            .thenThrow(HttpClientErrorException.create(
                HttpStatus.TOO_MANY_REQUESTS, "Too Many Requests", null, null, null));

        assertThatThrownBy(() -> geminiClient.analyze("text", "type"))
            .isInstanceOf(AiServiceException.class)
            .hasMessageContaining("rate limit");
    }

    @Test
    @DisplayName("API-level error in response body throws AiServiceException")
    void apiLevelError_throwsAiServiceException() {
        String errorResponse = """
            {
              "error": {
                "code": 400,
                "message": "API key not valid.",
                "status": "INVALID_ARGUMENT"
              }
            }
            """;
        when(restTemplate.postForEntity(anyString(), any(), eq(String.class)))
            .thenReturn(ResponseEntity.ok(errorResponse));

        assertThatThrownBy(() -> geminiClient.analyze("text", "type"))
            .isInstanceOf(AiServiceException.class)
            .hasMessageContaining("API key not valid");
    }
}