package com.pdfanalyzer.service.impl;

import com.pdfanalyzer.dto.request.AnalyzeRequest;
import com.pdfanalyzer.dto.response.AnalysisResult;
import com.pdfanalyzer.model.PdfInspectionResult;
import com.pdfanalyzer.service.AiAnalysisService;
import com.pdfanalyzer.service.AnalyzeService;
import com.pdfanalyzer.service.DocumentClassificationService;
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

    /**
     * 6-stage document ingestion pipeline.
     *
     * 1. URL Validation    — SSRF protection + DNS pinning
     * 2. Safe Download     — chunked stream, byte limit, magic-byte check
     * 3. PDF Inspection    — structure analysis, strategy decision
     * 4. Text Extraction   — NATIVE / OCR / HYBRID routing
     * 5. Classification    — document type hint for prompt enrichment
     * 6. AI Analysis       — structured Gemini/OpenAI output with retry
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

        // Stage 4 — Text Extraction
        log.info("[Stage 4] Extracting text — strategy: {}",
                inspection.getRecommendedStrategy());
        String extractedText = extractionOrchestrator.extract(pdfBytes, inspection);
        log.info("[Stage 4] Extracted {} chars", extractedText.length());

        // Stage 5 — Document Classification
        log.info("[Stage 5] Classifying document");
        String documentTypeHint = classificationService.classify(extractedText, inspection);
        log.info("[Stage 5] Document type: {}", documentTypeHint);

        // Stage 6 — AI Analysis
        log.info("[Stage 6] Sending to AI analysis");
        AnalysisResult result = aiAnalysisService.analyze(extractedText, documentTypeHint);

        // Attach lightweight pipeline metadata
        result.setExtractionStrategy(inspection.getRecommendedStrategy().name());
        result.setTotalPages(inspection.getTotalPages());

        log.info("=== PDF Analysis Pipeline COMPLETE === strategy={}, pages={}",
                inspection.getRecommendedStrategy(), inspection.getTotalPages());

        return result;
    }
}