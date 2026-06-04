package com.pdfanalyzer.service;

import com.pdfanalyzer.client.GeminiClient;
import com.pdfanalyzer.client.OpenAiClient;
import com.pdfanalyzer.config.AiProviderConfig;
import com.pdfanalyzer.dto.response.AnalysisResult;
import com.pdfanalyzer.exception.AiServiceException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class AiAnalysisService {

    private final AiProviderConfig aiProviderConfig;
    private final GeminiClient geminiClient;
    private final OpenAiClient openAiClient;
    private final ChunkedAiAnalysisService chunkedAiAnalysisService;

    public AnalysisResult analyze(String extractedText, String documentTypeHint) {
        if (extractedText == null || extractedText.isBlank()) {
            throw new com.pdfanalyzer.exception.PdfProcessingException(
                    "No text available to analyze. Extraction produced an empty result.");
        }

        AiProviderConfig.Provider provider = aiProviderConfig.resolvedProvider();
        log.info("AI provider mode: {}", provider);

        return chunkedAiAnalysisService.analyze(extractedText, documentTypeHint, this::analyzeSinglePass);
    }

    private AnalysisResult analyzeSinglePass(String extractedText, String documentTypeHint) {
        AiProviderConfig.Provider provider = aiProviderConfig.resolvedProvider();
        return switch (provider) {
            case OPENAI -> analyzeWithOpenAi(extractedText, documentTypeHint);
            case GEMINI -> analyzeWithGemini(extractedText, documentTypeHint);
            case AUTO -> analyzeWithAutoFallback(extractedText, documentTypeHint);
        };
    }

    private AnalysisResult analyzeWithAutoFallback(String extractedText, String documentTypeHint) {
        if (geminiClient.isConfigured()) {
            try {
                return analyzeWithGemini(extractedText, documentTypeHint);
            } catch (AiServiceException ex) {
                if (openAiClient.isConfigured() && isRetryableWithOpenAi(ex)) {
                    log.warn("Gemini failed ({}). Falling back to OpenAI.", ex.getMessage());
                    return analyzeWithOpenAi(extractedText, documentTypeHint);
                }
                throw ex;
            }
        }

        if (openAiClient.isConfigured()) {
            log.info("Gemini key not configured — using OpenAI.");
            return analyzeWithOpenAi(extractedText, documentTypeHint);
        }

        throw new AiServiceException(
                "No AI provider configured. Set GEMINI_API_KEY or GPT_API_KEY in .env or environment.");
    }

    private boolean isRetryableWithOpenAi(AiServiceException ex) {
        String message = ex.getMessage().toLowerCase();
        return message.contains("authentication")
                || message.contains("api key")
                || message.contains("forbidden")
                || message.contains("403")
                || message.contains("401");
    }

    private AnalysisResult analyzeWithGemini(String extractedText, String documentTypeHint) {
        log.info("Sending to Gemini. Document type hint: {}", documentTypeHint);
        return geminiClient.analyze(extractedText, documentTypeHint);
    }

    private AnalysisResult analyzeWithOpenAi(String extractedText, String documentTypeHint) {
        log.info("Sending to OpenAI. Document type hint: {}", documentTypeHint);
        return openAiClient.analyze(extractedText, documentTypeHint);
    }
}
