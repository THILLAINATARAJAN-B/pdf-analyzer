package com.pdfanalyzer.service;

import com.pdfanalyzer.client.GeminiClient;
import com.pdfanalyzer.client.OpenAiClient;
import com.pdfanalyzer.config.AiProviderConfig;
import com.pdfanalyzer.dto.response.AnalysisResult;
import com.pdfanalyzer.exception.AiServiceException;
import com.pdfanalyzer.exception.PdfProcessingException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Routes AI analysis to the configured provider (Gemini, OpenAI, or AUTO).
 *
 * AUTO mode tries Gemini first, falls back to OpenAI on auth/config failure.
 * Large document chunking is handled transparently inside GeminiClient
 * and OpenAiClient — this service does not need to know about it.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiAnalysisService {

    private final AiProviderConfig aiProviderConfig;
    private final GeminiClient geminiClient;
    private final OpenAiClient openAiClient;

    public AnalysisResult analyze(String extractedText, String documentTypeHint) {
        if (extractedText == null || extractedText.isBlank()) {
            throw new PdfProcessingException(
                    "No text available to analyze. Extraction produced an empty result.");
        }

        AiProviderConfig.Provider provider = aiProviderConfig.resolvedProvider();
        log.info("AI provider mode: {}", provider);

        return switch (provider) {
            case OPENAI -> analyzeWithOpenAi(extractedText, documentTypeHint);
            case GEMINI -> analyzeWithGemini(extractedText, documentTypeHint);
            case AUTO   -> analyzeWithAutoFallback(extractedText, documentTypeHint);
        };
    }

    private AnalysisResult analyzeWithAutoFallback(String text, String hint) {
        if (geminiClient.isConfigured()) {
            try {
                return analyzeWithGemini(text, hint);
            } catch (AiServiceException ex) {
                if (openAiClient.isConfigured() && isAuthOrConfigFailure(ex)) {
                    log.warn("Gemini failed ({}). Falling back to OpenAI.", ex.getMessage());
                    return analyzeWithOpenAi(text, hint);
                }
                throw ex;
            }
        }

        if (openAiClient.isConfigured()) {
            log.info("Gemini not configured — using OpenAI.");
            return analyzeWithOpenAi(text, hint);
        }

        throw new AiServiceException(
                "No AI provider configured. Set GEMINI_API_KEY or GPT_API_KEY.");
    }

    private boolean isAuthOrConfigFailure(AiServiceException ex) {
        String msg = ex.getMessage() == null ? "" : ex.getMessage().toLowerCase();
        return msg.contains("authentication")
                || msg.contains("api key")
                || msg.contains("forbidden")
                || msg.contains("401")
                || msg.contains("403");
    }

    private AnalysisResult analyzeWithGemini(String text, String hint) {
        log.info("Sending to Gemini. Document type hint: {}", hint);
        return geminiClient.analyze(text, hint);
    }

    private AnalysisResult analyzeWithOpenAi(String text, String hint) {
        log.info("Sending to OpenAI. Document type hint: {}", hint);
        return openAiClient.analyze(text, hint);
    }
}