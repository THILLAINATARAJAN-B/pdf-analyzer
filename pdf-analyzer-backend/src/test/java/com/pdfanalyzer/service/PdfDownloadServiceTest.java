package com.pdfanalyzer.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PdfDownloadServiceTest {

    @Test
    @DisplayName("Resolves relative redirect Location against base URL")
    void resolvesRelativeRedirect() {
        String base = "https://arxiv.org/pdf/1706.03762.pdf";
        String location = "/pdf/1706.03762";

        assertEquals("https://arxiv.org/pdf/1706.03762",
                PdfDownloadService.resolveRedirectUrl(base, location));
    }

    @Test
    @DisplayName("Resolves protocol-relative redirect Location")
    void resolvesProtocolRelativeRedirect() {
        String base = "http://example.com/old.pdf";
        String location = "//cdn.example.com/files/doc.pdf";

        assertEquals("http://cdn.example.com/files/doc.pdf",
                PdfDownloadService.resolveRedirectUrl(base, location));
    }

    @Test
    @DisplayName("Keeps absolute redirect Location unchanged")
    void keepsAbsoluteRedirect() {
        String base = "https://example.com/start.pdf";
        String location = "https://cdn.example.com/files/doc.pdf";

        assertEquals("https://cdn.example.com/files/doc.pdf",
                PdfDownloadService.resolveRedirectUrl(base, location));
    }
}
