package com.climind.saas.shared.ratelimit;

import com.climind.saas.shared.config.AppProperties;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Rate limiting en memoria con token buckets (Bucket4j). Suficiente para una única instancia;
 * para escalar horizontalmente se reemplaza por Bucket4j sobre PostgreSQL/Redis sin cambiar los llamadores.
 */
@Component
public class RateLimiter {

    public enum Policy {
        /** Endpoints de autenticación por IP (login, registro, recuperación). */
        AUTH_IP,
        /** Acciones que envían emails, por dirección de email (verificación, recuperación). */
        EMAIL_ACTION,
        /** Intentos de login por email (complementa el bloqueo de la cuenta). */
        LOGIN_EMAIL,
        /** API general por IP. */
        API_IP,
        /** Cotizaciones del cotizador público por IP. */
        PUBLIC_QUOTE_IP,
        /** Consultas (datos de contacto) del cotizador público por IP. */
        PUBLIC_LEAD_IP,
        /** Estimaciones en vivo de las demos públicas (landing) por IP. No guardan nada. */
        PUBLIC_DEMO_IP,
        /** Exportación completa de los datos de un negocio, por negocio. */
        ACCOUNT_EXPORT,
        /** Acciones de la cuenta que verifican la contraseña (cambio de clave, baja), por usuario. */
        ACCOUNT_PASSWORD
    }

    private record Entry(Bucket bucket, long lastAccessNanos) {
    }

    private final Map<String, Entry> buckets = new ConcurrentHashMap<>();
    private final AppProperties.RateLimit limits;

    public RateLimiter(AppProperties properties) {
        this.limits = properties.getRateLimit();
    }

    public boolean tryConsume(Policy policy, String key) {
        String compositeKey = policy.name() + ":" + key;
        Entry entry = buckets.compute(compositeKey, (k, existing) -> new Entry(
                existing != null ? existing.bucket() : newBucket(policy), System.nanoTime()));
        return entry.bucket().tryConsume(1);
    }

    private Bucket newBucket(Policy policy) {
        var builder = Bucket.builder();
        switch (policy) {
            case AUTH_IP -> builder.addLimit(limit(limits.getAuthPerMinutePerIp(), Duration.ofMinutes(1)));
            case EMAIL_ACTION -> builder.addLimit(limit(limits.getEmailActionsPerHour(), Duration.ofHours(1)));
            case LOGIN_EMAIL -> builder.addLimit(limit(10, Duration.ofMinutes(15)));
            case API_IP -> builder.addLimit(limit(limits.getApiPerMinutePerIp(), Duration.ofMinutes(1)));
            case PUBLIC_QUOTE_IP -> builder.addLimit(limit(limits.getPublicQuotesPerMinutePerIp(), Duration.ofMinutes(1)));
            case PUBLIC_LEAD_IP -> builder.addLimit(limit(limits.getPublicLeadsPerHourPerIp(), Duration.ofHours(1)));
            case PUBLIC_DEMO_IP -> builder.addLimit(limit(120, Duration.ofMinutes(1)));
            case ACCOUNT_EXPORT -> builder.addLimit(limit(5, Duration.ofHours(1)));
            case ACCOUNT_PASSWORD -> builder.addLimit(limit(10, Duration.ofMinutes(15)));
        }
        return builder.build();
    }

    private static Bandwidth limit(int capacity, Duration period) {
        return Bandwidth.builder().capacity(capacity).refillGreedy(capacity, period).build();
    }

    /** Libera memoria: un bucket sin uso por más de un día ya está completamente recargado. */
    @Scheduled(fixedDelay = 3_600_000L)
    public void evictIdle() {
        long threshold = System.nanoTime() - Duration.ofDays(1).toNanos();
        buckets.entrySet().removeIf(e -> e.getValue().lastAccessNanos() < threshold);
    }

    /** Solo para tests. */
    public void reset() {
        buckets.clear();
    }
}
