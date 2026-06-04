package com.pdfanalyzer.validation;

import com.pdfanalyzer.exception.InvalidUrlException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.List;

/**
 * Production-grade URL validator with SSRF protection and DNS pinning.
 *
 * Security measures:
 * 1. Scheme whitelist (http/https only)
 * 2. Hostname blocklist (localhost, AWS/GCP metadata endpoints)
 * 3. Private IP range blocking (RFC 1918 + loopback + link-local)
 * 4. DNS resolution check — resolves hostname and validates resolved IP
 *    This prevents DNS rebinding attacks where a hostname passes the
 *    blocklist check but resolves to a private IP at fetch time.
 * 5. URL length cap (2048 chars)
 */
@Slf4j
@Component
public class UrlValidator {

    private static final List<String> ALLOWED_SCHEMES = List.of("http", "https");

    private static final List<String> BLOCKED_HOSTNAMES = List.of(
            "localhost",
            "127.0.0.1",
            "0.0.0.0",
            "::1",
            "169.254.169.254",           // AWS EC2 metadata
            "metadata.google.internal",  // GCP metadata
            "100.100.100.200"            // Alibaba Cloud metadata
    );

    public void validate(String url) {
        if (url == null || url.isBlank()) {
            throw new InvalidUrlException("PDF URL must not be blank.");
        }

        if (url.length() > 2048) {
            throw new InvalidUrlException("URL exceeds maximum allowed length of 2048 characters.");
        }

        URI uri;
        try {
            uri = new URI(url.trim());
        } catch (URISyntaxException ex) {
            throw new InvalidUrlException("The provided URL is malformed or contains invalid characters.");
        }

        validateScheme(uri);
        validateHost(uri);
        validateResolvedIp(uri.getHost());
    }

    /**
     * Re-validates DNS immediately before fetch to narrow the DNS rebinding window.
     * Called at connection time and again for each redirect target.
     */
    public void revalidateBeforeFetch(String url) {
        if (url == null || url.isBlank()) {
            return;
        }
        try {
            URI uri = new URI(url.trim());
            String host = uri.getHost();
            if (host != null && !host.isBlank()) {
                validateResolvedIp(host);
            }
        } catch (URISyntaxException ex) {
            throw new InvalidUrlException("The provided URL is malformed or contains invalid characters.");
        }
    }

    private void validateScheme(URI uri) {
        String scheme = uri.getScheme();
        if (scheme == null || !ALLOWED_SCHEMES.contains(scheme.toLowerCase())) {
            throw new InvalidUrlException(
                    "URL must use HTTP or HTTPS. Provided scheme: " + scheme);
        }
    }

    private void validateHost(URI uri) {
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new InvalidUrlException("URL must contain a valid hostname.");
        }

        String hostLower = host.toLowerCase();

        for (String blocked : BLOCKED_HOSTNAMES) {
            if (hostLower.equals(blocked) || hostLower.endsWith("." + blocked)) {
                log.warn("SSRF attempt blocked — hostname: {}", hostLower);
                throw new InvalidUrlException("Access to internal or metadata addresses is not permitted.");
            }
        }

        if (isPrivateIpRange(host)) {
            log.warn("SSRF attempt blocked — private IP: {}", host);
            throw new InvalidUrlException("Access to private IP address ranges is not permitted.");
        }
    }

    /**
     * DNS pinning: resolve the hostname and re-check the resolved IP.
     * This is the critical step that prevents DNS rebinding attacks —
     * a hostname that looks public at validation time but resolves
     * to a private IP at fetch time.
     */
    private void validateResolvedIp(String hostname) {
        try {
            InetAddress[] addresses = InetAddress.getAllByName(hostname);
            for (InetAddress addr : addresses) {
                String resolvedIp = addr.getHostAddress();
                if (addr.isLoopbackAddress()
                        || addr.isSiteLocalAddress()
                        || addr.isLinkLocalAddress()
                        || addr.isAnyLocalAddress()
                        || isPrivateIpRange(resolvedIp)) {
                    log.warn("SSRF DNS rebinding blocked — hostname={} resolved to private IP={}",
                            hostname, resolvedIp);
                    throw new InvalidUrlException(
                            "The provided URL resolves to a restricted network address.");
                }
            }
        } catch (InvalidUrlException ex) {
            throw ex;
        } catch (UnknownHostException ex) {
            // Unresolvable hostname — reject to prevent unknown access
            log.warn("URL hostname could not be resolved: {}", hostname);
            throw new InvalidUrlException(
                    "The hostname could not be resolved. Please verify the URL.");
        } catch (Exception ex) {
            log.warn("DNS validation error for hostname {}: {}", hostname, ex.getMessage());
            throw new InvalidUrlException("Unable to validate URL hostname. Please try again.");
        }
    }

    private boolean isPrivateIpRange(String ip) {
        if (ip == null) return false;
        return ip.startsWith("10.")
                || ip.startsWith("192.168.")
                || ip.startsWith("172.16.") || ip.startsWith("172.17.")
                || ip.startsWith("172.18.") || ip.startsWith("172.19.")
                || ip.startsWith("172.2")   || ip.startsWith("172.3")
                || ip.startsWith("127.")
                || ip.startsWith("0.")
                || ip.equals("::1")
                || ip.startsWith("fd")      // IPv6 ULA
                || ip.startsWith("fe80");   // IPv6 link-local
    }
}