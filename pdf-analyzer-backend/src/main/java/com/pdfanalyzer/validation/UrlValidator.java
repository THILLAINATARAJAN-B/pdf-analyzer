package com.pdfanalyzer.validation;

import com.pdfanalyzer.exception.InvalidUrlException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;

@Slf4j
@Component
public class UrlValidator {

    private static final List<String> ALLOWED_SCHEMES = List.of("http", "https");

    // Block private/internal network ranges (SSRF prevention)
    private static final List<String> BLOCKED_HOSTS = List.of(
            "localhost", "127.0.0.1", "0.0.0.0", "::1",
            "169.254.169.254",   // AWS metadata
            "metadata.google.internal"
    );

    public void validate(String url) {
        if (url == null || url.isBlank()) {
            throw new InvalidUrlException("PDF URL must not be blank.");
        }

        URI uri;
        try {
            uri = new URI(url.trim());
        } catch (URISyntaxException e) {
            throw new InvalidUrlException("The provided URL is malformed.");
        }

        String scheme = uri.getScheme();
        if (scheme == null || !ALLOWED_SCHEMES.contains(scheme.toLowerCase())) {
            throw new InvalidUrlException(
                    "URL must use HTTP or HTTPS scheme. Provided: " + scheme);
        }

        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new InvalidUrlException("URL must contain a valid host.");
        }

        // SSRF guard: block internal/private hosts
        String hostLower = host.toLowerCase();
        for (String blocked : BLOCKED_HOSTS) {
            if (hostLower.equals(blocked) || hostLower.endsWith("." + blocked)) {
                log.warn("SSRF attempt blocked for host: {}", hostLower);
                throw new InvalidUrlException("Access to internal network addresses is not allowed.");
            }
        }

        // Block private IP ranges
        if (isPrivateIpRange(host)) {
            log.warn("SSRF attempt blocked for private IP: {}", host);
            throw new InvalidUrlException("Access to private IP ranges is not allowed.");
        }

        // Validate URL is not excessively long
        if (url.length() > 2048) {
            throw new InvalidUrlException("URL exceeds maximum allowed length of 2048 characters.");
        }

        log.debug("URL validation passed");
    }

    private boolean isPrivateIpRange(String host) {
        try {
            // Simple prefix checks for private IPv4 ranges
            return host.startsWith("10.")
                    || host.startsWith("192.168.")
                    || host.startsWith("172.16.")
                    || host.startsWith("172.17.")
                    || host.startsWith("172.18.")
                    || host.startsWith("172.19.")
                    || host.startsWith("172.2")
                    || host.startsWith("172.3");
        } catch (Exception e) {
            return false;
        }
    }
}