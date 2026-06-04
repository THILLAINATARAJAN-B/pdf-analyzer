package com.pdfanalyzer.service;

import com.pdfanalyzer.exception.PdfProcessingException;
import com.pdfanalyzer.model.ExtractionStrategy;
import com.pdfanalyzer.model.PdfInspectionResult;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PdfInspectionServiceTest {

    private PdfInspectionService inspectionService;

    @BeforeEach
    void setUp() {
        inspectionService = new PdfInspectionService();
        ReflectionTestUtils.setField(inspectionService, "maxPages", 200);
        ReflectionTestUtils.setField(inspectionService, "lowTextThreshold", 100);
    }

    @Test
    @DisplayName("NATIVE strategy selected when PDF has sufficient embedded text")
    void nativeStrategyForTextPdf() throws IOException {
        byte[] pdfBytes = buildTextPdf("This is a well-formatted research document with plenty of "
                + "embedded text content. It contains multiple sentences so the character "
                + "count well exceeds the low-text threshold used for strategy selection.");

        PdfInspectionResult result = inspectionService.inspect(pdfBytes);

        assertThat(result.getRecommendedStrategy()).isEqualTo(ExtractionStrategy.NATIVE);
        assertThat(result.isLowTextDensity()).isFalse();
        assertThat(result.isHasEmbeddedText()).isTrue();   // Lombok: boolean → isHasEmbeddedText()
        assertThat(result.getTotalPages()).isEqualTo(1);
    }

    @Test
    @DisplayName("OCR strategy selected when PDF has no embedded text")
    void ocrStrategyForImagePdf() throws IOException {
        byte[] pdfBytes = buildEmptyPdf(); // page with no content stream = no text

        PdfInspectionResult result = inspectionService.inspect(pdfBytes);

        assertThat(result.getRecommendedStrategy()).isEqualTo(ExtractionStrategy.OCR);
        assertThat(result.isHasEmbeddedText()).isFalse();  // Lombok: boolean → isHasEmbeddedText()
    }

    @Test
    @DisplayName("Throws PdfProcessingException for empty PDF (0 pages)")
    void throwsForZeroPagePdf() throws IOException {
        byte[] pdfBytes = buildPdfWithPages(0);

        assertThatThrownBy(() -> inspectionService.inspect(pdfBytes))
                .isInstanceOf(PdfProcessingException.class)
                .hasMessageContaining("no pages");
    }

    @Test
    @DisplayName("Throws PdfProcessingException when page count exceeds max")
    void throwsWhenPageLimitExceeded() throws IOException {
        ReflectionTestUtils.setField(inspectionService, "maxPages", 2);
        byte[] pdfBytes = buildPdfWithPages(3);

        assertThatThrownBy(() -> inspectionService.inspect(pdfBytes))
                .isInstanceOf(PdfProcessingException.class)
                .hasMessageContaining("maximum allowed page count");
    }

    @Test
    @DisplayName("Throws PdfProcessingException for invalid/corrupt byte array")
    void throwsForCorruptBytes() {
        byte[] garbage = "not a pdf at all".getBytes();

        assertThatThrownBy(() -> inspectionService.inspect(garbage))
                .isInstanceOf(PdfProcessingException.class);
    }

    // ── PDF Builders ──────────────────────────────────────────────────────────

    private byte[] buildTextPdf(String text) throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage();
            doc.addPage(page);

            // PDFBox 3.x: static font constants removed — use Standard14Fonts.FontName enum
            PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);

            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(font, 12);
                cs.newLineAtOffset(50, 700);
                cs.showText(text);
                cs.endText();
            }

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        }
    }

    private byte[] buildEmptyPdf() throws IOException {
        try (PDDocument doc = new PDDocument()) {
            doc.addPage(new PDPage()); // no content stream = no embedded text
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        }
    }

    private byte[] buildPdfWithPages(int pageCount) throws IOException {
        try (PDDocument doc = new PDDocument()) {
            for (int i = 0; i < pageCount; i++) {
                doc.addPage(new PDPage());
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        }
    }
}