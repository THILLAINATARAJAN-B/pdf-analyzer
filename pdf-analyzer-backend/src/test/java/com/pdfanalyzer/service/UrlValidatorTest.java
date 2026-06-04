// src/test/java/com/pdfanalyzer/service/UrlValidatorTest.java
package com.pdfanalyzer.service;

import com.pdfanalyzer.exception.InvalidUrlException;
import com.pdfanalyzer.validation.UrlValidator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatNoException;

@SpringBootTest
@ActiveProfiles("test")
@DisplayName("UrlValidator — SSRF and DNS Pinning Tests")
class UrlValidatorTest {

    @Autowired
    private UrlValidator urlValidator;

    @Test
    @DisplayName("Should reject localhost URL")
    void rejectsLocalhost() {
        assertThatThrownBy(() -> urlValidator.validate("http://localhost/evil.pdf"))
                .isInstanceOf(InvalidUrlException.class);
    }

    @Test
    @DisplayName("Should reject AWS metadata endpoint")
    void rejectsAwsMetadata() {
        assertThatThrownBy(() -> urlValidator.validate("http://169.254.169.254/latest/meta-data/"))
                .isInstanceOf(InvalidUrlException.class);
    }

    @Test
    @DisplayName("Should reject private IP range 192.168.x.x")
    void rejectsPrivateIp() {
        assertThatThrownBy(() -> urlValidator.validate("http://192.168.1.1/file.pdf"))
                .isInstanceOf(InvalidUrlException.class);
    }

    @Test
    @DisplayName("Should reject non-http scheme (ftp)")
    void rejectsFtpScheme() {
        assertThatThrownBy(() -> urlValidator.validate("ftp://example.com/file.pdf"))
                .isInstanceOf(InvalidUrlException.class);
    }

    @Test
    @DisplayName("Should reject blank URL")
    void rejectsBlankUrl() {
        assertThatThrownBy(() -> urlValidator.validate(""))
                .isInstanceOf(InvalidUrlException.class);
    }

    @Test
    @DisplayName("Should accept valid HTTPS public URL")
    void acceptsValidHttpsUrl() {
        // Uses a real public domain — DNS pinning will resolve it
        assertThatNoException()
                .isThrownBy(() -> urlValidator.validate("https://www.w3.org/WAI/WCAG21/wcag21.pdf"));
    }
}