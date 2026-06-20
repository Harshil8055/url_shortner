package com.example.urlshortener.dto;

/**
 * Error response body used for 400, 404, 409, and 500 responses.
 *
 * {@code error} is a short, machine-friendly code (matches the
 * {@link com.example.urlshortener.service.ServiceResult.Status} name),
 * useful for clients that want to branch on error type without parsing prose.
 * {@code message} is a human-readable detail for debugging/display.
 */
public class ErrorResponse {

    private final String error;
    private final String message;

    public ErrorResponse(String error, String message) {
        this.error = error;
        this.message = message;
    }

    public String getError() {
        return error;
    }

    public String getMessage() {
        return message;
    }
}