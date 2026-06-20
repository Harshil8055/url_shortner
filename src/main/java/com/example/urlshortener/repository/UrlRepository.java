package com.example.urlshortener.repository;

import java.util.Optional;

/**
 * Storage contract for code-to-URL mappings.
 *
 * <p>Defined as an interface - even though only one implementation
 * ({@link InMemoryUrlRepository}) currently exists - so a future swap to a real
 * datastore (Postgres, Redis, etc.) only requires a new class implementing this
 * contract. The service layer depends only on this interface and is unaffected
 * by such a swap.
 */
public interface UrlRepository {

    /**
     * Persists a code-to-URL mapping. If {@code code} already maps to an entry,
     * implementations should overwrite it (callers are expected to check
     * {@link #existsByCode(String)} first where "already taken" should be an error
     * rather than a silent overwrite - see {@code UrlShortenerService}).
     */
    void save(String code, String url);

    /**
     * Looks up the original URL for a given short code. Used by the redirect path.
     */
    Optional<String> findByCode(String code);

    /**
     * Looks up the existing code (if any) for a given URL. Used only by the
     * no-alias dedup path - exact string match, no normalization.
     */
    Optional<String> findByUrl(String url);

    /**
     * Checks whether a code is already in use, regardless of which URL it maps to.
     * Used both for collision-checking generated codes and for alias-conflict
     * checking.
     */
    boolean existsByCode(String code);
}