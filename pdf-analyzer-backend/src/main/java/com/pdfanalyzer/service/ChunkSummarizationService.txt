package com.pdfanalyzer.service;

import com.pdfanalyzer.client.GeminiClient;
import com.pdfanalyzer.exception.AiServiceException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Handles large PDFs that exceed Gemini's single-call token budget.
 *
 * Strategy: Sliding window chunking + hierarchical summarization.
 *
 * How it works:
 * 1. Split the full extracted text into overlapping chunks.
 * 2. Send each chunk to Gemini requesting a prose summary.
 * 3. Concatenate the per-chunk summaries into a "meta-document".
 * 4. Return the meta-document to AiAnalysisService for final structured analysis.
 *
 * The overlap between chunks preserves context at chunk boundaries,
 * preventing mid-sentence or mid-paragraph splits from losing meaning.
 *
 * Activation threshold: pdf.processing.chunk-threshold-chars
 * Default: 35000 chars (leaves room below the 40000 max-text-chars budget)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChunkSummarizationService {

    private final GeminiClient geminiClient;

    @Value("${pdf.processing.chunk-size-chars:12000}")
    private int chunkSizeChars;

    @Value("${pdf.processing.chunk-overlap-chars:500}")
    private int chunkOverlapChars;

    @Value("${pdf.processing.chunk-threshold-chars:35000}")
    private int chunkThresholdChars;

    /**
     * Returns true if the text is long enough to require chunked summarization.
     */
    public boolean requiresChunking(String text) {
        return text != null && text.length() > chunkThresholdChars;
    }

    /**
     * Splits text into chunks, summarizes each, then concatenates summaries
     * into a condensed meta-document suitable for final Gemini analysis.
     *
     * @param fullText        Full extracted document text
     * @param documentTypeHint Pre-classified document type for prompt context
     * @return Condensed meta-document text
     */
    public String summarizeInChunks(String fullText, String documentTypeHint) {
        List<String> chunks = splitIntoChunks(fullText);
        log.info("Chunk summarization: {} chunks from {} chars", chunks.size(), fullText.length());

        List<String> chunkSummaries = new ArrayList<>();

        for (int i = 0; i < chunks.size(); i++) {
            log.info("Summarizing chunk {}/{}", i + 1, chunks.size());
            try {
                String summary = geminiClient.summarizeChunk(
                        chunks.get(i), documentTypeHint, i + 1, chunks.size());
                if (summary != null && !summary.isBlank()) {
                    chunkSummaries.add(summary.trim());
                }
            } catch (AiServiceException ex) {
                // Non-fatal: if one chunk fails, continue with others
                log.warn("Chunk {}/{} summarization failed — skipping: {}",
                        i + 1, chunks.size(), ex.getMessage());
            }
        }

        if (chunkSummaries.isEmpty()) {
            log.warn("All chunk summarizations failed — falling back to raw text truncation");
            return fullText.substring(0, Math.min(fullText.length(), chunkThresholdChars));
        }

        String metaDocument = String.join("\n\n---\n\n", chunkSummaries);
        log.info("Chunk summarization complete — meta-document: {} chars from {} chunks",
                metaDocument.length(), chunkSummaries.size());
        return metaDocument;
    }

    /**
     * Splits text into overlapping fixed-size chunks.
     * Overlap preserves context at chunk boundaries.
     */
    private List<String> splitIntoChunks(String text) {
        List<String> chunks = new ArrayList<>();
        int start = 0;
        int length = text.length();

        while (start < length) {
            int end = Math.min(start + chunkSizeChars, length);

            // If not at the last chunk, try to break at a paragraph boundary
            if (end < length) {
                int paragraphBreak = text.lastIndexOf("\n\n", end);
                if (paragraphBreak > start + (chunkSizeChars / 2)) {
                    end = paragraphBreak;
                }
            }

            chunks.add(text.substring(start, end));

            // Move forward, minus overlap to preserve boundary context
            start = end - chunkOverlapChars;
            if (start <= 0 || start >= length) break;
        }

        return chunks;
    }

    public int getChunkCount(String text) {
        return splitIntoChunks(text).size();
    }
}