package com.climind.saas.shared.util;

/** Enmascarado de datos personales para logs. */
public final class Masking {

    private Masking() {
    }

    /** "gaston@climind-ia.com" → "g*****@climind-ia.com". */
    public static String email(String email) {
        if (email == null || email.isBlank()) {
            return "";
        }
        int at = email.indexOf('@');
        if (at <= 0) {
            return "***";
        }
        String local = email.substring(0, at);
        return local.charAt(0) + "*".repeat(Math.max(1, local.length() - 1)) + email.substring(at);
    }
}
