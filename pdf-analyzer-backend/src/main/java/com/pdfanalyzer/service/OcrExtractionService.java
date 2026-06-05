package com.pdfanalyzer.service;

import com.pdfanalyzer.exception.PdfProcessingException;
import com.pdfanalyzer.util.TessdataPathResolver;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import net.sourceforge.tess4j.ITessAPI;
import net.sourceforge.tess4j.Tesseract;
import net.sourceforge.tess4j.TesseractException;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
public class OcrExtractionService {

    private static final float RENDER_DPI = 300f;

    @Value("${pdf.ocr.tessdata-path:}")
    private String configuredTessdataPath;

    @Value("${pdf.processing.max-text-chars:40000}")
    private int maxTextChars;

    @Value("${pdf.ocr.max-pages:10}")
    private int maxOcrPages;

    private String tessdataPath;
    private boolean ocrAvailable;

    @PostConstruct
    void initTessdata() {
        tessdataPath = TessdataPathResolver.resolve(configuredTessdataPath);
        ocrAvailable = TessdataPathResolver.isAvailable(tessdataPath);
        if (ocrAvailable) {
            log.info("OCR available — tessdata path: {}", tessdataPath);
        } else {
            log.warn("OCR unavailable — install Tesseract and eng.traineddata, or set pdf.ocr.tessdata-path");
        }
    }

    public boolean isAvailable() {
        return ocrAvailable;
    }

    public String extractWithOcr(byte[] pdfBytes) {
        if (!ocrAvailable) {
            throw new PdfProcessingException(
                    "This PDF appears to be scanned or image-based and requires OCR, "
                            + "but Tesseract is not installed or tessdata is missing on this server. "
                            + "Install Tesseract OCR locally or use a PDF with embedded text.");
        }

        log.info("Starting OCR extraction pipeline");

        try (PDDocument document = Loader.loadPDF(pdfBytes)) {
            if (document.isEncrypted()) {
                throw new com.pdfanalyzer.exception.PdfPasswordException(
                        "This PDF is password-protected. Please provide an unlocked version.");
            }

            int totalPages = document.getNumberOfPages();
            int pagesToProcess = Math.min(totalPages, maxOcrPages);
            log.info("OCR: processing {} of {} pages", pagesToProcess, totalPages);

            PDFRenderer renderer = new PDFRenderer(document);
            Tesseract tesseract = buildTesseract();
            List<String> pageTexts = new ArrayList<>();

            for (int page = 0; page < pagesToProcess; page++) {
    BufferedImage rawImage    = null;
    BufferedImage binaryImage = null;
    try {
        rawImage    = renderer.renderImageWithDPI(page, RENDER_DPI, ImageType.RGB);
        binaryImage = preprocessImage(rawImage);
        String pageText = tesseract.doOCR(binaryImage);
        if (pageText != null && !pageText.isBlank()) {
            pageTexts.add(pageText.trim());
        }
        log.debug("OCR completed for page {}", page + 1);
    } catch (TesseractException ex) {
        log.warn("OCR failed for page {} — skipping: {}", page + 1, ex.getMessage());
    } catch (Error ex) {
        log.error("OCR native engine failure on page {}: {}", page + 1, ex.getMessage());
        throw new PdfProcessingException(
                "OCR engine failed while processing this scanned PDF. "
                        + "Ensure Tesseract is correctly installed with eng.traineddata.");
    } finally {
        if (rawImage    != null) rawImage.flush();
        if (binaryImage != null) binaryImage.flush();
    }
}

            if (pageTexts.isEmpty()) {
                throw new PdfProcessingException(
                        "OCR extraction yielded no readable text. "
                                + "The document may use unsupported image formats or low-quality scans.");
            }

            if (totalPages > maxOcrPages) {
                pageTexts.add("\n[OCR limited to first " + maxOcrPages + " pages of " + totalPages + " total]");
            }

            String combined = String.join("\n\n", pageTexts);
            if (combined.length() > maxTextChars) {
                combined = combined.substring(0, maxTextChars) + "\n[Truncated]";
            }

            log.info("OCR extraction complete: {} chars from {} pages", combined.length(), pagesToProcess);
            return combined;

        } catch (InvalidPasswordException ex) {
            throw new com.pdfanalyzer.exception.PdfPasswordException(
                    "This PDF is password-protected. Please provide an unlocked version.");
        } catch (PdfProcessingException | com.pdfanalyzer.exception.PdfPasswordException ex) {
            throw ex;
        } catch (IOException ex) {
            log.error("OCR PDF render IO error: {}", ex.getMessage());
            throw new PdfProcessingException("Failed to render PDF pages for OCR.", ex);
        } catch (Exception ex) {
            log.error("Unexpected OCR failure: {}", ex.getMessage(), ex);
            throw new PdfProcessingException(
                    "OCR processing failed. The document may be corrupted or unsupported.");
        }
    }

    private Tesseract buildTesseract() {
        Tesseract tesseract = new Tesseract();
        tesseract.setDatapath(tessdataPath);
        tesseract.setLanguage("eng");
        tesseract.setPageSegMode(ITessAPI.TessPageSegMode.PSM_AUTO_OSD);
        tesseract.setOcrEngineMode(ITessAPI.TessOcrEngineMode.OEM_LSTM_ONLY);
        return tesseract;
    }

    private BufferedImage preprocessImage(BufferedImage src) {
    int width  = src.getWidth();
    int height = src.getHeight();
    BufferedImage binary = new BufferedImage(width, height, BufferedImage.TYPE_BYTE_GRAY);
    for (int y = 0; y < height; y++) {
        for (int x = 0; x < width; x++) {
            int rgb  = src.getRGB(x, y);
            int r    = (rgb >> 16) & 0xFF;
            int g    = (rgb >> 8)  & 0xFF;
            int b    =  rgb        & 0xFF;
            int luma = (int)(0.299 * r + 0.587 * g + 0.114 * b);
            int out  = (luma > 180) ? 255 : 0;
            binary.setRGB(x, y, (out << 16) | (out << 8) | out);
        }
    }
    return binary;
}
}
