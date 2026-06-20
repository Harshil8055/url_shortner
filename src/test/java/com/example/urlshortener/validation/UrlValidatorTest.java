package com.example.urlshortener.validation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class UrlValidatorTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "https://example.com",
            "http://example.com/path?query=1",
            "ftp://files.example.com",
            "https://example.com/page/",          // trailing slash - still valid
            "https://example.com:8080/path",      // explicit port
            "custom-scheme://example.com/x"       // any scheme accepted per requirements
    })
    void acceptsWellFormedUrlsWithAnyScheme(String url) {
        assertThat(UrlValidator.isValid(url)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "not a url",
            "/just/a/path",
            "mailto:someone@example.com",   // has scheme but no host
            "://missing-scheme.com",
            "   "
    })
    void rejectsMalformedOrIncompleteUrls(String url) {
        assertThat(UrlValidator.isValid(url)).isFalse();
    }

    @ParameterizedTest
    @NullAndEmptySource
    void rejectsNullAndEmpty(String url) {
        assertThat(UrlValidator.isValid(url)).isFalse();
    }

    @Test
    void rejectsBlankWhitespaceOnly() {
        assertThat(UrlValidator.isValid("    ")).isFalse();
    }
}
