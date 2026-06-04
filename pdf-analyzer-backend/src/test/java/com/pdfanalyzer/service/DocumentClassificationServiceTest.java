package com.pdfanalyzer.service;

import com.pdfanalyzer.model.DocumentType;
import com.pdfanalyzer.model.ExtractionStrategy;
import com.pdfanalyzer.model.PdfInspectionResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

@DisplayName("DocumentClassificationService — Document Type Detection")
class DocumentClassificationServiceTest {

    private DocumentClassificationService classificationService;

    @BeforeEach
    void setUp() {
        classificationService = new DocumentClassificationService();
    }

    private PdfInspectionResult inspectionWith(DocumentType type) {
        return PdfInspectionResult.builder()
            .totalPages(10)
            .recommendedStrategy(ExtractionStrategy.NATIVE)
            .preclassifiedType(type)
            .hasEmbeddedText(true)
            .isLowTextDensity(false)
            .extractedCharCount(3000)
            .build();
    }

    @Test
    @DisplayName("Inspection pre-classified RESEARCH_PAPER is returned correctly")
    void preClassifiedResearchPaper_returnsResearchPaper() {
        String result = classificationService.classify("any text", inspectionWith(DocumentType.RESEARCH_PAPER));
        assertThat(result).isEqualTo("Research Paper");
    }

    @Test
    @DisplayName("UNKNOWN type refined by text signals — abstract+references → Research Paper")
    void unknownType_withAbstractReferences_returnsResearchPaper() {
        String text = "Abstract — This paper examines... References: [1] Smith et al. Conclusion here.";
        String result = classificationService.classify(text, inspectionWith(DocumentType.UNKNOWN));
        assertThat(result).isEqualTo("Research Paper");
    }

    @Test
    @DisplayName("UNKNOWN type refined by text signals — invoice signals → Invoice or Form")
    void unknownType_withInvoiceText_returnsInvoice() {
        String text = "Invoice #12345. Bill to: Acme Corp. Total amount due: $500.";
        String result = classificationService.classify(text, inspectionWith(DocumentType.UNKNOWN));
        assertThat(result).isEqualTo("Invoice or Form");
    }

    @Test
    @DisplayName("UNKNOWN type with legal signals → Legal Document")
    void unknownType_withLegalText_returnsLegalDocument() {
        String text = "This agreement is entered whereas the party agrees to terms and conditions hereinafter.";
        String result = classificationService.classify(text, inspectionWith(DocumentType.UNKNOWN));
        assertThat(result).isEqualTo("Legal Document");
    }

    @Test
    @DisplayName("UNKNOWN type with no signals → General Document")
    void unknownType_withNoSignals_returnsGeneralDocument() {
        String result = classificationService.classify("Random text with no signals.", inspectionWith(DocumentType.UNKNOWN));
        assertThat(result).isEqualTo("General Document");
    }

    @Test
    @DisplayName("Blank text with UNKNOWN type → General Document")
    void blankText_unknownType_returnsGeneralDocument() {
        String result = classificationService.classify("", inspectionWith(DocumentType.UNKNOWN));
        assertThat(result).isEqualTo("General Document");
    }
}