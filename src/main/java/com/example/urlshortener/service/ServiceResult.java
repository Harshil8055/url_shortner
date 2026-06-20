package com.example.urlshortener.service;

/**
 * Generic result wrapper used by {@link UrlShortenerService} instead of throwing
 * exceptions for expected business outcomes (invalid input, conflicts, not found).
 *
 * <p>The controller layer inspects {@link #getStatus()} and maps it directly to an
 * HTTP response - no try/catch, no global exception handler. Genuine unexpected
 * errors (bugs) are NOT represented here; those remain real exceptions handled by
 * Spring's default error handling, separate from this contract.
 *
 * @param <T> the type of the success payload (e.g. {@code ShortenResponse} for the
 *            shorten flow, {@code String} for the redirect flow)
 */
public class ServiceResult<T> {

    public enum Status {
        SUCCESS,
        INVALID_URL,
        INVALID_ALIAS,
        ALIAS_CONFLICT,
        NOT_FOUND,
        GENERATION_FAILED
    }

    private final Status status;
    private final T data;
    private final String message;

    private ServiceResult(Status status, T data, String message) {
        this.status = status;
        this.data = data;
        this.message = message;
    }

    public static <T> ServiceResult<T> success(T data) {
        return new ServiceResult<>(Status.SUCCESS, data, null);
    }

    public static <T> ServiceResult<T> invalidUrl(String message) {
        return new ServiceResult<>(Status.INVALID_URL, null, message);
    }

    public static <T> ServiceResult<T> invalidAlias(String message) {
        return new ServiceResult<>(Status.INVALID_ALIAS, null, message);
    }

    public static <T> ServiceResult<T> aliasConflict(String message) {
        return new ServiceResult<>(Status.ALIAS_CONFLICT, null, message);
    }

    public static <T> ServiceResult<T> notFound(String message) {
        return new ServiceResult<>(Status.NOT_FOUND, null, message);
    }

    public static <T> ServiceResult<T> generationFailed(String message) {
        return new ServiceResult<>(Status.GENERATION_FAILED, null, message);
    }

    public Status getStatus() {
        return status;
    }

    public T getData() {
        return data;
    }

    public String getMessage() {
        return message;
    }

    public boolean isSuccess() {
        return status == Status.SUCCESS;
    }
}