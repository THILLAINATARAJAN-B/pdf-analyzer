package com.pdfanalyzer.service.impl;

import com.pdfanalyzer.dto.request.AnalyzeRequest;
import com.pdfanalyzer.dto.response.AnalysisResult;
import com.pdfanalyzer.model.PdfInspectionResult;
import com.pdfanalyzer.model.QualityReport;
import com.pdfanalyzer.service.AiAnalysisService;
import com.pdfanalyzer.service.AnalyzeService;
import com.pdfanalyzer.service.ChunkSummarizationService;
import com.pdfanalyzer.service.DocumentClassificationService;
import com.pdfanalyzer.service.DocumentQualityService;
import com.pdfanalyzer.service.PdfDownloadService;
import com.pdfanalyzer.service.PdfExtractionOrchestrator;
import com.pdfanalyzer.service.PdfInspectionService;
import com.pdfanalyzer.validation.UrlValidator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class AnalyzeServiceImpl implements AnalyzeService {

    private final UrlValidator urlValidator;
    private final PdfDownloadService pdfDownloadService;
    private final PdfInspectionService pdfInspectionService;
    private final PdfExtractionOrchestrator extractionOrchestrator;
    private final DocumentClassificationService classificationService;
    private final AiAnalysisService aiAnalysisService;
    private final ChunkSummarizationService chunkSummarizationService;
    private final DocumentQualityService documentQualityService;

    /**
     * Full 7-stage document ingestion pipeline:
     *
     * 1. URL Validation          — SSRF protection + DNS pinning
     * 2. Safe Download           — chunked stream, byte limit, magic-byte check
     * 3. PDF Inspection          — structure analysis, page count, text density
     * 4. Extraction Strategy     — NATIVE / OCR / HYBRID routing
     * 5. Document Classification — type signals for prompt enrichment
     * 5b. Chunk Summarization    — hierarchical summarization for large documents
     * 6. Gemini Structured AI    — schema-based output, retry, safety filter handling
     * 7. Quality Scoring         — coherence, OCR confidence, blank-page ratio
     */
    @Override
    public AnalysisResult analyze(AnalyzeRequest request) {
        String pdfUrl = request.getPdfUrl().trim();
        log.info("=== PDF Analysis Pipeline START ===");

        // Stage 1 — URL Validation
        log.info("[Stage 1] Validating URL");
        urlValidator.validate(pdfUrl);

        // Stage 2 — Safe Chunked Download
        log.info("[Stage 2] Downloading PDF");
        byte[] pdfBytes = pdfDownloadService.download(pdfUrl);

        // Stage 3 — PDF Structural Inspection
        log.info("[Stage 3] Inspecting PDF structure");
        PdfInspectionResult inspection = pdfInspectionService.inspect(pdfBytes);
        log.info("[Stage 3] pages={}, strategy={}, type={}",
                inspection.getTotalPages(),
                inspection.getRecommendedStrategy(),
                inspection.getPreclassifiedType());

        // Stage 4 — Text Extraction via Strategy
        log.info("[Stage 4] Extracting text — strategy: {}",
                inspection.getRecommendedStrategy());
        String extractedText = extractionOrchestrator.extract(pdfBytes, inspection);
        log.info("[Stage 4] Extracted {} chars", extractedText.length());

        // Stage 5 — Document Classification
        log.info("[Stage 5] Classifying document");
        String documentTypeHint = classificationService.classify(extractedText, inspection);
        log.info("[Stage 5] Document type: {}", documentTypeHint);

        // Stage 5b — Chunk Summarization (only for large documents)
        boolean wasChunked = false;
        int chunkCount = 0;
        if (chunkSummarizationService.requiresChunking(extractedText)) {
            chunkCount = chunkSummarizationService.getChunkCount(extractedText);
            log.info("[Stage 5b] Large document detected — {} chunks required", chunkCount);
            extractedText = chunkSummarizationService.summarizeInChunks(
                    extractedText, documentTypeHint);
            wasChunked = true;
            log.info("[Stage 5b] Chunk summarization complete — meta-doc: {} chars",
                    extractedText.length());
        }

        // Stage 6 — Gemini Structured Analysis
        log.info("[Stage 6] Sending to AI analysis");
        AnalysisResult result = aiAnalysisService.analyze(extractedText, documentTypeHint);

        // Stage 7 — Quality Report
        log.info("[Stage 7] Computing quality report");
        QualityReport qualityReport = documentQualityService.compute(
                extractedText, inspection, wasChunked, wasChunked ? chunkCount : null);

        // Enrich result with pipeline metadata
        result.setExtractionStrategy(inspection.getRecommendedStrategy().name());
        result.setTotalPages(inspection.getTotalPages());
        result.setQualityScore(qualityReport.getTier());
        result.setQualityReport(qualityReport);

        log.info("=== PDF Analysis Pipeline COMPLETE === quality={}, chunked={}, strategy={}",
                qualityReport.getTier(), wasChunked, inspection.getRecommendedStrategy());

        return result;
    }
}