package com.pdfanalyzer.service;

import com.pdfanalyzer.exception.PdfProcessingException;
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

import static org.assertj.core.api.Assertions.*;

@DisplayName("NativeTextExtractionService — PDFBox Text Extraction")
class NativeTextExtractionServiceTest {

    private NativeTextExtractionService nativeExtractor;

    @BeforeEach
    void setUp() {
        nativeExtractor = new NativeTextExtractionService();
        ReflectionTestUtils.setField(nativeExtractor, "maxTextChars", 40000);
    }

    private byte[] buildPdfWithText(String text) throws Exception {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                cs.newLineAtOffset(50, 700);
                cs.showText(text);
                cs.endText();
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        }
    }

    @Test
    @DisplayName("Extracts text correctly from single-page PDF")
    void singlePage_extractsText() throws Exception {
        byte[] pdf = buildPdfWithText("Hello from PDF extraction test");

        String result = nativeExtractor.extract(pdf);

        assertThat(result).isNotBlank();
        assertThat(result).contains("Hello");
    }

    @Test
    @DisplayName("Text is normalized — no excessive whitespace")
    void extractedText_isNormalized() throws Exception {
        byte[] pdf = buildPdfWithText("Clean text content.");

        String result = nativeExtractor.extract(pdf);

        assertThat(result).doesNotContain("   "); // No triple spaces
        assertThat(result.trim()).isEqualTo(result); // No leading/trailing whitespace
    }

    @Test
@DisplayName("Text exceeding maxTextChars is truncated with notice")
void oversizedText_isTruncatedWithNotice() throws Exception {
    ReflectionTestUtils.setField(nativeExtractor, "maxTextChars", 50);
    byte[] pdf = buildPdfWithText("This is a very long text that will definitely exceed fifty characters limit.");

    String result = nativeExtractor.extract(pdf);

    // Production appends: "\n\n[Content truncated — document too large for full analysis]"
    // "\n\n"                                                    = 2 chars
    // "[Content truncated — document too large for full analysis]" = 58 chars
    // Total suffix overhead                                     = 60 chars
    // maxTextChars(50) + suffix(60)                             = 110 → correct upper bound
    String expectedSuffix = "\n\n[Content truncated — document too large for full analysis]";
    assertThat(result.length()).isLessThanOrEqualTo(50 + expectedSuffix.length()); // ≤ 110
    assertThat(result).contains("[Content truncated");
    assertThat(result).endsWith(expectedSuffix);
}

    @Test
    @DisplayName("Corrupted PDF bytes throw PdfProcessingException")
    void corruptedBytes_throwsPdfProcessingException() {
        byte[] garbage = "not a pdf".getBytes();

        assertThatThrownBy(() -> nativeExtractor.extract(garbage))
            .isInstanceOf(PdfProcessingException.class);
    }
}