package com.pdfanalyzer.service;

import com.pdfanalyzer.exception.PdfProcessingException;
import com.pdfanalyzer.model.DocumentType;
import com.pdfanalyzer.model.ExtractionStrategy;
import com.pdfanalyzer.model.PdfInspectionResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("PdfExtractionOrchestrator Tests")
class PdfExtractionOrchestratorTest {

    @Mock
    private NativeTextExtractionService nativeExtractor;

    @Mock
    private OcrExtractionService ocrExtractor;

    @InjectMocks
    private PdfExtractionOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(orchestrator, "ocrEnabled", true);
        ReflectionTestUtils.setField(orchestrator, "lowTextThreshold", 100);
    }

    // ── NATIVE Strategy ──────────────────────────────────────────────────────

    @Test
    @DisplayName("NATIVE strategy — delegates to NativeTextExtractionService")
    void nativeStrategy_delegatesToNativeExtractor() {
        byte[] pdf = new byte[]{};
        PdfInspectionResult inspection = buildInspection(ExtractionStrategy.NATIVE);

        when(nativeExtractor.extract(pdf)).thenReturn("Native extracted text content.");

        String result = orchestrator.extract(pdf, inspection);

        assertThat(result).isEqualTo("Native extracted text content.");
        verify(nativeExtractor).extract(pdf);
        verifyNoInteractions(ocrExtractor);
    }

    @Test
    @DisplayName("NATIVE strategy — throws when extraction returns blank")
    void nativeStrategy_blankResult_throwsException() {
        byte[] pdf = new byte[]{};
        PdfInspectionResult inspection = buildInspection(ExtractionStrategy.NATIVE);

        when(nativeExtractor.extract(pdf)).thenReturn("   ");

        assertThatThrownBy(() -> orchestrator.extract(pdf, inspection))
                .isInstanceOf(PdfProcessingException.class)
                .hasMessageContaining("No readable text");
    }

    // ── OCR Strategy ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("OCR strategy — delegates to OcrExtractionService when enabled")
    void ocrStrategy_ocrEnabled_delegatesToOcrExtractor() {
        byte[] pdf = new byte[]{};
        PdfInspectionResult inspection = buildInspection(ExtractionStrategy.OCR);

        when(ocrExtractor.extractWithOcr(pdf)).thenReturn("OCR extracted text.");

        String result = orchestrator.extract(pdf, inspection);

        assertThat(result).isEqualTo("OCR extracted text.");
        verify(ocrExtractor).extractWithOcr(pdf);
        verifyNoInteractions(nativeExtractor);
    }

    @Test
    @DisplayName("OCR strategy — throws when OCR is disabled in config")
    void ocrStrategy_ocrDisabled_throwsException() {
        ReflectionTestUtils.setField(orchestrator, "ocrEnabled", false);
        byte[] pdf = new byte[]{};
        PdfInspectionResult inspection = buildInspection(ExtractionStrategy.OCR);

        assertThatThrownBy(() -> orchestrator.extract(pdf, inspection))
                .isInstanceOf(PdfProcessingException.class)
                .hasMessageContaining("OCR");
    }

    // ── HYBRID Strategy ──────────────────────────────────────────────────────

    @Test
    @DisplayName("HYBRID strategy — native sufficient, OCR not called")
    void hybridStrategy_nativeSufficient_ocrNotCalled() {
        byte[] pdf = new byte[]{};
        PdfInspectionResult inspection = buildInspection(ExtractionStrategy.HYBRID);

        // 200 chars > lowTextThreshold (100) → native is sufficient
        when(nativeExtractor.extract(pdf)).thenReturn("X".repeat(200));

        String result = orchestrator.extract(pdf, inspection);

        assertThat(result).hasSize(200);
        verify(nativeExtractor).extract(pdf);
        verifyNoInteractions(ocrExtractor);
    }

    @Test
    @DisplayName("HYBRID strategy — native insufficient, OCR called")
    void hybridStrategy_nativeInsufficient_ocrCalled() {
        // Arrange
        byte[] pdf = new byte[]{};
        PdfInspectionResult inspection = PdfInspectionResult.builder()
                .totalPages(5)
                .recommendedStrategy(ExtractionStrategy.HYBRID)
                .hasEmbeddedText(true)
                .isLowTextDensity(true)
                .extractedCharCount(4)
                .preclassifiedType(DocumentType.UNKNOWN)
                .build();

        // CRITICAL FIX: stub isAvailable() → true so OCR branch is entered
        // Without this stub, Mockito returns false by default, silently
        // skipping the OCR fallback and causing a false-negative test failure.
        when(ocrExtractor.isAvailable()).thenReturn(true);
        when(nativeExtractor.extract(pdf)).thenReturn("tiny");  // 4 chars < lowTextThreshold (100)
        when(ocrExtractor.extractWithOcr(pdf)).thenReturn("Full OCR extracted text from scanned document.");

        // Act
        String result = orchestrator.extract(pdf, inspection);

        // Assert
        verify(ocrExtractor).extractWithOcr(pdf);
        assertThat(result).contains("OCR extracted text");
    }

    @Test
    @DisplayName("HYBRID strategy — OCR disabled, returns partial native text")
    void hybridStrategy_ocrDisabled_returnsNativeText() {
        ReflectionTestUtils.setField(orchestrator, "ocrEnabled", false);
        byte[] pdf = new byte[]{};
        PdfInspectionResult inspection = buildInspection(ExtractionStrategy.HYBRID);

        when(nativeExtractor.extract(pdf)).thenReturn("small");

        String result = orchestrator.extract(pdf, inspection);

        assertThat(result).isEqualTo("small");
        verifyNoInteractions(ocrExtractor);
    }

    // ── Helper ────────────────────────────────────────────────────────────────

    private PdfInspectionResult buildInspection(ExtractionStrategy strategy) {
        return PdfInspectionResult.builder()
                .totalPages(5)
                .recommendedStrategy(strategy)
                .hasEmbeddedText(strategy != ExtractionStrategy.OCR)
                .isLowTextDensity(strategy == ExtractionStrategy.HYBRID)
                .extractedCharCount(strategy == ExtractionStrategy.NATIVE ? 500 : 4)
                .preclassifiedType(DocumentType.UNKNOWN)
                .build();
    }
}