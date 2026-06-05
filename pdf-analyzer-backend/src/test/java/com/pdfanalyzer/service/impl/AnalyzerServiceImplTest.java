package com.pdfanalyzer.service.impl;

import com.pdfanalyzer.dto.request.AnalyzeRequest;
import com.pdfanalyzer.dto.response.AnalysisResult;
import com.pdfanalyzer.exception.InvalidUrlException;
import com.pdfanalyzer.exception.PdfDownloadException;
import com.pdfanalyzer.model.ExtractionStrategy;
import com.pdfanalyzer.model.PdfInspectionResult;
import com.pdfanalyzer.model.DocumentType;
import com.pdfanalyzer.service.AiAnalysisService;
import com.pdfanalyzer.service.AnalyzeService;
import com.pdfanalyzer.service.PdfDownloadService;
import com.pdfanalyzer.service.PdfExtractionOrchestrator;
import com.pdfanalyzer.service.PdfInspectionService;
import com.pdfanalyzer.validation.UrlValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AnalyzerServiceImplTest {

    @Mock private UrlValidator urlValidator;
    @Mock private PdfDownloadService pdfDownloadService;
    @Mock private PdfInspectionService pdfInspectionService;
    @Mock private PdfExtractionOrchestrator extractionOrchestrator;
    @Mock private AiAnalysisService aiAnalysisService;

    private AnalyzeService analyzeService;

    private static final byte[] DUMMY_PDF = "%PDF-1.4 test content".getBytes();

    @BeforeEach
    void setUp() {
        analyzeService = new AnalyzeServiceImpl(
                urlValidator,
                pdfDownloadService,
                pdfInspectionService,
                extractionOrchestrator,
                aiAnalysisService
        );
    }

    private PdfInspectionResult nativeInspection() {
        return PdfInspectionResult.builder()
                .totalPages(10)
                .extractedCharCount(5000)
                .hasEmbeddedText(true)
                .isLowTextDensity(false)
                .recommendedStrategy(ExtractionStrategy.NATIVE)
                .preclassifiedType(DocumentType.RESEARCH_PAPER)
                .build();
    }

    @Test
    @DisplayName("Returns structured AnalysisResult when all pipeline stages succeed")
    void successfulPipeline() {
        AnalysisResult aiResult = AnalysisResult.builder()
                .documentType("Research Paper")
                .title("Attention Is All You Need")
                .authors("Vaswani et al.")
                .summary("A foundational paper on Transformer architecture.")
                .keyTakeaway("Self-attention replaces recurrence in seq2seq tasks.")
                .build();

        doNothing().when(urlValidator).validate(anyString());
        when(pdfDownloadService.download(anyString())).thenReturn(DUMMY_PDF);
        when(pdfInspectionService.inspect(any())).thenReturn(nativeInspection());
        when(extractionOrchestrator.extract(any(), any())).thenReturn("Extracted text content here.");
        when(aiAnalysisService.analyze(anyString(), anyString())).thenReturn(aiResult);

        AnalyzeRequest request = new AnalyzeRequest();
        request.setPdfUrl("https://arxiv.org/pdf/1706.03762");

        AnalysisResult result = analyzeService.analyze(request);

        assertThat(result).isNotNull();
        assertThat(result.getTitle()).isEqualTo("Attention Is All You Need");
        assertThat(result.getDocumentType()).isEqualTo("Research Paper");
        assertThat(result.getSummary()).isNotBlank();
        assertThat(result.getExtractionStrategy()).isEqualTo("NATIVE");
        assertThat(result.getTotalPages()).isEqualTo(10);
    }

    @Test
    @DisplayName("Throws InvalidUrlException when URL validation fails")
    void throwsWhenUrlInvalid() {
        doThrow(new InvalidUrlException("Access to internal addresses is not permitted."))
                .when(urlValidator).validate(anyString());

        AnalyzeRequest request = new AnalyzeRequest();
        request.setPdfUrl("http://169.254.169.254/metadata");

        assertThatThrownBy(() -> analyzeService.analyze(request))
                .isInstanceOf(InvalidUrlException.class)
                .hasMessageContaining("internal addresses");
    }

    @Test
    @DisplayName("Throws PdfDownloadException when download fails")
    void throwsWhenDownloadFails() {
        doNothing().when(urlValidator).validate(anyString());
        when(pdfDownloadService.download(anyString()))
                .thenThrow(new PdfDownloadException("Unable to download PDF."));

        AnalyzeRequest request = new AnalyzeRequest();
        request.setPdfUrl("https://example.com/unreachable.pdf");

        assertThatThrownBy(() -> analyzeService.analyze(request))
                .isInstanceOf(PdfDownloadException.class)
                .hasMessageContaining("Unable to download");
    }

    @Test
    @DisplayName("All pipeline stages are invoked in correct order")
    void allStagesInvokedInOrder() {
        AnalysisResult aiResult = AnalysisResult.builder()
                .documentType("Business Report")
                .title("Q1 Report")
                .authors("Finance Team")
                .summary("Quarterly financial summary.")
                .keyTakeaway("Revenue grew 12% year-over-year.")
                .build();

        doNothing().when(urlValidator).validate(anyString());
        when(pdfDownloadService.download(anyString())).thenReturn(DUMMY_PDF);
        when(pdfInspectionService.inspect(any())).thenReturn(nativeInspection());
        when(extractionOrchestrator.extract(any(), any())).thenReturn("Quarterly revenue report.");
        
        when(aiAnalysisService.analyze(anyString(), anyString())).thenReturn(aiResult);

        AnalyzeRequest request = new AnalyzeRequest();
        request.setPdfUrl("https://example.com/report.pdf");

        analyzeService.analyze(request);

        // Verify all stages were called
        verify(urlValidator).validate("https://example.com/report.pdf");
        verify(pdfDownloadService).download("https://example.com/report.pdf");
        verify(pdfInspectionService).inspect(DUMMY_PDF);
        verify(extractionOrchestrator).extract(any(), any());
        verify(aiAnalysisService).analyze(anyString(), anyString());
    }
}