package com.pdfanalyzer.service.impl;

import com.pdfanalyzer.client.GeminiClient;
import com.pdfanalyzer.client.PdfDownloadClient;
import com.pdfanalyzer.dto.request.AnalyzeRequest;
import com.pdfanalyzer.dto.response.AnalysisResult;
import com.pdfanalyzer.service.AnalyzeService;
import com.pdfanalyzer.util.PdfTextExtractor;
import com.pdfanalyzer.validation.UrlValidator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.InputStream;

@Slf4j
@Service
@RequiredArgsConstructor
public class AnalyzeServiceImpl implements AnalyzeService {

    private final UrlValidator urlValidator;
    private final PdfDownloadClient pdfDownloadClient;
    private final PdfTextExtractor pdfTextExtractor;
    private final GeminiClient geminiClient;

    @Override
    public AnalysisResult analyze(AnalyzeRequest request) {
        String pdfUrl = request.getPdfUrl().trim();

        log.info("Starting PDF analysis pipeline");

        // Step 1: Validate URL
        urlValidator.validate(pdfUrl);
        log.info("URL validation passed");

        // Step 2: Download PDF
        byte[] pdfBytes = pdfDownloadClient.download(pdfUrl);
        log.info("PDF downloaded successfully, size: {} bytes", pdfBytes.length);

        // Step 3: Extract text
        String extractedText = pdfTextExtractor.extract(pdfBytes);
        log.info("Text extraction complete, chars: {}", extractedText.length());

        // Step 4: Send to Gemini for analysis
        AnalysisResult result = geminiClient.analyze(extractedText);
        log.info("Gemini analysis complete");

        return result;
    }
}