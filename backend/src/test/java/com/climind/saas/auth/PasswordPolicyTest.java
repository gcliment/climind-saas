package com.climind.saas.auth;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class PasswordPolicyTest {

    @Test
    void aceptaContrasenasLargasYPropias() {
        assertThat(PasswordPolicy.validate("Cotizar-Facil-2026", "ana@ejemplo.com")).isEmpty();
        assertThat(PasswordPolicy.validate("mi perro se llama tango", "ana@ejemplo.com")).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "corta", "Abc-123"})
    void rechazaContrasenasCortas(String password) {
        assertThat(PasswordPolicy.validate(password, "ana@ejemplo.com")).isPresent();
    }

    @ParameterizedTest
    @ValueSource(strings = {"1234567890", "12345678", "Password", "Contraseña1", "aaaaaaaaaaaa", "abababababab", "ARGENTINA123"})
    void rechazaContrasenasComunesORepetitivas(String password) {
        assertThat(PasswordPolicy.validate(password, "ana@ejemplo.com")).isPresent();
    }

    @Test
    void rechazaContrasenasQueContienenElEmail() {
        assertThat(PasswordPolicy.validate("gaston.climent-2026", "gaston.climent@ejemplo.com"))
                .hasValueSatisfying(m -> assertThat(m).contains("email"));
    }

    @Test
    void rechazaMasDe72BytesPorElLimiteDeBcrypt() {
        assertThat(PasswordPolicy.validate("abcdefgh1".repeat(8), "x@y.com")).isEmpty(); // 72 bytes
        assertThat(PasswordPolicy.validate("abcdefgh1".repeat(8) + "z", "x@y.com")).isPresent(); // 73 bytes
        // Caracteres multibyte cuentan como bytes UTF-8.
        assertThat(PasswordPolicy.validate("ñ".repeat(37), "x@y.com")).isPresent();
    }
}
