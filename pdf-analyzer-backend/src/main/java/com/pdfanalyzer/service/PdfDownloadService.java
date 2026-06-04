package com.pdfanalyzer.service;

import com.pdfanalyzer.exception.PdfDownloadException;
import com.pdfanalyzer.exception.PdfProcessingException;
import com.pdfanalyzer.validation.UrlValidator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class PdfDownloadService {

    private final UrlValidator urlValidator;

    private static final int CHUNK_SIZE = 8192;
    private static final Set<Integer> RETRYABLE_STATUS = Set.of(429, 500, 502, 503, 504);

    @Value("${pdf.download.connect-timeout-ms:10000}")
    private int connectTimeoutMs;

    @Value("${pdf.download.read-timeout-ms:30000}")
    private int readTimeoutMs;

    @Value("${pdf.download.max-size-bytes:52428800}")
    private long maxSizeBytes;

    @Value("${pdf.download.max-redirects:3}")
    private int maxRedirects;

    /**
     * Downloads a PDF via chunked streaming with hard byte limit.
     * Uses HttpURLConnection directly to control redirect policy
     * and enforce byte-level limits during stream — not after full load.
     * This protects against OOM attacks and denial-of-service payloads.
     */
    public byte[] download(String pdfUrl) {
        log.info("Starting chunked PDF download");

        HttpURLConnection connection = null;
        try {
            connection = openConnection(pdfUrl, 0);
            int status = connection.getResponseCode();

            if (status == HttpURLConnection.HTTP_NOT_FOUND) {
                throw new PdfDownloadException("PDF not found at the supplied URL (404).");
            }
            if (status == HttpURLConnection.HTTP_FORBIDDEN || status == HttpURLConnection.HTTP_UNAUTHORIZED) {
                throw new PdfDownloadException("Access to the PDF URL was denied (HTTP " + status + ").");
            }
            if (status != HttpURLConnection.HTTP_OK) {
                throw new PdfDownloadException("Failed to download PDF. HTTP status: " + status);
            }

            validateContentType(connection);

            // Check Content-Length header as early rejection — not a security check alone
            long contentLength = connection.getContentLengthLong();
            if (contentLength > maxSizeBytes) {
                throw new PdfProcessingException(
                        "PDF exceeds maximum allowed size of " + (maxSizeBytes / (1024 * 1024)) + " MB.");
            }

            byte[] content = streamWithLimit(connection.getInputStream());
            validatePdfContent(content);

            log.info("PDF downloaded successfully. Size: {} bytes", content.length);
            return content;

        } catch (PdfDownloadException | PdfProcessingException ex) {
            throw ex;
        } catch (IOException ex) {
            log.error("PDF download IO failure: {}", ex.getMessage());
            throw new PdfDownloadException("Unable to download PDF. Check the URL and try again.", ex);
        } catch (Exception ex) {
            log.error("Unexpected download failure: {}", ex.getMessage());
            throw new PdfDownloadException("Unexpected error while downloading PDF.", ex);
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private HttpURLConnection openConnection(String pdfUrl, int redirectCount) throws IOException {
        if (redirectCount > maxRedirects) {
            throw new PdfDownloadException("Too many redirects. Maximum allowed: " + maxRedirects);
        }

        urlValidator.revalidateBeforeFetch(pdfUrl);

        URL url = toAbsoluteUrl(pdfUrl);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(connectTimeoutMs);
        conn.setReadTimeout(readTimeoutMs);
        conn.setRequestProperty("User-Agent", "PDFAnalyzer/2.0");
        conn.setInstanceFollowRedirects(false); // We handle redirects manually

        int status = conn.getResponseCode();
        if (status == HttpURLConnection.HTTP_MOVED_TEMP
                || status == HttpURLConnection.HTTP_MOVED_PERM
                || status == 307 || status == 308) {
            String location = conn.getHeaderField("Location");
            if (location == null || location.isBlank()) {
                throw new PdfDownloadException("Redirect with no Location header.");
            }
            String resolvedUrl = resolveRedirectUrl(pdfUrl, location);
            urlValidator.validate(resolvedUrl);
            conn.disconnect();
            log.debug("Following redirect [{}/{}] to: {}", redirectCount + 1, maxRedirects, resolvedUrl);
            return openConnection(resolvedUrl, redirectCount + 1);
        }

        return conn;
    }

    /**
     * Resolves redirect Location headers against the current request URL.
     * Servers often return relative paths (e.g. {@code /pdf/123}) or
     * protocol-relative URLs (e.g. {@code //host/path}) which are not
     * valid inputs to {@link URI#toURL()} on their own.
     */
    static String resolveRedirectUrl(String baseUrl, String location) {
        String trimmedLocation = location.trim();
        URI base = URI.create(baseUrl.trim());

        if (trimmedLocation.startsWith("//")) {
            return base.getScheme() + ":" + trimmedLocation;
        }

        URI target = URI.create(trimmedLocation);
        return (target.isAbsolute() ? target : base.resolve(target)).toString();
    }

    private URL toAbsoluteUrl(String pdfUrl) {
        URI uri = URI.create(pdfUrl.trim());
        if (!uri.isAbsolute()) {
            throw new PdfDownloadException("Download URL must be absolute: " + pdfUrl);
        }
        try {
            return uri.toURL();
        } catch (MalformedURLException | IllegalArgumentException ex) {
            throw new PdfDownloadException("Invalid download URL: " + pdfUrl, ex);
        }
    }

    /**
     * Reads the stream in chunks and enforces the byte limit inline.
     * This prevents loading a multi-GB file fully into memory before checking size.
     */
    private byte[] streamWithLimit(InputStream inputStream) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[CHUNK_SIZE];
        long totalRead = 0;
        int bytesRead;

        try (inputStream) {
            while ((bytesRead = inputStream.read(chunk)) != -1) {
                totalRead += bytesRead;
                if (totalRead > maxSizeBytes) {
                    throw new PdfProcessingException(
                            "PDF exceeds maximum allowed size of " + (maxSizeBytes / (1024 * 1024)) + " MB.");
                }
                buffer.write(chunk, 0, bytesRead);
            }
        }

        return buffer.toByteArray();
    }

    private void validateContentType(HttpURLConnection connection) {
        String contentType = connection.getContentType();
        if (contentType == null || contentType.isBlank()) {
            return;
        }

        String lower = contentType.toLowerCase();
        if (lower.contains("text/html") || lower.contains("text/plain")) {
            throw new PdfProcessingException(
                    "The URL returned a web page or plain text, not a PDF. "
                            + "Verify the link points directly to a PDF file.");
        }
        if (lower.contains("application/json") || lower.contains("application/xml")) {
            throw new PdfProcessingException(
                    "The URL returned structured data (JSON/XML), not a PDF file.");
        }
        if (!lower.contains("application/pdf")
                && !lower.contains("application/octet-stream")
                && !lower.contains("binary")) {
            log.warn("Unexpected Content-Type: {} — verifying PDF magic bytes", contentType);
        }
    }

    /**
     * Validates PDF magic-number header (%PDF-) and detects common non-PDF payloads.
     */
    private void validatePdfContent(byte[] content) {
        if (content == null || content.length < 5) {
            throw new PdfProcessingException("Downloaded file is too small to be a valid PDF.");
        }

        String header = new String(content, 0, 5, StandardCharsets.US_ASCII);
        if (header.startsWith("%PDF-")) {
            return;
        }

        String snippet = new String(content, 0, Math.min(300, content.length), StandardCharsets.UTF_8)
                .toLowerCase();
        if (snippet.contains("<html") || snippet.contains("<!doctype") || snippet.contains("<head")) {
            throw new PdfProcessingException(
                    "The URL returned an HTML web page, not a PDF. "
                            + "Use a direct link to a .pdf file (e.g. ending in /document.pdf).");
        }

        throw new PdfProcessingException(
                "The supplied URL does not point to a valid PDF file. "
                        + "The downloaded content is not a PDF document.");
    }
}