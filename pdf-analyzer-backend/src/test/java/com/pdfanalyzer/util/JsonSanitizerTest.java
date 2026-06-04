package com.pdfanalyzer.util;

import com.pdfanalyzer.exception.AiServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

@DisplayName("JsonSanitizer — Gemini Response Cleanup")
class JsonSanitizerTest {

    private JsonSanitizer jsonSanitizer;

    @BeforeEach
    void setUp() {
        jsonSanitizer = new JsonSanitizer();
    }

    private static final String CLEAN_JSON = """
        {
          "documentType": "Research Paper",
          "title": "Test Title",
          "authors": "Author One",
          "summary": "Summary here.",
          "keyTakeaway": "Key insight."
        }
        """;

    @Test
    @DisplayName("Clean JSON returned as-is")
    void cleanJson_returnedAsIs() {
        String result = jsonSanitizer.extractJson(CLEAN_JSON.trim());
        assertThat(result).contains("documentType");
        assertThat(result).contains("Research Paper");
    }

    @Test
    @DisplayName("JSON wrapped in markdown code fence is extracted")
    void markdownFence_extracted() {
        String wrapped = "```json\n" + CLEAN_JSON + "\n```";
        String result = jsonSanitizer.extractJson(wrapped);
        assertThat(result).contains("documentType");
    }

    @Test
    @DisplayName("JSON wrapped in plain code fence is extracted")
    void plainCodeFence_extracted() {
        String wrapped = "```\n" + CLEAN_JSON + "\n```";
        String result = jsonSanitizer.extractJson(wrapped);
        assertThat(result).contains("documentType");
    }

    @Test
    @DisplayName("JSON with surrounding explanation text is extracted")
    void jsonWithPreamble_extracted() {
        String messy = "Here is the analysis result:\n" + CLEAN_JSON + "\nHope this helps!";
        String result = jsonSanitizer.extractJson(messy);
        assertThat(result).contains("documentType");
    }

    @Test
    @DisplayName("Null input throws AiServiceException")
    void nullInput_throwsAiServiceException() {
        assertThatThrownBy(() -> jsonSanitizer.extractJson(null))
            .isInstanceOf(AiServiceException.class);
    }

    @Test
    @DisplayName("Blank input throws AiServiceException")
    void blankInput_throwsAiServiceException() {
        assertThatThrownBy(() -> jsonSanitizer.extractJson("   "))
            .isInstanceOf(AiServiceException.class);
    }

    @Test
    @DisplayName("Response with no JSON object throws AiServiceException")
    void noJsonObject_throwsAiServiceException() {
        assertThatThrownBy(() -> jsonSanitizer.extractJson("Sorry, I cannot analyze this."))
            .isInstanceOf(AiServiceException.class)
            .hasMessageContaining("valid JSON");
    }
}