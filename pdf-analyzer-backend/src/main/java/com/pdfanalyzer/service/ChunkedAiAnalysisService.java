package com.pdfanalyzer.service;

import com.pdfanalyzer.client.GeminiClient;
import com.pdfanalyzer.client.OpenAiClient;
import com.pdfanalyzer.config.AiProviderConfig;
import com.pdfanalyzer.dto.response.AnalysisResult;
import com.pdfanalyzer.exception.AiServiceException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;

/**
 * Handles large documents by summarizing page-range chunks first,
 * then running structured analysis on the combined intermediate summaries.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChunkedAiAnalysisService {

    private final AiProviderConfig aiProviderConfig;
    private final GeminiClient geminiClient;
    private final OpenAiClient openAiClient;

    @Value("${ai.analysis.chunk-threshold-chars:12000}")
    private int chunkThresholdChars;

    @Value("${ai.analysis.chunk-size-chars:8000}")
    private int chunkSizeChars;

    @Value("${ai.analysis.max-chunks:8}")
    private int maxChunks;

    public AnalysisResult analyze(String extractedText, String documentTypeHint,
                                  BiFunction<String, String, AnalysisResult> singlePassAnalyzer) {
        if (extractedText.length() <= chunkThresholdChars) {
            return singlePassAnalyzer.apply(extractedText, documentTypeHint);
        }

        List<String> chunks = splitIntoChunks(extractedText);
        log.info("Document exceeds {} chars — chunking into {} sections for staged analysis",
                chunkThresholdChars, chunks.size());

        List<String> sectionSummaries = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            String summary = summarizeSection(chunks.get(i), documentTypeHint, i + 1, chunks.size());
            sectionSummaries.add("Section " + (i + 1) + ":\n" + summary);
        }

        String combined = String.join("\n\n", sectionSummaries);
        log.info("Chunk summarization complete — {} chars → {} chars for final analysis",
                extractedText.length(), combined.length());

        return singlePassAnalyzer.apply(combined, documentTypeHint);
    }

    private List<String> splitIntoChunks(String text) {
        List<String> chunks = new ArrayList<>();
        int start = 0;
        while (start < text.length() && chunks.size() < maxChunks) {
            int end = Math.min(start + chunkSizeChars, text.length());
            if (end < text.length()) {
                int breakAt = text.lastIndexOf('\n', end);
                if (breakAt > start + chunkSizeChars / 2) {
                    end = breakAt;
                }
            }
            chunks.add(text.substring(start, end).trim());
            start = end;
        }

        if (start < text.length() && chunks.size() == maxChunks) {
            String last = chunks.get(chunks.size() - 1);
            chunks.set(chunks.size() - 1,
                    last + "\n\n[Additional content truncated for token budget]");
        }

        return chunks;
    }

    private String summarizeSection(String sectionText, String documentTypeHint,
                                    int sectionNumber, int totalSections) {
        String prompt = """
                You are summarizing section %d of %d from a %s document.
                Write a concise factual summary (4-6 sentences) capturing key points from this section only.
                Output plain text only — no JSON, no markdown.

                Section text:
                ---
                %s
                ---
                """.formatted(sectionNumber, totalSections, documentTypeHint, sectionText);

        return switch (aiProviderConfig.resolvedProvider()) {
            case OPENAI -> openAiClient.summarizeText(prompt);
            case GEMINI -> geminiClient.summarizeText(prompt);
            case AUTO -> summarizeWithAutoFallback(prompt);
        };
    }

    private String summarizeWithAutoFallback(String prompt) {
        if (geminiClient.isConfigured()) {
            try {
                return geminiClient.summarizeText(prompt);
            } catch (AiServiceException ex) {
                if (openAiClient.isConfigured()) {
                    log.warn("Gemini chunk summary failed, using OpenAI: {}", ex.getMessage());
                    return openAiClient.summarizeText(prompt);
                }
                throw ex;
            }
        }
        if (openAiClient.isConfigured()) {
            return openAiClient.summarizeText(prompt);
        }
        throw new AiServiceException("No AI provider configured for chunked analysis.");
    }
}
