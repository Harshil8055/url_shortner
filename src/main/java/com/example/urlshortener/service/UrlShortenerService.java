package com.example.urlshortener.service;

import com.example.urlshortener.dto.ShortenResponse;
import com.example.urlshortener.repository.UrlRepository;
import com.example.urlshortener.validation.AliasValidator;
import com.example.urlshortener.validation.UrlValidator;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * Orchestrates URL shortening and redirect lookups. Returns {@link ServiceResult}
 * from every public method instead of throwing exceptions for expected business
 * outcomes - see {@link ServiceResult} for the full rationale.
 */
@Service
public class UrlShortenerService {

    private static final int MAX_GENERATION_RETRIES = 5;

    private final UrlRepository repository;
    private final ShortCodeGenerator generator;

    public UrlShortenerService(UrlRepository repository, ShortCodeGenerator generator) {
        this.repository = repository;
        this.generator = generator;
    }

    /**
     * Handles POST /shorten.
     *
     * @param url     the long URL to shorten (required)
     * @param alias   an optional custom alias; null or blank means "generate one"
     * @param baseUrl the scheme+host(+port) of the incoming request, used to build
     *                the full short URL in the response (e.g. "http://localhost:8080")
     */
    public ServiceResult<ShortenResponse> shorten(String url, String alias, String baseUrl) {
        if (!UrlValidator.isValid(url)) {
            return ServiceResult.invalidUrl("The provided URL is not well-formed.");
        }

        if (alias != null && !alias.isBlank()) {
            return shortenWithAlias(url, alias, baseUrl);
        }
        return shortenWithGeneratedCode(url, baseUrl);
    }

    private ServiceResult<ShortenResponse> shortenWithAlias(String url, String alias, String baseUrl) {
        if (!AliasValidator.isValid(alias)) {
            return ServiceResult.invalidAlias(
                    "Alias must be 1-64 URL-safe characters (A-Z, a-z, 0-9, -, _, ., ~).");
        }
        if (repository.existsByCode(alias)) {
            return ServiceResult.aliasConflict("Alias '" + alias + "' is already taken.");
        }
        repository.save(alias, url);
        return ServiceResult.success(buildResponse(alias, url, baseUrl));
    }

    private ServiceResult<ShortenResponse> shortenWithGeneratedCode(String url, String baseUrl) {
        // Idempotency: if this exact URL was already shortened (no alias), return
        // the existing code rather than minting a new one.
        Optional<String> existingCode = repository.findByUrl(url);
        if (existingCode.isPresent()) {
            return ServiceResult.success(buildResponse(existingCode.get(), url, baseUrl));
        }

        for (int attempt = 0; attempt < MAX_GENERATION_RETRIES; attempt++) {
            String candidate = generator.generate();
            if (!repository.existsByCode(candidate)) {
                repository.save(candidate, url);
                return ServiceResult.success(buildResponse(candidate, url, baseUrl));
            }
        }
        return ServiceResult.generationFailed(
                "Could not generate a unique short code after " + MAX_GENERATION_RETRIES + " attempts.");
    }

    /**
     * Handles GET /{code}. Returns the original URL on success (data = the URL
     * string), or a NOT_FOUND result if the code is unknown.
     */
    public ServiceResult<String> redirect(String code) {
        return repository.findByCode(code)
                .map(ServiceResult::success)
                .orElseGet(() -> ServiceResult.notFound("No URL found for code '" + code + "'."));
    }

    private ShortenResponse buildResponse(String code, String url, String baseUrl) {
        return new ShortenResponse(code, baseUrl + "/" + code, url);
    }
}