package com.climind.saas.auth;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class UserLockoutTest {

    private static final Instant NOW = Instant.parse("2026-10-07T15:00:00Z");
    private static final Duration BASE = Duration.ofMinutes(15);

    @Test
    void bloqueaAlQuintoIntentoYDuplicaEnCadaBloqueo() {
        User user = new User("ana@ejemplo.com", "hash", "Ana", "Prueba", NOW);
        for (int i = 1; i <= 4; i++) {
            assertThat(user.registerFailedLogin(NOW, 5, BASE)).isFalse();
        }
        assertThat(user.registerFailedLogin(NOW, 5, BASE)).isTrue();
        assertThat(user.getLockedUntil()).isEqualTo(NOW.plus(Duration.ofMinutes(15)));
        assertThat(user.isLocked(NOW.plus(Duration.ofMinutes(14)))).isTrue();
        assertThat(user.isLocked(NOW.plus(Duration.ofMinutes(15)))).isFalse();

        for (int i = 6; i <= 10; i++) {
            user.registerFailedLogin(NOW, 5, BASE);
        }
        assertThat(user.getLockedUntil()).isEqualTo(NOW.plus(Duration.ofMinutes(30)));
    }

    @Test
    void elBloqueoNuncaSuperaUnDia() {
        User user = new User("ana@ejemplo.com", "hash", "Ana", "Prueba", NOW);
        for (int i = 1; i <= 100; i++) {
            user.registerFailedLogin(NOW, 5, BASE);
        }
        assertThat(user.getLockedUntil()).isEqualTo(NOW.plus(Duration.ofHours(24)));
    }

    @Test
    void loginExitosoReiniciaElContador() {
        User user = new User("ana@ejemplo.com", "hash", "Ana", "Prueba", NOW);
        user.registerFailedLogin(NOW, 5, BASE);
        user.registerSuccessfulLogin(NOW);
        assertThat(user.getFailedLoginCount()).isZero();
        assertThat(user.getLockedUntil()).isNull();
        assertThat(user.getLastLoginAt()).isEqualTo(NOW);
    }

    @Test
    void normalizaElEmail() {
        assertThat(new User("  Ana@Ejemplo.COM ", "h", " Ana ", " Prueba ", NOW).getEmail()).isEqualTo("ana@ejemplo.com");
    }
}
