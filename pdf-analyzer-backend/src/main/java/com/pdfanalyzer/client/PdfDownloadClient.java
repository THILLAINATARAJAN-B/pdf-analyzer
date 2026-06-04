package com.pdfanalyzer.client;

import com.pdfanalyzer.exception.PdfDownloadException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;

@Slf4j
@Component
public class PdfDownloadClient {

    @Value("${pdf.download.connect-timeout-ms}")
    private int connectTimeoutMs;

    @Value("${pdf.download.read-timeout-ms}")
    private int readTimeoutMs;

    @Value("${pdf.download.max-size-bytes}")
    private long maxSizeBytes;

    private static final int MAX_REDIRECTS = 5;

    public byte[] download(String pdfUrl) {
        log.info("Downloading PDF from URL (truncated)");

        HttpURLConnection connection = null;
        int redirectCount = 0;
        String currentUrl = pdfUrl;

        try {
            while (redirectCount <= MAX_REDIRECTS) {
                URL url = URI.create(currentUrl).toURL();
                connection = (HttpURLConnection) url.openConnection();
                connection.setConnectTimeout(connectTimeoutMs);
                connection.setReadTimeout(readTimeoutMs);
                connection.setRequestMethod("GET");
                connection.setRequestProperty("User-Agent",
                        "Mozilla/5.0 (compatible; PDFAnalyzer/1.0)");
                connection.setInstanceFollowRedirects(false);

                int statusCode = connection.getResponseCode();

                if (statusCode == HttpURLConnection.HTTP_MOVED_TEMP
                        || statusCode == HttpURLConnection.HTTP_MOVED_PERM
                        || statusCode == 307 || statusCode == 308) {
                    String location = connection.getHeaderField("Location");
                    if (location == null || location.isBlank()) {
                        throw new PdfDownloadException("Redirect with no Location header");
                    }
                    connection.disconnect();
                    currentUrl = location;
                    redirectCount++;
                    continue;
                }

                if (statusCode != HttpURLConnection.HTTP_OK) {
                    throw new PdfDownloadException(
                            "Failed to download PDF. HTTP status: " + statusCode);
                }

                validateContentType(connection);

                try (InputStream inputStream = connection.getInputStream()) {
                    byte[] content = inputStream.readAllBytes();
                    if (content.length > maxSizeBytes) {
                        throw new PdfDownloadException(
                                "PDF file exceeds maximum allowed size of 20 MB");
                    }
                    validatePdfSignature(content);
                    return content;
                }
            }

            throw new PdfDownloadException("Too many redirects while downloading PDF");

        } catch (PdfDownloadException e) {
            throw e;
        } catch (IOException e) {
            log.error("IO error during PDF download: {}", e.getMessage());
            throw new PdfDownloadException(
                    "Unable to reach the PDF URL. Check connectivity or URL validity.");
        } catch (Exception e) {
            log.error("Unexpected error during PDF download: {}", e.getMessage());
            throw new PdfDownloadException("Unexpected error while downloading the PDF.");
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private void validateContentType(HttpURLConnection connection) {
        String contentType = connection.getContentType();
        if (contentType != null) {
            String ct = contentType.toLowerCase();
            // Accept PDF MIME or octet-stream (many academic servers send this)
            if (!ct.contains("pdf") && !ct.contains("octet-stream")) {
                log.warn("Unexpected content-type: {}. Proceeding anyway.", contentType);
            }
        }
    }

    private void validatePdfSignature(byte[] content) {
        if (content.length < 5) {
            throw new PdfDownloadException("Downloaded file is too small to be a valid PDF.");
        }
        // Check %PDF- magic bytes
        String header = new String(content, 0, 5);
        if (!header.startsWith("%PDF-")) {
            throw new PdfDownloadException(
                    "The URL does not point to a valid PDF file.");
        }
    }
}