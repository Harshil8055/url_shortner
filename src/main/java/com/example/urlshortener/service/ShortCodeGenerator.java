package com.example.urlshortener.service;

import org.springframework.stereotype.Component;

import java.security.SecureRandom;

/**
 * Generates random Base62 short codes of fixed length 7.
 *
 * <p>Search space: 62^7 ~= 3.5 trillion combinations. This class has no knowledge
 * of collisions or persistence - it is a pure generator. Collision detection and
 * retry orchestration live in {@link UrlShortenerService}, keeping this class a
 * single-responsibility, trivially unit-testable component (assert length,
 * assert alphabet membership, statistical distribution checks).
 *
 * <p>Uses {@link SecureRandom} rather than {@link java.util.Random} for
 * cryptographically strong randomness - a defensible default with negligible
 * performance cost at this scale, avoiding any theoretical predictability concern.
 */
@Component
public class ShortCodeGenerator {

    private static final String ALPHABET =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
    private static final int CODE_LENGTH = 7;

    private final SecureRandom random = new SecureRandom();

    public String generate() {
        StringBuilder sb = new StringBuilder(CODE_LENGTH);
        for (int i = 0; i < CODE_LENGTH; i++) {
            sb.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        return sb.toString();
    }
}