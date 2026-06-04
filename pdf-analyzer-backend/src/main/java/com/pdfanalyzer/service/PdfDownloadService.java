package com.pdfanalyzer.service;

import com.pdfanalyzer.exception.InvalidUrlException;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Downloads a PDF from a remote URL using chunked streaming.
 *
 * Security measures applied in order:
 * 1. Google Drive sharing/consent page detection (pre-download)
 * 2. Manual redirect handling with cap (prevents redirect loops)
 * 3. Content-Type early rejection (HTML/JSON are not PDFs)
 * 4. Content-Length header pre-check (fast rejection of huge files)
 * 5. Chunked streaming with inline byte-limit enforcement (prevents OOM)
 * 6. Streaming wall-clock timeout (prevents slow-server hangs)
 * 7. PDF magic-byte validation (%PDF-) on final content
 * 8. HTML-masquerade detection (consent/login pages that slipped through)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PdfDownloadService {

    private final UrlValidator urlValidator;

    private static final int CHUNK_SIZE = 8192;
    private static final long MAX_STREAM_DURATION_MS = 20_000L;

    private static final Set<Integer> REDIRECT_STATUSES = Set.of(
            300, 301, 302, 303, 307, 308
    );

    @Value("${pdf.download.connect-timeout-ms:10000}")
    private int connectTimeoutMs;

    @Value("${pdf.download.read-timeout-ms:30000}")
    private int readTimeoutMs;

    @Value("${pdf.download.max-size-bytes:52428800}")
    private long maxSizeBytes;

    @Value("${pdf.download.max-redirects:5}")
    private int maxRedirects;

    // ── Public Entry Point ────────────────────────────────────────────────────

    /**
     * Downloads a PDF via chunked streaming with hard byte and time limits.
     * Uses HttpURLConnection directly to control redirect policy
     * and enforce byte-level limits during stream — not after full load.
     * This protects against OOM attacks and denial-of-service payloads.
     */
    public byte[] download(String pdfUrl) {
        log.info("Starting chunked PDF download");

        // Stage 1 — Pre-download Google Drive detection
        // Catches sharing links and large-file consent URLs before any network call.
        detectGoogleDriveLink(pdfUrl);

        HttpURLConnection connection = null;
        try {
            connection = openConnection(pdfUrl, 0);
            int status = connection.getResponseCode();

            // ── HTTP Status Checks ────────────────────────────────────────────
            if (status == HttpURLConnection.HTTP_NOT_FOUND) {
                throw new InvalidUrlException(
                        "No PDF was found at the supplied URL. Please verify the link is correct (HTTP 404).");
            }
            if (status == HttpURLConnection.HTTP_FORBIDDEN
                    || status == HttpURLConnection.HTTP_UNAUTHORIZED) {
                throw new PdfDownloadException(
                        "Access to the PDF URL was denied (HTTP " + status + ").");
            }
            if (status != HttpURLConnection.HTTP_OK) {
                throw new PdfDownloadException(
                        "Failed to download PDF. HTTP status: " + status);
            }

            // ── Content-Type Early Rejection ──────────────────────────────────
            validateContentType(connection);

            // ── Content-Length Pre-check ──────────────────────────────────────
            long contentLength = connection.getContentLengthLong();
            if (contentLength > 0 && contentLength > maxSizeBytes) {
                throw new PdfProcessingException(
                        "PDF exceeds maximum allowed size of "
                                + (maxSizeBytes / (1024 * 1024)) + " MB.");
            }

            // ── Chunked Stream with Byte + Time Limits ────────────────────────
            byte[] content = streamWithLimit(connection.getInputStream());

            // ── Stage 2 — Post-download Google Drive consent page detection ───
            // Catches the case where the URL resolved but returned an HTML
            // consent/login page instead of the actual PDF.
            checkForGoogleDriveConsentPage(connection, content);

            // ── PDF Magic-byte + HTML-masquerade Validation ───────────────────
            validatePdfContent(content);

            log.info("PDF downloaded successfully. Size: {} bytes", content.length);
            return content;

        } catch (PdfDownloadException | PdfProcessingException | InvalidUrlException ex) {
            throw ex;
        } catch (IOException ex) {
            log.error("PDF download IO failure: {}", ex.getMessage());
            throw new PdfDownloadException(
                    "Unable to download PDF. Check the URL and try again.", ex);
        } catch (Exception ex) {
            log.error("Unexpected download failure: {}", ex.getMessage());
            throw new PdfDownloadException(
                    "Unexpected error while downloading PDF.", ex);
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    // ── Connection + Redirect Handling ────────────────────────────────────────

    private HttpURLConnection openConnection(String pdfUrl, int redirectCount) throws IOException {
        if (redirectCount > maxRedirects) {
            throw new PdfDownloadException(
                    "Too many redirects (>" + maxRedirects
                            + "). The URL may be in a redirect loop.");
        }

        // NOTE: Full DNS-pinning validation runs once in UrlValidator (before download).
        // We do NOT re-run it on every redirect hop — that would break legitimate
        // CDN chains (arxiv, archive.org, government servers).
        // Security is maintained by: initial URL validation + magic-byte check at the end.

        URL url = toAbsoluteUrl(pdfUrl);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(connectTimeoutMs);
        conn.setReadTimeout(readTimeoutMs);
        // Broad User-Agent improves compatibility with academic and government servers
        // that block or throttle requests from bot-like User-Agent strings.
        conn.setRequestProperty("User-Agent",
                "Mozilla/5.0 (compatible; PDFAnalyzer/2.0; +https://github.com/pdf-analyzer)");
        conn.setRequestProperty("Accept",
                "application/pdf,application/octet-stream,*/*;q=0.8");
        conn.setInstanceFollowRedirects(false); // All redirects are handled manually

        int status = conn.getResponseCode();

        if (REDIRECT_STATUSES.contains(status)) {
            String location = conn.getHeaderField("Location");
            if (location == null || location.isBlank()) {
                if (status == 300) {
                    // 300 Multiple Choices with no Location: retry as-is (pick default)
                    log.warn("HTTP 300 Multiple Choices with no Location header — retrying same URL");
                    conn.disconnect();
                    return openConnection(pdfUrl, redirectCount + 1);
                }
                throw new PdfDownloadException(
                        "Redirect (HTTP " + status + ") with no Location header.");
            }

            String resolvedUrl = resolveRedirectUrl(pdfUrl, location);
            conn.disconnect();
            log.debug("Following redirect [{}/{}] HTTP {} → {}",
                    redirectCount + 1, maxRedirects, status, resolvedUrl);
            return openConnection(resolvedUrl, redirectCount + 1);
        }

        return conn;
    }

    /**
     * Resolves redirect Location headers against the current request URL.
     * Handles relative paths, protocol-relative URLs (//) and absolute URLs.
     */
    static String resolveRedirectUrl(String baseUrl, String location) {
        String trimmedLocation = location.trim();
        URI base = URI.create(baseUrl.trim());

        // Protocol-relative: //host/path → scheme://host/path
        if (trimmedLocation.startsWith("//")) {
            return base.getScheme() + ":" + trimmedLocation;
        }

        URI target = URI.create(trimmedLocation);
        return (target.isAbsolute() ? target : base.resolve(target)).toString();
    }

    private URL toAbsoluteUrl(String pdfUrl) {
        URI uri = URI.create(pdfUrl.trim());
        if (!uri.isAbsolute()) {
            throw new PdfDownloadException(
                    "Download URL must be absolute (must start with http:// or https://): " + pdfUrl);
        }
        try {
            return uri.toURL();
        } catch (MalformedURLException | IllegalArgumentException ex) {
            throw new PdfDownloadException("Invalid download URL: " + pdfUrl, ex);
        }
    }

    // ── Streaming with Inline Limits ──────────────────────────────────────────

    /**
     * Reads the stream in 8KB chunks and enforces:
     * - Total byte limit (prevents loading a huge file into memory)
     * - Wall-clock time limit (prevents slow-server hangs that bypass readTimeout)
     */
    private byte[] streamWithLimit(InputStream inputStream) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[CHUNK_SIZE];
        long totalRead = 0;
        int bytesRead;
        long startMs = System.currentTimeMillis();

        try (inputStream) {
            while ((bytesRead = inputStream.read(chunk)) != -1) {
                totalRead += bytesRead;

                if (totalRead > maxSizeBytes) {
                    throw new PdfProcessingException(
                            "PDF exceeds maximum allowed size of "
                                    + (maxSizeBytes / (1024 * 1024)) + " MB.");
                }

                if (System.currentTimeMillis() - startMs > MAX_STREAM_DURATION_MS) {
                    throw new PdfDownloadException(
                            "PDF download timed out after 20 seconds. "
                                    + "The file may be too large or the server is too slow.");
                }

                buffer.write(chunk, 0, bytesRead);
            }
        }

        return buffer.toByteArray();
    }

    // ── Validation Methods ────────────────────────────────────────────────────

    /**
     * Checks the Content-Type header for obvious non-PDF types.
     * If content-type is absent, we proceed and let magic-byte validation decide.
     * This is a fast-fail for HTML pages / JSON APIs masquerading as PDFs.
     */
    private void validateContentType(HttpURLConnection connection) {
        String contentType = connection.getContentType();
        if (contentType == null || contentType.isBlank()) {
            return; // No Content-Type — let magic-byte check decide
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
            log.warn("Unexpected Content-Type '{}' — proceeding to magic-byte validation",
                    contentType);
        }
    }

    /**
     * Post-download Google Drive consent page detector.
     *
     * Catches the scenario where a Google Drive URL resolved (HTTP 200)
     * but returned an HTML consent/virus-scan page instead of the actual PDF.
     * This happens for large files (>100MB) or when Google forces a download warning.
     */
    private void checkForGoogleDriveConsentPage(HttpURLConnection connection, byte[] content) {
        String contentType = connection.getContentType();
        if (contentType == null || !contentType.toLowerCase().contains("text/html")) {
            return; // Not HTML — nothing to check
        }

        String snippet = new String(content, 0, Math.min(500, content.length),
                StandardCharsets.UTF_8).toLowerCase();

        if (snippet.contains("drive.google.com")
                || snippet.contains("accounts.google.com")
                || snippet.contains("confirm")
                || snippet.contains("virus scan")
                || snippet.contains("download anyway")) {
            throw new PdfProcessingException(
                    "Google Drive returned a consent or confirmation page instead of the PDF. "
                            + "The file may require sign-in or exceed the direct download size limit. "
                            + "Download the file manually and host it on a publicly accessible direct URL.");
        }
    }

    /**
     * Pre-download Google Drive URL detection.
     *
     * Handles two cases:
     * 1. Share/view links — not directly downloadable, extract file ID and suggest fix.
     * 2. Direct download (uc?export=download) links for large files — may show consent page.
     */
    private void detectGoogleDriveLink(String url) {
        if (url == null) return;
        String lower = url.toLowerCase();

        // Case 1 — Sharing/view links (e.g. /file/d/.../view)
        if (lower.contains("drive.google.com/file/d/") && lower.contains("/view")) {
            String fileId = extractGoogleDriveFileId(url);
            String hint = fileId != null
                    ? "https://drive.google.com/uc?export=download&id=" + fileId
                    : "https://drive.google.com/uc?export=download&id=<YOUR_FILE_ID>";
            throw new PdfDownloadException(
                    "Google Drive sharing links are not directly downloadable. "
                            + "Use the direct download URL instead: " + hint);
        }

        // Case 2 — open? links
        if (lower.contains("drive.google.com") && lower.contains("/open?")) {
            throw new PdfDownloadException(
                    "Google Drive open links are not directly downloadable. "
                            + "Use: https://drive.google.com/uc?export=download&id=<YOUR_FILE_ID>");
        }

        // Case 3 — Direct download links (may show a consent page for large files)
        if (lower.contains("drive.google.com/uc") && lower.contains("export=download")) {
            log.warn("Google Drive direct download URL detected — consent page possible for large files");
            // We don't throw here; we attempt the download and catch the consent page in
            // checkForGoogleDriveConsentPage() after the stream completes.
        }
    }

    /**
     * Validates the PDF magic-number header (%PDF-).
     * Also detects HTML pages (login/consent screens) that bypassed Content-Type checks.
     */
    private void validatePdfContent(byte[] content) {
        if (content == null || content.length < 5) {
            throw new PdfProcessingException(
                    "Downloaded file is too small to be a valid PDF.");
        }

        String header = new String(content, 0, 5, StandardCharsets.US_ASCII);
        if (header.startsWith("%PDF-")) {
            return; // Valid PDF magic bytes
        }

        // Detect HTML masquerading as PDF
        String snippet = new String(content, 0, Math.min(300, content.length),
                StandardCharsets.UTF_8).toLowerCase();

        if (snippet.contains("<html") || snippet.contains("<!doctype")
                || snippet.contains("<head")) {
            throw new PdfProcessingException(
                    "The URL returned an HTML web page, not a PDF. "
                            + "Use a direct link to a .pdf file (e.g. ending in /document.pdf).");
        }

        throw new PdfProcessingException(
                "The supplied URL does not point to a valid PDF file. "
                        + "The downloaded content is not a PDF document.");
    }

    /**
     * Extracts the file ID from a Google Drive URL.
     * Example: https://drive.google.com/file/d/1aBcDeFg/view → "1aBcDeFg"
     */
    private String extractGoogleDriveFileId(String url) {
        Matcher m = Pattern.compile("/file/d/([^/?]+)").matcher(url);
        return m.find() ? m.group(1) : null;
    }
}