package com.climind.saas.shared.util;

import java.security.SecureRandom;
import java.util.UUID;

/**
 * Generador de UUID versión 7 (RFC 9562): ordenados por tiempo, lo que mantiene los índices
 * B-tree compactos, y no adivinables gracias a 74 bits aleatorios.
 */
public final class Ids {

    private static final SecureRandom RANDOM = new SecureRandom();

    private Ids() {
    }

    public static UUID newId() {
        long millis = System.currentTimeMillis();
        long randA = RANDOM.nextInt(1 << 12);
        long randB = RANDOM.nextLong();
        long msb = (millis << 16) | (0x7L << 12) | randA;
        long lsb = (randB & 0x3FFFFFFFFFFFFFFFL) | 0x8000000000000000L;
        return new UUID(msb, lsb);
    }
}
