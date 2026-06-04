package com.pdfanalyzer.client;

import com.pdfanalyzer.exception.PdfDownloadException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

@Slf4j
@Component
public class PdfDownloadClient {

    @Value("${pdf.download.connect-timeout-ms}")
    private int connectTimeoutMs;

    @Value("${pdf.download.read-timeout-ms}")
    private int readTimeoutMs;

    @Value("${pdf.download.max-size-bytes}")
    private long maxSizeBytes;

    public byte[] download(String pdfUrl) {

        log.info("Starting PDF download");

        try {

            HttpClient client = HttpClient.newBuilder()
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .connectTimeout(Duration.ofMillis(connectTimeoutMs))
                    .build();

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(pdfUrl))
                    .timeout(Duration.ofMillis(readTimeoutMs))
                    .header("User-Agent", "PDFAnalyzer/1.0")
                    .GET()
                    .build();

            HttpResponse<byte[]> response =
                    client.send(
                            request,
                            HttpResponse.BodyHandlers.ofByteArray()
                    );

            if (response.statusCode() != 200) {
                throw new PdfDownloadException(
                        "Failed to download PDF. HTTP status: "
                                + response.statusCode()
                );
            }

            byte[] content = response.body();

            if (content == null || content.length == 0) {
                throw new PdfDownloadException(
                        "Downloaded PDF is empty."
                );
            }

            if (content.length > maxSizeBytes) {
                throw new PdfDownloadException(
                        "PDF exceeds maximum allowed size of "
                                + (maxSizeBytes / (1024 * 1024))
                                + " MB."
                );
            }

            validatePdfSignature(content);

            log.info(
                    "PDF downloaded successfully. Size: {} bytes",
                    content.length
            );

            return content;

        } catch (PdfDownloadException ex) {

            throw ex;

        } catch (IllegalArgumentException ex) {

            log.error("Invalid PDF URL format", ex);

            throw new PdfDownloadException(
                    "Invalid PDF URL format."
            );

        } catch (Exception ex) {

            log.error("PDF download failed", ex);

            throw new PdfDownloadException(
                    "Unable to download PDF from supplied URL."
            );
        }
    }

    private void validatePdfSignature(byte[] content) {

        if (content.length < 5) {
            throw new PdfDownloadException(
                    "Downloaded file is too small to be a valid PDF."
            );
        }

        String header = new String(content, 0, 5);

        if (!header.startsWith("%PDF-")) {
            throw new PdfDownloadException(
                    "The supplied URL does not point to a valid PDF file."
            );
        }
    }
}