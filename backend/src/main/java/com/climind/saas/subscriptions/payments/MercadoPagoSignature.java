package com.climind.saas.subscriptions.payments;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Locale;

/**
 * Validación de la firma {@code x-signature} de las notificaciones de Mercado Pago (documentación oficial,
 * "Validar origen de una notificación"):
 * <ol>
 *   <li>El header tiene la forma {@code ts=...,v1=...}.</li>
 *   <li>Se arma el manifest {@code id:[data.id];request-id:[x-request-id];ts:[ts];}. Un {@code data.id}
 *       alfanumérico va en minúsculas; si falta un valor, se omite su parte.</li>
 *   <li>{@code v1} debe ser el HMAC-SHA256 en hexadecimal del manifest con la clave secreta de Webhooks.</li>
 * </ol>
 * Además se rechazan notificaciones con un {@code ts} fuera de la tolerancia (repetición de mensajes viejos).
 */
public final class MercadoPagoSignature {

    private MercadoPagoSignature() {
    }

    public static boolean verify(String header, String requestId, String dataId, String secret, Instant now,
                                 Duration tolerance) {
        if (header == null || header.isBlank() || secret == null || secret.isBlank()) {
            return false;
        }
        String ts = null;
        String v1 = null;
        for (String part : header.split(",")) {
            String[] kv = part.trim().split("=", 2);
            if (kv.length == 2 && kv[0].trim().equals("ts")) {
                ts = kv[1].trim();
            } else if (kv.length == 2 && kv[0].trim().equals("v1")) {
                v1 = kv[1].trim();
            }
        }
        if (ts == null || v1 == null || !ts.matches("\\d{1,16}")) {
            return false;
        }
        long value = Long.parseLong(ts);
        // La documentación lo describe en milisegundos pero los ejemplos usan segundos: se aceptan ambos.
        Instant sent = value > 100_000_000_000L ? Instant.ofEpochMilli(value) : Instant.ofEpochSecond(value);
        if (Duration.between(sent, now).abs().compareTo(tolerance) > 0) {
            return false;
        }
        String expected = hmac(secret, manifest(dataId, requestId, ts));
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII),
                v1.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.US_ASCII));
    }

    static String manifest(String dataId, String requestId, String ts) {
        StringBuilder m = new StringBuilder();
        if (dataId != null && !dataId.isBlank()) {
            String id = dataId.trim();
            m.append("id:").append(id.matches("[A-Za-z0-9]+") ? id.toLowerCase(Locale.ROOT) : id).append(';');
        }
        if (requestId != null && !requestId.isBlank()) {
            m.append("request-id:").append(requestId.trim()).append(';');
        }
        return m.append("ts:").append(ts).append(';').toString();
    }

    public static String hmac(String secret, String message) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(message.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 no disponible", e);
        }
    }
}
