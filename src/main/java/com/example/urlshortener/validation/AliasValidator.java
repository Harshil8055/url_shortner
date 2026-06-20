package com.example.urlshortener.validation;

import java.util.regex.Pattern;

/**
 * Validates custom aliases supplied in POST /shorten requests.
 *
 * <p>Character set: RFC 3986 unreserved characters (A-Z a-z 0-9 - _ . ~), matching
 * the "any URL-safe characters" decision in the requirements doc.
 *
 * <p>Length bound: 1-64 characters. This bound is an implementation assumption
 * (not explicitly specified by the stakeholder beyond "no limit, within reason") -
 * documented here and in the requirements doc so it's a visible, adjustable
 * decision rather than a hidden magic number.
 */
public final class AliasValidator {

    static final int MIN_LENGTH = 1;
    static final int MAX_LENGTH = 64;

    private static final Pattern URL_SAFE_PATTERN = Pattern.compile("^[A-Za-z0-9\\-_.~]+$");

    private AliasValidator() {
        // utility class, not instantiable
    }

    public static boolean isValid(String alias) {
        if (alias == null) {
            return false;
        }
        int length = alias.length();
        if (length < MIN_LENGTH || length > MAX_LENGTH) {
            return false;
        }
        return URL_SAFE_PATTERN.matcher(alias).matches();
    }
}