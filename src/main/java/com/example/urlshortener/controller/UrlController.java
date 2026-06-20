package com.example.urlshortener.controller;

import com.example.urlshortener.dto.ErrorResponse;
import com.example.urlshortener.dto.ShortenRequest;
import com.example.urlshortener.dto.ShortenResponse;
import com.example.urlshortener.service.ServiceResult;
import com.example.urlshortener.service.UrlShortenerService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;

/**
 * Exposes POST /shorten and GET /{code}.
 *
 * <p>Contains no business logic - purely translates between HTTP concerns
 * (request/response DTOs, status codes) and {@link ServiceResult} outcomes
 * returned by {@link UrlShortenerService}.
 */
@RestController
public class UrlController {

    private final UrlShortenerService service;

    public UrlController(UrlShortenerService service) {
        this.service = service;
    }

    @PostMapping("/shorten")
    public ResponseEntity<?> shorten(@RequestBody ShortenRequest request, HttpServletRequest httpRequest) {
        String baseUrl = ServletUriComponentsBuilder.fromRequestUri(httpRequest)
                .replacePath(null)
                .replaceQuery(null)
                .build()
                .toUriString();

        ServiceResult<ShortenResponse> result = service.shorten(request.getUrl(), request.getAlias(), baseUrl);

        return switch (result.getStatus()) {
            case SUCCESS -> ResponseEntity.status(HttpStatus.CREATED).body(result.getData());
            case INVALID_URL, INVALID_ALIAS -> ResponseEntity.badRequest()
                    .body(new ErrorResponse(result.getStatus().name(), result.getMessage()));
            case ALIAS_CONFLICT -> ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(new ErrorResponse(result.getStatus().name(), result.getMessage()));
            case GENERATION_FAILED -> ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(new ErrorResponse(result.getStatus().name(), result.getMessage()));
            default -> ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(new ErrorResponse("UNKNOWN", "Unexpected result status."));
        };
    }

    @GetMapping("/{code}")
    public ResponseEntity<?> redirect(@PathVariable String code) {
        ServiceResult<String> result = service.redirect(code);

        if (result.isSuccess()) {
            return ResponseEntity.status(HttpStatus.MOVED_PERMANENTLY)
                    .location(URI.create(result.getData()))
                    .build();
        }
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ErrorResponse("NOT_FOUND", result.getMessage()));
    }
}