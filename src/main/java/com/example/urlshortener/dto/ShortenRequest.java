package com.example.urlshortener.dto;

/**
 * Request body for POST /shorten.
 *
 * {@code url} is required. {@code alias} is optional - if omitted or blank,
 * the service generates a random short code instead.
 */
public class ShortenRequest {

    private String url;
    private String alias;

    public ShortenRequest() {
        // required no-arg constructor for Jackson deserialization
    }

    public ShortenRequest(String url, String alias) {
        this.url = url;
        this.alias = alias;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public String getAlias() {
        return alias;
    }

    public void setAlias(String alias) {
        this.alias = alias;
    }
}