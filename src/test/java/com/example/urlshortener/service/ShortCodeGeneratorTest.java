package com.example.urlshortener.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.RepeatedTest;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ShortCodeGeneratorTest {

    private static final String EXPECTED_ALPHABET =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";

    private final ShortCodeGenerator generator = new ShortCodeGenerator();

    @RepeatedTest(50)
    void generatesCodeOfExactlySevenCharacters() {
        String code = generator.generate();
        assertThat(code).hasSize(7);
    }

    @RepeatedTest(50)
    void generatesCodeUsingOnlyBase62Alphabet() {
        String code = generator.generate();
        for (char c : code.toCharArray()) {
            assertThat(EXPECTED_ALPHABET.indexOf(c))
                    .as("character '%s' should be in the Base62 alphabet", c)
                    .isGreaterThanOrEqualTo(0);
        }
    }

    @Test
    void generatesDistinctCodesAcrossManyInvocations() {
        // Not a formal proof of no-collision (impossible to test exhaustively),
        // but a basic sanity check that the generator isn't degenerate / isn't
        // always returning the same value or a tiny cycling set.
        Set<String> seen = new HashSet<>();
        int sampleSize = 10_000;
        for (int i = 0; i < sampleSize; i++) {
            seen.add(generator.generate());
        }
        // With 62^7 ~= 3.5 trillion possibilities, 10,000 draws should be
        // essentially all unique - allow a generous margin for paranoia.
        assertThat(seen.size()).isGreaterThan((int) (sampleSize * 0.999));
    }
}
