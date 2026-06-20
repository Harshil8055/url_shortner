package com.example.urlshortener.dto;

/**
 * Success response body for POST /shorten.
 * Immutable - constructed once with all fields populated.
 */
public class ShortenResponse {

    private final String code;
    private final String shortUrl;
    private final String originalUrl;

    public ShortenResponse(String code, String shortUrl, String originalUrl) {
        this.code = code;
        this.shortUrl = shortUrl;
        this.originalUrl = originalUrl;
    }

    public String getCode() {
        return code;
    }

    public String getShortUrl() {
        return shortUrl;
    }

    public String getOriginalUrl() {
        return originalUrl;
    }
}