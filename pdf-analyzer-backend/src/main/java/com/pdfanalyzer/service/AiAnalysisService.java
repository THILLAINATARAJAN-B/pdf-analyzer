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

        // Build enriched hint — AI gets structural suggestion but overrides from content
        String enrichedHint = buildEnrichedHint(documentTypeHint);

        return switch (provider) {
            case OPENAI -> analyzeWithOpenAi(extractedText, enrichedHint);
            case GEMINI -> analyzeWithGemini(extractedText, enrichedHint);
            case AUTO   -> analyzeWithAutoFallback(extractedText, enrichedHint);
        };
    }

    /**
     * Converts heuristic label into a soft suggestion so the AI
     * can override it based on actual text content.
     *
     * Example: "SLIDE_DECK" → hint string that AI can reject
     * if the content is clearly a library report, not slides.
     */
    private String buildEnrichedHint(String heuristicHint) {
        if (heuristicHint == null || heuristicHint.isBlank()) {
            return "UNKNOWN — determine type from content";
        }
        // Pass heuristic as a suggestion only — prompt instructs AI to override if wrong
        return heuristicHint + " (structural heuristic — override from content if incorrect)";
    }

    private AnalysisResult analyzeWithAutoFallback(String text, String hint) {
        if (geminiClient.isConfigured()) {
            try {
                return analyzeWithGemini(text, hint);
            } catch (AiServiceException ex) {
                if (openAiClient.isConfigured()) {
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

    private AnalysisResult analyzeWithGemini(String text, String hint) {
        log.info("Sending to Gemini. Document type hint: {}", hint);
        return geminiClient.analyze(text, hint);
    }

    private AnalysisResult analyzeWithOpenAi(String text, String hint) {
        log.info("Sending to OpenAI. Document type hint: {}", hint);
        return openAiClient.analyze(text, hint);
    }
}