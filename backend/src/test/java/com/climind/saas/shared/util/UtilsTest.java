package com.climind.saas.shared.util;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class UtilsTest {

    @Test
    void tokensSonUnicosConFormatoFijoYSeHasheanDeFormaDeterministica() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 1000; i++) {
            String token = Tokens.newToken();
            assertThat(Tokens.looksValid(token)).isTrue();
            assertThat(seen.add(token)).isTrue();
        }
        assertThat(Tokens.sha256Hex("abc"))
                .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
        assertThat(Tokens.looksValid("corto")).isFalse();
        assertThat(Tokens.looksValid("x".repeat(42) + "'")).isFalse();
        assertThat(Tokens.looksValid(null)).isFalse();
    }

    @Test
    void idsSonUuidV7Ordenados() throws InterruptedException {
        UUID first = Ids.newId();
        Thread.sleep(2);
        UUID second = Ids.newId();
        assertThat(first.version()).isEqualTo(7);
        assertThat(first.variant()).isEqualTo(2);
        assertThat(first.compareTo(second)).isNegative();
    }

    @Test
    void enmascaraEmails() {
        assertThat(Masking.email("gaston@climind-ia.com")).isEqualTo("g*****@climind-ia.com");
        assertThat(Masking.email("a@b.com")).isEqualTo("a*@b.com");
        assertThat(Masking.email("sin-arroba")).isEqualTo("***");
        assertThat(Masking.email(null)).isEmpty();
    }

    @Test
    void slugsSinAcentosNiSimbolos() {
        assertThat(Slugs.slugify("Limpiezas Ñandú & Cía.", 40)).isEqualTo("limpiezas-nandu-cia");
        assertThat(Slugs.slugify("  --  ", 40)).isEmpty();
        assertThat(Slugs.slugify("a".repeat(50), 10)).hasSize(10);
        assertThat(Slugs.randomSuffix(5)).matches("[a-z2-9]{5}");
    }
}
