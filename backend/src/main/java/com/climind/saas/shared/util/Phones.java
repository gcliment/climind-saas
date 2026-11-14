package com.climind.saas.shared.util;

import java.util.Optional;

/**
 * Normalización de números de WhatsApp al formato de wa.me (solo dígitos, con código de país).
 * <p>
 * Los visitantes argentinos suelen escribir el número sin código de país ("11 2345-6789", "011 15 2345-6789").
 * Para Argentina, los celulares en wa.me llevan "549" + código de área + número (10 dígitos, sin 0 ni 15).
 */
public final class Phones {

    private Phones() {
    }

    /**
     * @param raw     lo que escribió la persona
     * @param country país del negocio (ISO 3166-1 alfa-2) para completar el código de país
     * @return dígitos para wa.me, o vacío si no parece un número válido
     */
    public static Optional<String> whatsapp(String raw, String country) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        boolean international = raw.strip().startsWith("+");
        String digits = raw.replaceAll("\\D", "");
        if (digits.startsWith("00")) {
            digits = digits.substring(2);
            international = true;
        }
        if ("AR".equalsIgnoreCase(country)) {
            digits = argentina(digits, international);
        }
        return digits.length() >= 8 && digits.length() <= 15 ? Optional.of(digits) : Optional.empty();
    }

    private static String argentina(String digits, boolean international) {
        if (international || (digits.startsWith("54") && digits.length() >= 12)) {
            if (digits.startsWith("54") && !digits.startsWith("549") && digits.length() == 12) {
                return "549" + digits.substring(2); // +54 11 2345-6789: falta el 9 de celular
            }
            return digits;
        }
        String local = digits.startsWith("0") ? digits.substring(1) : digits;
        // "11 15 2345 6789": área (2 a 4 dígitos) + 15 + número. Con 12 dígitos y un "15" después del área, se quita.
        if (local.length() == 12) {
            for (int area = 2; area <= 4; area++) {
                if (local.startsWith("15", area)) {
                    local = local.substring(0, area) + local.substring(area + 2);
                    break;
                }
            }
        }
        return local.length() == 10 ? "549" + local : local;
    }

    private static final java.util.regex.Pattern AR_MOBILE =
            java.util.regex.Pattern.compile("^549(11|2\\d{2}|3\\d{2})(\\d{3,4})(\\d{4})$");

    /** Para mostrar: "5491123456789" → "+54 9 11 2345-6789" (igual que format.js). Otros países: "+" y los dígitos. */
    public static String display(String digits) {
        if (digits == null || digits.isBlank()) {
            return "";
        }
        java.util.regex.Matcher m = AR_MOBILE.matcher(digits);
        return m.matches() ? "+54 9 " + m.group(1) + " " + m.group(2) + "-" + m.group(3) : "+" + digits;
    }
}
