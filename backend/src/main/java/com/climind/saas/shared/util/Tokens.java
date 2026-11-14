package com.climind.saas.shared.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/** Tokens opacos de un solo uso: se envía el valor en claro y se persiste solo su hash SHA-256. */
public final class Tokens {

    private static final SecureRandom RANDOM = new SecureRandom();

    private Tokens() {
    }

    /** 32 bytes aleatorios codificados en base64url sin padding (43 caracteres). */
    public static String newToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 no disponible", e);
        }
    }

    /** Formato válido de un token emitido por {@link #newToken()}: evita consultas con basura. */
    public static boolean looksValid(String token) {
        return token != null && token.length() == 43 && token.matches("[A-Za-z0-9_-]+");
    }
}
