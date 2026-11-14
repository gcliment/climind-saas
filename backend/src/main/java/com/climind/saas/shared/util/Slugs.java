package com.climind.saas.shared.util;

import java.security.SecureRandom;
import java.text.Normalizer;
import java.util.Locale;

/** Slugs para URLs públicas: minúsculas, sin acentos, solo [a-z0-9-]. */
public final class Slugs {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String ALPHABET = "abcdefghijkmnpqrstuvwxyz23456789";

    private Slugs() {
    }

    public static String slugify(String text, int maxLength) {
        if (text == null) {
            return "";
        }
        String normalized = Normalizer.normalize(text, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-+|-+$)", "");
        if (normalized.length() > maxLength) {
            normalized = normalized.substring(0, maxLength).replaceAll("-+$", "");
        }
        return normalized;
    }

    public static String randomSuffix(int length) {
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        }
        return sb.toString();
    }
}
