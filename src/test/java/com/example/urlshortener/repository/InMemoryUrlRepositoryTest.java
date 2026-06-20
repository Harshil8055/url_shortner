package com.example.urlshortener.repository;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryUrlRepositoryTest {

    private final InMemoryUrlRepository repository = new InMemoryUrlRepository();

    @Test
    void savedMappingIsRetrievableByCode() {
        repository.save("abc1234", "https://example.com");

        Optional<String> result = repository.findByCode("abc1234");

        assertThat(result).contains("https://example.com");
    }

    @Test
    void savedMappingIsRetrievableByUrl() {
        repository.save("abc1234", "https://example.com");

        Optional<String> result = repository.findByUrl("https://example.com");

        assertThat(result).contains("abc1234");
    }

    @Test
    void findByCodeReturnsEmptyForUnknownCode() {
        assertThat(repository.findByCode("doesNotExist")).isEmpty();
    }

    @Test
    void findByUrlReturnsEmptyForUnseenUrl() {
        assertThat(repository.findByUrl("https://never-shortened.com")).isEmpty();
    }

    @Test
    void existsByCodeReflectsSavedState() {
        assertThat(repository.existsByCode("xyz9999")).isFalse();

        repository.save("xyz9999", "https://example.com");

        assertThat(repository.existsByCode("xyz9999")).isTrue();
    }

    @Test
    void urlCanHaveMultipleCodesSimultaneously() {
        // One URL, two different codes (e.g. an auto-generated code plus a
        // custom alias) - both must remain independently resolvable.
        repository.save("auto123", "https://example.com/page");
        repository.save("custom-alias", "https://example.com/page");

        assertThat(repository.findByCode("auto123")).contains("https://example.com/page");
        assertThat(repository.findByCode("custom-alias")).contains("https://example.com/page");
    }

    @Test
    void reverseIndexPointsToMostRecentCodeWhenUrlHasMultipleCodes() {
        // Per LLD §6: urlToCode is overwritten by the latest save() for that URL.
        // This is documented as intentional - the reverse index just tracks
        // "the code we'd return on a no-alias dedup hit", not the only valid code.
        repository.save("first-code", "https://example.com/page");
        repository.save("second-code", "https://example.com/page");

        assertThat(repository.findByUrl("https://example.com/page")).contains("second-code");
        // Both codes individually remain valid and resolvable:
        assertThat(repository.findByCode("first-code")).contains("https://example.com/page");
        assertThat(repository.findByCode("second-code")).contains("https://example.com/page");
    }

    @Test
    void exactStringMatchOnlyNoNormalization() {
        repository.save("code1", "https://example.com/page");

        // Trailing slash difference - per requirements §2.4, treated as a
        // DIFFERENT url, so no existing mapping should be found.
        assertThat(repository.findByUrl("https://example.com/page/")).isEmpty();
    }

    /**
     * Concurrency stress test that HONESTLY DEMONSTRATES the TOCTOU gap
     * documented in LLD §9, rather than asserting a guarantee the system
     * does not actually provide.
     *
     * <p>This mirrors the real service-layer pattern: read {@code findByUrl}
     * to check if a mapping already exists, and only {@code save} if absent.
     * That check-then-act sequence is NOT atomic - {@code save()} itself is
     * synchronized internally, but the preceding read happens outside any
     * lock. Under heavy concurrent contention on the exact same URL, multiple
     * threads can observe "not found" before any of them have saved, and each
     * proceed to save - meaning more than one save CAN occur for the same URL.
     *
     * <p>This test does not assert "only one save happens" because that would
     * misrepresent what the code actually guarantees. Instead it asserts the
     * property the repository DOES guarantee unconditionally: whatever the
     * final state is, {@code codeToUrl} and {@code urlToCode} are never
     * mutually inconsistent (no torn/partial writes - see {@code save()}'s
     * synchronized block). The race affects *how many* codes might get minted
     * for one URL under pathological concurrent load; it does not affect data
     * integrity. This is the documented, deliberately-accepted tradeoff from
     * LLD §9 - this test exists to keep that claim honest and falsifiable,
     * not to paper over it.
     */
    @Test
    void demonstratesDocumentedToctouGapUnderConcurrentLoadOnSameUrl() throws InterruptedException {
        String url = "https://example.com/race-condition-test";
        int threadCount = 100;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch readyLatch = new CountDownLatch(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);
        AtomicInteger savesPerformed = new AtomicInteger(0);

        for (int i = 0; i < threadCount; i++) {
            final String candidateCode = "code" + i;
            executor.submit(() -> {
                try {
                    readyLatch.countDown();
                    startLatch.await();
                    // Deliberately mirrors UrlShortenerService.shortenWithGeneratedCode:
                    // an unsynchronized read followed by a synchronized write.
                    if (repository.findByUrl(url).isEmpty()) {
                        repository.save(candidateCode, url);
                        savesPerformed.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        readyLatch.await();
        startLatch.countDown(); // release all threads at once to maximize contention
        boolean completed = doneLatch.await(10, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(completed).as("all threads should complete within timeout").isTrue();

        // GUARANTEE THE SYSTEM DOES PROVIDE: no matter how many saves raced
        // through, the two maps agree on whichever code is currently the
        // canonical reverse-index entry for this url. No mismatched state.
        Optional<String> winningCode = repository.findByUrl(url);
        assertThat(winningCode).isPresent();
        assertThat(repository.findByCode(winningCode.get())).contains(url);

        // GUARANTEE THE SYSTEM DOES NOT PROVIDE (intentionally, per LLD §9):
        // we do NOT assert savesPerformed == 1. Under real contention this
        // count is frequently > 1, which is the documented TOCTOU gap, not a
        // bug discovered by this test. We only assert it's at least 1 (sanity
        // check that the test setup itself isn't broken).
        assertThat(savesPerformed.get()).isGreaterThanOrEqualTo(1);
    }

    /**
     * A stricter companion test: when saves are properly serialized through
     * the synchronized save() method alone (no unsynchronized pre-check, i.e.
     * testing the repository's OWN atomicity guarantee in isolation), the two
     * maps must never be observably inconsistent under concurrent writes to
     * DIFFERENT codes/urls.
     */
    @Test
    void concurrentSavesForDifferentUrlsAreAllPersistedConsistently() throws InterruptedException {
        int threadCount = 200;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);

        List<String> codes = IntStream.range(0, threadCount)
                .mapToObj(i -> "code" + i)
                .collect(Collectors.toList());

        for (String code : codes) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    repository.save(code, "https://example.com/" + code);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        boolean completed = doneLatch.await(10, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(completed).isTrue();

        // Every single mapping must be present and consistent in BOTH maps.
        for (String code : codes) {
            String expectedUrl = "https://example.com/" + code;
            assertThat(repository.findByCode(code)).contains(expectedUrl);
            assertThat(repository.findByUrl(expectedUrl)).contains(code);
        }
    }
}
