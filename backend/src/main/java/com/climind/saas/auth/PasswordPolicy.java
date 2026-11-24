package com.climind.saas.auth;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Política de contraseñas (criterio NIST 800-63B: largo por sobre reglas de composición).
 * <ul>
 *   <li>Mínimo 8 caracteres (NIST 800-63B), más la lista de contraseñas comunes.</li>
 *   <li>Máximo 72 bytes UTF-8: límite real de BCrypt (más allá se ignoraría en silencio).</li>
 *   <li>No puede ser una contraseña muy común ni contener la parte local del email.</li>
 * </ul>
 */
public final class PasswordPolicy {

    public static final int MIN_LENGTH = 8;
    public static final int MAX_BYTES = 72;

    private static final Set<String> COMMON = Set.of(
            "12345678", "123456789", "87654321", "11111111", "00000000", "12341234", "abcd1234", "abc12345",
            "password", "qwertyui", "qwerty12", "asdfghjk", "iloveyou", "argentina", "contra123",
            "teamo123", "boca1234", "river123", "climind1", "cotiza12", "cotizador",
            "1234567890", "12345678910", "0123456789", "1234512345", "1111111111", "0000000000",
            "qwertyuiop", "contraseña", "contrasena", "contraseña1", "contrasena1", "password1",
            "password12", "password123", "passw0rd123", "abcdefghij", "abc1234567", "iloveyou12",
            "argentina1", "argentina10", "argentina123", "bocajuniors", "riverplate", "teamo12345",
            "qwerty1234", "1q2w3e4r5t", "asdfghjkl1", "climind123", "cotiza1234", "administrador");

    private PasswordPolicy() {
    }

    /** Mensaje de error para mostrar al usuario, o vacío si la contraseña es aceptable. */
    public static Optional<String> validate(String password, String email) {
        if (password == null || password.codePointCount(0, password.length()) < MIN_LENGTH) {
            return Optional.of("La contraseña debe tener al menos " + MIN_LENGTH + " caracteres.");
        }
        if (password.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
            return Optional.of("La contraseña es demasiado larga (máximo 72 caracteres).");
        }
        String lower = password.toLowerCase(Locale.ROOT);
        if (COMMON.contains(lower) || lower.chars().distinct().count() <= 2) {
            return Optional.of("Esa contraseña es muy fácil de adivinar. Probá con una frase o combinación propia.");
        }
        if (email != null) {
            int at = email.indexOf('@');
            String local = (at > 0 ? email.substring(0, at) : email).toLowerCase(Locale.ROOT);
            if (local.length() >= 4 && lower.contains(local)) {
                return Optional.of("La contraseña no puede contener tu email.");
            }
        }
        return Optional.empty();
    }
}
