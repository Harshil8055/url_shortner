package com.example.urlshortener.validation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class AliasValidatorTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "my-link",
            "a.b~c_d",
            "ALLCAPS123",
            "a",                  // 1 char - lower boundary
    })
    void acceptsUrlSafeAliases(String alias) {
        assertThat(AliasValidator.isValid(alias)).isTrue();
    }

    @Test
    void acceptsAliasAtMaxLengthBoundary() {
        String alias = "a".repeat(AliasValidator.MAX_LENGTH);
        assertThat(AliasValidator.isValid(alias)).isTrue();
    }

    @Test
    void rejectsAliasOverMaxLength() {
        String alias = "a".repeat(AliasValidator.MAX_LENGTH + 1);
        assertThat(AliasValidator.isValid(alias)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "my link",     // space
            "my/link",     // slash
            "my?link",     // query char
            "my#link",     // fragment char
            "my@link",     // not in unreserved set
            ""             // empty - below min length
    })
    void rejectsNonUrlSafeOrEmptyAliases(String alias) {
        assertThat(AliasValidator.isValid(alias)).isFalse();
    }

    @Test
    void rejectsNull() {
        assertThat(AliasValidator.isValid(null)).isFalse();
    }
}
