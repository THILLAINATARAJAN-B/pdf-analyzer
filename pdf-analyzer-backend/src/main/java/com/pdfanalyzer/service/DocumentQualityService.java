package com.pdfanalyzer.service;

import com.pdfanalyzer.model.ExtractionStrategy;
import com.pdfanalyzer.model.PdfInspectionResult;
import com.pdfanalyzer.model.QualityReport;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Set;
import java.util.regex.Pattern;

/**
 * Computes a detailed QualityReport from extraction signals.
 *
 * Quality is measured across four dimensions:
 * 1. Text volume      — raw character count after normalization
 * 2. Text coherence   — ratio of real English words vs random characters
 * 3. Blank page ratio — proportion of near-empty pages in the document
 * 4. Truncation flag  — whether the text budget was hit during extraction
 *
 * These signals combine into a tier (HIGH / MEDIUM / LOW)
 * with a human-readable note for the API response.
 */
@Slf4j
@Service
public class DocumentQualityService {

    private static final Pattern WORD_PATTERN  = Pattern.compile("[a-zA-Z]{3,}");
    private static final Pattern TOKEN_PATTERN = Pattern.compile("\\S+");

    // Common English words used for coherence scoring
    private static final Set<String> COMMON_WORDS = Set.of(
            "the", "and", "for", "are", "with", "this", "that", "from",
            "have", "has", "been", "were", "they", "their", "which",
            "will", "can", "not", "but", "all", "one", "more", "also",
            "use", "used", "into", "when", "than", "then", "its", "may"
    );

    @Value("${pdf.processing.max-text-chars:40000}")
    private int maxTextChars;

    @Value("${pdf.processing.low-text-threshold:100}")
    private int lowTextThreshold;

    public QualityReport compute(String extractedText,
                                  PdfInspectionResult inspection,
                                  boolean wasChunked,
                                  Integer chunkCount) {

        int charCount = extractedText == null ? 0 : extractedText.trim().length();
        boolean truncated = extractedText != null
                && extractedText.contains("[Content truncated");

        double coherence  = computeCoherence(extractedText);
        double blankRatio = computeBlankPageRatio(inspection);
        Integer ocrConfidence = estimateOcrConfidence(
                inspection.getRecommendedStrategy(), extractedText);

        String tier = computeTier(charCount, coherence, inspection.getRecommendedStrategy());
        String note = buildNote(tier, charCount, coherence, truncated,
                inspection.getRecommendedStrategy(), blankRatio);

        // ✅ Fix: Java SLF4J placeholders + String.format for decimal values
        log.info("Quality computed — tier={}, chars={}, coherence={}, blankRatio={}",
                tier,
                charCount,
                String.format("%.2f", coherence),
                String.format("%.2f", blankRatio));

        return QualityReport.builder()
                .tier(tier)
                .extractedCharCount(charCount)
                .blankPageRatio(blankRatio)
                .ocrConfidenceScore(ocrConfidence)
                .textTruncated(truncated)
                .chunked(wasChunked)
                .chunkCount(wasChunked ? chunkCount : null)
                .textCoherenceScore(Math.round(coherence * 100.0) / 100.0)
                .note(note)
                .build();
    }

    /**
     * Computes text coherence as the ratio of known English tokens to total tokens.
     * High coherence = real readable text.
     * Low coherence  = OCR garbage, encoding artifacts, or binary content.
     */
    private double computeCoherence(String text) {
        if (text == null || text.isBlank()) return 0.0;

        var tokenMatcher = TOKEN_PATTERN.matcher(text);
        int totalTokens = 0;
        int knownWords  = 0;

        while (tokenMatcher.find()) {
            totalTokens++;
            String token = tokenMatcher.group().toLowerCase()
                    .replaceAll("[^a-z]", "");
            if (COMMON_WORDS.contains(token)) {
                knownWords++;
            }
        }

        if (totalTokens == 0) return 0.0;
        return (double) knownWords / totalTokens;
    }

    private double computeBlankPageRatio(PdfInspectionResult inspection) {
        int total = inspection.getTotalPages();
        if (total == 0) return 1.0;
        int imageOnly = inspection.getImageOnlyPageCount();
        return (double) imageOnly / total;
    }

    /**
     * Estimates OCR confidence based on text coherence and character density.
     * Returns null if native extraction was used (OCR confidence not applicable).
     * Scale: 0–100.
     */
    private Integer estimateOcrConfidence(ExtractionStrategy strategy, String text) {
        if (strategy == ExtractionStrategy.NATIVE) return null;
        if (text == null || text.isBlank()) return 0;

        double coherence = computeCoherence(text);
        int charCount    = text.trim().length();

        int baseScore    = (int) (coherence * 70); // coherence contributes up to 70 pts
        int volumeBonus  = charCount > 500 ? 20 : (charCount > 100 ? 10 : 0);
        int densityBonus = charCount > 2000 ? 10 : 0;

        return Math.min(100, baseScore + volumeBonus + densityBonus);
    }

    private String computeTier(int charCount,
                                double coherence,
                                ExtractionStrategy strategy) {
        if (charCount < lowTextThreshold || coherence < 0.01) return "LOW";
        if (strategy == ExtractionStrategy.NATIVE
                && charCount > 3000 && coherence > 0.05) return "HIGH";
        if (charCount > 1000 && coherence > 0.03) return "MEDIUM";
        if (charCount > 300) return "MEDIUM";
        return "LOW";
    }

    private String buildNote(String tier, int chars, double coherence,
                              boolean truncated, ExtractionStrategy strategy,
                              double blankRatio) {
        StringBuilder note = new StringBuilder();
        note.append("Extraction via ").append(strategy.name()).append(". ");

        switch (tier) {
            case "HIGH"   -> note.append(
                    "Text is rich and coherent — analysis confidence is high.");
            case "MEDIUM" -> note.append(
                    "Text volume or coherence is moderate — analysis may miss some details.");
            case "LOW"    -> note.append(
                    "Limited readable text extracted — analysis may be incomplete.");
        }

        if (truncated) {
            note.append(" Document was truncated to fit analysis budget.");
        }
        if (blankRatio > 0.3) {
            note.append(String.format(" %.0f%% of pages appear image-only.", blankRatio * 100));
        }

        return note.toString().trim();
    }

    // Expose threshold for orchestrator use
    public int getLowTextThreshold() {
        return lowTextThreshold;
    }
}