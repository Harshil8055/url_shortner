package com.example.urlshortener.service;

import com.example.urlshortener.dto.ShortenResponse;
import com.example.urlshortener.repository.UrlRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UrlShortenerServiceTest {

    private static final String BASE_URL = "http://localhost:8080";

    @Mock
    private UrlRepository repository;

    @Mock
    private ShortCodeGenerator generator;

    private UrlShortenerService service;

    @BeforeEach
    void setUp() {
        service = new UrlShortenerService(repository, generator);
    }

    // ---- shorten(): URL validation ----

    @Test
    void shortenReturnsInvalidUrlForMalformedUrl() {
        ServiceResult<ShortenResponse> result = service.shorten("not a url", null, BASE_URL);

        assertThat(result.getStatus()).isEqualTo(ServiceResult.Status.INVALID_URL);
        assertThat(result.getData()).isNull();
    }

    @Test
    void shortenDoesNotTouchRepositoryWhenUrlIsInvalid() {
        service.shorten("not a url", null, BASE_URL);

        verify(repository, never()).save(anyString(), anyString());
    }

    // ---- shorten(): no-alias, new URL ----

    @Test
    void shortenGeneratesNewCodeForUnseenUrlWithNoAlias() {
        String url = "https://example.com/foo";
        when(repository.findByUrl(url)).thenReturn(Optional.empty());
        when(generator.generate()).thenReturn("aZ3x9Qm");
        when(repository.existsByCode("aZ3x9Qm")).thenReturn(false);

        ServiceResult<ShortenResponse> result = service.shorten(url, null, BASE_URL);

        assertThat(result.getStatus()).isEqualTo(ServiceResult.Status.SUCCESS);
        assertThat(result.getData().getCode()).isEqualTo("aZ3x9Qm");
        assertThat(result.getData().getOriginalUrl()).isEqualTo(url);
        assertThat(result.getData().getShortUrl()).isEqualTo(BASE_URL + "/aZ3x9Qm");
        verify(repository).save("aZ3x9Qm", url);
    }

    // ---- shorten(): no-alias, duplicate URL (idempotency) ----

    @Test
    void shortenReturnsExistingCodeForDuplicateUrlWithNoAlias() {
        String url = "https://example.com/foo";
        when(repository.findByUrl(url)).thenReturn(Optional.of("existingCode123"));

        ServiceResult<ShortenResponse> result = service.shorten(url, null, BASE_URL);

        assertThat(result.getStatus()).isEqualTo(ServiceResult.Status.SUCCESS);
        assertThat(result.getData().getCode()).isEqualTo("existingCode123");
        // Idempotent path must NOT generate a new code or write anything.
        verify(generator, never()).generate();
        verify(repository, never()).save(anyString(), anyString());
    }

    // ---- shorten(): collision retry ----

    @Test
    void shortenRetriesGenerationOnCollisionAndSucceeds() {
        String url = "https://example.com/foo";
        when(repository.findByUrl(url)).thenReturn(Optional.empty());
        when(generator.generate()).thenReturn("collide1", "collide1", "freeCode");
        when(repository.existsByCode("collide1")).thenReturn(true);
        when(repository.existsByCode("freeCode")).thenReturn(false);

        ServiceResult<ShortenResponse> result = service.shorten(url, null, BASE_URL);

        assertThat(result.getStatus()).isEqualTo(ServiceResult.Status.SUCCESS);
        assertThat(result.getData().getCode()).isEqualTo("freeCode");
        verify(generator, times(3)).generate();
        verify(repository).save("freeCode", url);
    }

    @Test
    void shortenReturnsGenerationFailedAfterExhaustingRetries() {
        String url = "https://example.com/foo";
        when(repository.findByUrl(url)).thenReturn(Optional.empty());
        when(generator.generate()).thenReturn("alwaysCollides");
        when(repository.existsByCode("alwaysCollides")).thenReturn(true);

        ServiceResult<ShortenResponse> result = service.shorten(url, null, BASE_URL);

        assertThat(result.getStatus()).isEqualTo(ServiceResult.Status.GENERATION_FAILED);
        verify(generator, times(5)).generate(); // MAX_GENERATION_RETRIES = 5
        verify(repository, never()).save(anyString(), anyString());
    }

    // ---- shorten(): custom alias ----

    @Test
    void shortenSavesDirectlyWithFreeCustomAlias() {
        String url = "https://example.com/foo";
        when(repository.existsByCode("my-link")).thenReturn(false);

        ServiceResult<ShortenResponse> result = service.shorten(url, "my-link", BASE_URL);

        assertThat(result.getStatus()).isEqualTo(ServiceResult.Status.SUCCESS);
        assertThat(result.getData().getCode()).isEqualTo("my-link");
        verify(repository).save("my-link", url);
        // Custom alias path must NOT perform the no-alias dedup lookup.
        verify(repository, never()).findByUrl(anyString());
    }

    @Test
    void shortenReturnsAliasConflictWhenAliasAlreadyTaken() {
        String url = "https://example.com/foo";
        when(repository.existsByCode("my-link")).thenReturn(true);

        ServiceResult<ShortenResponse> result = service.shorten(url, "my-link", BASE_URL);

        assertThat(result.getStatus()).isEqualTo(ServiceResult.Status.ALIAS_CONFLICT);
        verify(repository, never()).save(anyString(), anyString());
    }

    @Test
    void shortenReturnsInvalidAliasForBadCharacters() {
        String url = "https://example.com/foo";

        ServiceResult<ShortenResponse> result = service.shorten(url, "bad alias!", BASE_URL);

        assertThat(result.getStatus()).isEqualTo(ServiceResult.Status.INVALID_ALIAS);
        verify(repository, never()).save(anyString(), anyString());
    }

    @Test
    void shortenAllowsCustomAliasEvenWhenUrlAlreadyHasADifferentCode() {
        // Per requirements §2.3: custom-alias requests always create a new
        // entry, even if the URL was already shortened before. The dedup
        // check against existing URL mappings must be bypassed entirely.
        String url = "https://example.com/foo";
        when(repository.existsByCode("second-alias")).thenReturn(false);

        ServiceResult<ShortenResponse> result = service.shorten(url, "second-alias", BASE_URL);

        assertThat(result.getStatus()).isEqualTo(ServiceResult.Status.SUCCESS);
        verify(repository, never()).findByUrl(anyString());
        verify(repository).save("second-alias", url);
    }

    @Test
    void blankAliasIsTreatedAsNoAliasGiven() {
        String url = "https://example.com/foo";
        when(repository.findByUrl(url)).thenReturn(Optional.empty());
        when(generator.generate()).thenReturn("genCode1");
        when(repository.existsByCode("genCode1")).thenReturn(false);

        ServiceResult<ShortenResponse> result = service.shorten(url, "   ", BASE_URL);

        assertThat(result.getStatus()).isEqualTo(ServiceResult.Status.SUCCESS);
        assertThat(result.getData().getCode()).isEqualTo("genCode1");
    }

    // ---- redirect() ----

    @Test
    void redirectReturnsUrlForKnownCode() {
        when(repository.findByCode("abc1234")).thenReturn(Optional.of("https://example.com/foo"));

        ServiceResult<String> result = service.redirect("abc1234");

        assertThat(result.getStatus()).isEqualTo(ServiceResult.Status.SUCCESS);
        assertThat(result.getData()).isEqualTo("https://example.com/foo");
    }

    @Test
    void redirectReturnsNotFoundForUnknownCode() {
        when(repository.findByCode("doesNotExist")).thenReturn(Optional.empty());

        ServiceResult<String> result = service.redirect("doesNotExist");

        assertThat(result.getStatus()).isEqualTo(ServiceResult.Status.NOT_FOUND);
        assertThat(result.getData()).isNull();
    }
}
