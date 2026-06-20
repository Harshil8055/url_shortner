package com.example.urlshortener.repository;

import org.springframework.stereotype.Repository;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory implementation of {@link UrlRepository}, backed by two
 * {@link ConcurrentHashMap}s.
 *
 * <p><b>Why two maps:</b> {@code codeToUrl} serves the redirect path
 * (GET /{code}), and {@code urlToCode} is a reverse index that serves the
 * no-alias dedup check (POST /shorten) in O(1) instead of an O(n) scan over
 * every stored entry.
 *
 * <p><b>Concurrency design:</b> reads ({@link #findByCode}, {@link #findByUrl},
 * {@link #existsByCode}) are lock-free {@code ConcurrentHashMap} reads - this
 * keeps the redirect path (the highest-traffic operation) at full throughput.
 * Writes ({@link #save}) are synchronized as a single critical section across
 * both maps, because {@code ConcurrentHashMap} only guarantees atomicity per
 * individual map operation, not across two separate maps. Without this,
 * concurrent requests shortening the same URL could race past the dedup check
 * and each write a different code for the same URL, breaking the idempotency
 * guarantee.
 *
 * <p><b>Note on alias overwrites:</b> when a custom alias is saved for a URL
 * that already has a different code, {@code urlToCode.put(url, code)} overwrites
 * the reverse-index entry to point at the newest code. This is intentional and
 * harmless: {@code urlToCode} is only consulted by the no-alias dedup path, and a
 * URL is allowed to have multiple valid codes simultaneously (its original code
 * plus any aliases) - the reverse index just tracks "the code we'll return if
 * asked again with no alias," which reasonably becomes the most recent one. Older
 * codes remain fully valid and resolvable via {@code codeToUrl} regardless.
 *
 * <p>All mappings are lost on application restart - an explicit, documented
 * tradeoff for this demo (see requirements doc, Assumptions &amp; Limitations).
 */
@Repository
public class InMemoryUrlRepository implements UrlRepository {

    private final Map<String, String> codeToUrl = new ConcurrentHashMap<>();
    private final Map<String, String> urlToCode = new ConcurrentHashMap<>();
    private final Object writeLock = new Object();

    @Override
    public void save(String code, String url) {
        synchronized (writeLock) {
            codeToUrl.put(code, url);
            urlToCode.put(url, code);
        }
    }

    @Override
    public Optional<String> findByCode(String code) {
        return Optional.ofNullable(codeToUrl.get(code));
    }

    @Override
    public Optional<String> findByUrl(String url) {
        return Optional.ofNullable(urlToCode.get(url));
    }

    @Override
    public boolean existsByCode(String code) {
        return codeToUrl.containsKey(code);
    }
}