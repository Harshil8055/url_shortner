package com.example.urlshortener.validation;

import java.net.URI;
import java.net.URISyntaxException;

/**
 * Validates that a submitted URL is well-formed.
 *
 * <p>Per requirements: any scheme is accepted (not restricted to http/https) - only
 * structural well-formedness is checked. A URL is considered valid if it parses as a
 * syntactically correct URI and has both a scheme and a host.
 *
 * <p>Stateless utility - safe to call concurrently, no instance state.
 */
public final class UrlValidator {

    private UrlValidator() {
        // utility class, not instantiable
    }

    public static boolean isValid(String url) {
        if (url == null || url.isBlank()) {
            return false;
        }
        try {
            URI uri = new URI(url);
            return uri.getScheme() != null && uri.getHost() != null;
        } catch (URISyntaxException e) {
            return false;
        }
    }
}